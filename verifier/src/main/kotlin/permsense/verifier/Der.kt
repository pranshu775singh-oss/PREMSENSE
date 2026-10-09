package permsense.verifier

/**
 * Just enough DER to read the Android key attestation extension.
 *
 * Reads tag-length-value items. Refuses indefinite lengths, lengths that run past the data, and
 * anything nested deeper than a certificate extension ever is.
 */
class DerProblem(message: String) : Exception(message)

class DerItem(val tag: Int, val content: ByteArray) {

    val isSequence: Boolean get() = tag == 0x30
    val isOctetString: Boolean get() = tag == 0x04
    val isInteger: Boolean get() = tag == 0x02
    val isEnumerated: Boolean get() = tag == 0x0A

    /** The children of a constructed item. */
    fun children(): List<DerItem> {
        val out = ArrayList<DerItem>()
        var offset = 0
        while (offset < content.size) {
            val (item, next) = Der.read(content, offset)
            out += item
            offset = next
        }
        return out
    }

    fun asLong(): Long {
        if (content.isEmpty() || content.size > 8) throw DerProblem("integer of unsupported size")
        var v = if (content[0] < 0) -1L else 0L
        for (b in content) v = (v shl 8) or (b.toLong() and 0xFF)
        return v
    }
}

object Der {

    fun parse(data: ByteArray): DerItem {
        val (item, end) = read(data, 0)
        if (end != data.size) throw DerProblem("trailing bytes after the DER item")
        return item
    }

    fun read(data: ByteArray, start: Int): Pair<DerItem, Int> {

        if (start + 2 > data.size) throw DerProblem("truncated")

        val tag = data[start].toInt() and 0xFF
        if (tag and 0x1F == 0x1F) throw DerProblem("multi-byte tags are not used here")

        var offset = start + 1
        val first = data[offset++].toInt() and 0xFF

        val length: Int
        if (first < 0x80) {
            length = first
        } else {
            val count = first and 0x7F
            if (count == 0) throw DerProblem("indefinite length")
            if (count > 4) throw DerProblem("length too large")
            if (offset + count > data.size) throw DerProblem("truncated length")
            var l = 0L
            repeat(count) { l = (l shl 8) or (data[offset++].toLong() and 0xFF) }
            if (l > Int.MAX_VALUE) throw DerProblem("length too large")
            length = l.toInt()
        }

        if (offset + length > data.size || offset + length < offset) throw DerProblem("length runs past the data")

        return DerItem(tag, data.copyOfRange(offset, offset + length)) to (offset + length)
    }
}

/** The fields of an Android KeyDescription that a verifier needs. */
data class KeyDescription(
    val attestationVersion: Long,
    /** 0 = software, 1 = trusted environment (TEE), 2 = StrongBox. */
    val attestationSecurityLevel: Int,
    val keymasterSecurityLevel: Int,
    val challenge: ByteArray,
) {
    val securityLevelName: String
        get() = when (attestationSecurityLevel) {
            0 -> "software"
            1 -> "trusted environment (TEE)"
            2 -> "StrongBox"
            else -> "unknown ($attestationSecurityLevel)"
        }

    companion object {

        const val OID = "1.3.6.1.4.1.11129.2.1.17"

        /**
         * KeyDescription ::= SEQUENCE {
         *   attestationVersion INTEGER, attestationSecurityLevel ENUMERATED,
         *   keymasterVersion INTEGER, keymasterSecurityLevel ENUMERATED,
         *   attestationChallenge OCTET STRING, uniqueId OCTET STRING, ... }
         *
         * @param extensionValue what X509Certificate.getExtensionValue returns: the extension
         *   wrapped in one more OCTET STRING.
         */
        fun parse(extensionValue: ByteArray): KeyDescription {
            val wrapper = Der.parse(extensionValue)
            if (!wrapper.isOctetString) throw DerProblem("extension is not wrapped in an octet string")

            val seq = Der.parse(wrapper.content)
            if (!seq.isSequence) throw DerProblem("key description is not a sequence")

            val parts = seq.children()
            if (parts.size < 5) throw DerProblem("key description is too short")
            if (!parts[0].isInteger || !parts[1].isEnumerated || !parts[2].isInteger ||
                !parts[3].isEnumerated || !parts[4].isOctetString
            ) throw DerProblem("key description has an unexpected shape")

            return KeyDescription(
                attestationVersion = parts[0].asLong(),
                attestationSecurityLevel = parts[1].asLong().toInt(),
                keymasterSecurityLevel = parts[3].asLong().toInt(),
                challenge = parts[4].content,
            )
        }
    }
}
