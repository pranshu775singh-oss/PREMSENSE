package com.ojas.permsense.judgement

import com.ojas.permsense.json.Json
import com.ojas.permsense.json.JsonException
import com.ojas.permsense.json.arr
import com.ojas.permsense.json.int
import com.ojas.permsense.json.obj
import com.ojas.permsense.json.str

class PolicyPackException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One rule's settings, exactly as the pack file states them. */
data class RuleConfig(
    val id: String,
    val enabled: Boolean,
    /** 3 = act now, 2 = look at this, 1 = informational. */
    val severity: Int,
    /** The basis for the rule: a regulation clause, a study, or a design principle. Never empty. */
    val citation: String,
    val params: Map<String, Any?>,
) {

    fun long(name: String, default: Long): Long =
        when (val v = params[name]) {
            null -> default
            is Long -> v
            is Double -> v.toLong()
            else -> throw PolicyPackException("Rule $id: \"$name\" must be a number")
        }

    fun strings(name: String, default: Set<String>): Set<String> =
        when (val v = params[name]) {
            null -> default
            is List<*> -> v.map {
                it as? String ?: throw PolicyPackException("Rule $id: \"$name\" must list strings")
            }.toSet()
            else -> throw PolicyPackException("Rule $id: \"$name\" must be a list")
        }
}

data class PolicyPack(
    val schemaVersion: Int,
    val packId: String,
    val packVersion: String,
    val rules: List<RuleConfig>,
    /** Packages treated as digital-lending apps by the RBI rules. */
    val lendingApps: Set<String>,
    /** Things the loader ignored (unknown rules). The pack still loaded. */
    val warnings: List<String>,
) {

    /**
     * Rules whose citation still says a human reviewer has to confirm it. A report must say so:
     * a regulation reference nobody has checked is not a regulation reference.
     */
    fun rulesAwaitingReview(): List<String> =
        rules.filter { it.citation.contains(REVIEW_MARKER) }.map { it.id }

    /** True when this pack's version is strictly newer than [other], comparing dotted numbers. */
    fun isNewerThan(other: PolicyPack): Boolean = compareVersions(packVersion, other.packVersion) > 0

    companion object {

        const val REVIEW_MARKER = "TO BE CONFIRMED"

        fun compareVersions(a: String, b: String): Int {
            val x = a.split('.').map { it.toLongOrNull() ?: 0L }
            val y = b.split('.').map { it.toLongOrNull() ?: 0L }
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = (x.getOrNull(i) ?: 0L).compareTo(y.getOrNull(i) ?: 0L)
                if (c != 0) return c
            }
            return 0
        }
    }
}

/**
 * Reads and validates a rule pack. Rules are data, not compiled code, so a pack can be reviewed
 * by someone who did not write it, corrected, or extended without an app release.
 *
 * A pack that does not validate is rejected whole: half a rulebook silently applied is worse than
 * the one already in force.
 */
object PolicyPackLoader {

    const val SUPPORTED_SCHEMA = 1

    const val BUNDLED_RESOURCE = "/policy/default_pack.json"

    private val PACKAGE = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""")

    /** Rule ids this build knows how to evaluate. */
    val KNOWN_RULES = listOf("INV-01", "DEBT-01", "NEW-01", "RBI-01", "RBI-02", "RBI-03", "RBI-04", "RBI-05", "SPY-01")

    fun loadBundled(): PolicyPack {
        val stream = PolicyPackLoader::class.java.getResourceAsStream(BUNDLED_RESOURCE)
            ?: throw PolicyPackException("The bundled rule pack $BUNDLED_RESOURCE is missing")
        return parse(stream.readBytes().toString(Charsets.UTF_8))
    }

    fun parse(text: String): PolicyPack {

        val root = try {
            Json.parseObject(text)
        } catch (e: JsonException) {
            throw PolicyPackException("The rule pack is not valid JSON: ${e.message}", e)
        }

        try {
            val schema = root.int("schemaVersion")
            if (schema != SUPPORTED_SCHEMA) {
                throw PolicyPackException("Unsupported rule pack schema $schema (this build reads $SUPPORTED_SCHEMA)")
            }

            val meta = root.obj("pack")
            val packId = meta.str("id").also { if (it.isBlank()) throw PolicyPackException("Pack id is empty") }
            val packVersion = meta.str("version").also {
                if (!Regex("""^\d+(\.\d+)*$""").matches(it)) {
                    throw PolicyPackException("Pack version \"$it\" must be dotted numbers, like 2026.10.1")
                }
            }

            val warnings = ArrayList<String>()
            val seen = HashSet<String>()
            val rules = ArrayList<RuleConfig>()

            for (entry in root.arr("rules")) {

                @Suppress("UNCHECKED_CAST")
                val rule = entry as? Map<String, Any?> ?: throw PolicyPackException("Every rule must be an object")

                val id = rule.str("id")
                if (!seen.add(id)) throw PolicyPackException("Rule $id appears twice")

                val severity = rule.int("severity")
                if (severity !in 1..3) throw PolicyPackException("Rule $id: severity must be 1, 2 or 3")

                val citation = rule.str("citation")
                if (citation.isBlank()) throw PolicyPackException("Rule $id has no citation")

                val params = if (rule.containsKey("params")) rule.obj("params") else emptyMap()
                val enabled = (rule["enabled"] as? Boolean) ?: true

                if (id !in KNOWN_RULES) {
                    warnings += "Ignored unknown rule $id"
                    continue
                }

                rules += RuleConfig(id, enabled, severity, citation, params)
            }

            val lending = LinkedHashSet<String>()
            if (root.containsKey("lendingApps")) {
                for (entry in root.arr("lendingApps")) {
                    @Suppress("UNCHECKED_CAST")
                    val app = entry as? Map<String, Any?> ?: throw PolicyPackException("Every lending app must be an object")
                    val pkg = app.str("package")
                    if (!PACKAGE.matches(pkg)) throw PolicyPackException("\"$pkg\" is not a package name")
                    lending += pkg
                }
            }

            return PolicyPack(schema, packId, packVersion, rules, lending, warnings)

        } catch (e: PolicyPackException) {
            throw e
        } catch (e: JsonException) {
            throw PolicyPackException("The rule pack is malformed: ${e.message}", e)
        }
    }
}
