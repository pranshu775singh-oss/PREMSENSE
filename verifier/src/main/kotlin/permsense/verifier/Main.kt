package permsense.verifier

import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import kotlin.system.exitProcess

private const val USAGE = """
permsense-verify: check a PermSense evidence report without trusting PermSense.

Usage:
  permsense-verify <report.json> [--root <roots.pem>]... [--no-bundled-roots] [--require-hardware]
  permsense-verify intended-log <intended_log.jsonl> [same options]

  --root <file>         Extra trusted attestation root certificates (PEM). Repeatable.
  --no-bundled-roots    Do not trust the roots shipped inside this tool.
  --require-hardware    Exit with status 3 unless the signing key is attested to secure hardware.

  intended-log         Check the probe app's signed log of the accesses it meant to make.

Exit status: 0 verified, 2 rejected, 3 verified but not hardware-attested (with --require-hardware),
             64 bad usage.
"""

fun main(args: Array<String>) {
    exitProcess(run(args.toList()))
}

internal fun run(args: List<String>): Int {

    if (args.firstOrNull() == "intended-log") return runIntendedLog(args.drop(1))

    var file: String? = null
    val rootFiles = ArrayList<String>()
    var bundled = true
    var requireHardware = false

    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--root" -> { rootFiles += args.getOrNull(++i) ?: return usage("--root needs a file") }
            "--no-bundled-roots" -> bundled = false
            "--require-hardware" -> requireHardware = true
            "-h", "--help" -> { println(USAGE.trimIndent()); return 0 }
            else -> if (a.startsWith("-")) return usage("unknown option $a") else if (file == null) file = a else return usage("one report at a time")
        }
        i++
    }

    val path = file ?: return usage("no report given")
    val bytes = try {
        File(path).readBytes()
    } catch (e: Exception) {
        System.err.println("Cannot read $path: ${e.message}")
        return 64
    }

    val roots = ArrayList<X509Certificate>()
    if (bundled) roots += TrustedRoots.bundled()
    for (r in rootFiles) {
        try {
            roots += TrustedRoots.parsePem(File(r).readText())
        } catch (e: Exception) {
            System.err.println("Cannot read roots from $r: ${e.message}")
            return 64
        }
    }

    val report = EvidenceVerifier.verify(bytes, VerifierOptions(trustedRoots = roots))
    print(render(report))

    return when {
        !report.accepted -> 2
        requireHardware && report.level != Level.HARDWARE_ATTESTED -> 3
        else -> 0
    }
}

/** `permsense-verify intended-log <file>`: the probe app's signed log, checked the same way. */
internal fun runIntendedLog(args: List<String>): Int {

    var file: String? = null
    val rootFiles = ArrayList<String>()
    var bundled = true
    var requireHardware = false

    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--root" -> { rootFiles += args.getOrNull(++i) ?: return usage("--root needs a file") }
            "--no-bundled-roots" -> bundled = false
            "--require-hardware" -> requireHardware = true
            "-h", "--help" -> { println(USAGE.trimIndent()); return 0 }
            else -> if (a.startsWith("-")) return usage("unknown option $a") else if (file == null) file = a else return usage("one log at a time")
        }
        i++
    }

    val path = file ?: return usage("no log given")
    val bytes = try {
        File(path).readBytes()
    } catch (e: Exception) {
        System.err.println("Cannot read $path: ${e.message}")
        return 64
    }

    val roots = ArrayList<X509Certificate>()
    if (bundled) roots += TrustedRoots.bundled()
    for (r in rootFiles) {
        try {
            roots += TrustedRoots.parsePem(File(r).readText())
        } catch (e: Exception) {
            System.err.println("Cannot read roots from $r: ${e.message}")
            return 64
        }
    }

    val report = IntendedLogVerifier.verify(bytes, VerifierOptions(trustedRoots = roots))
    print(renderIntended(report))

    return when {
        !report.accepted -> 2
        requireHardware && report.level != Level.HARDWARE_ATTESTED -> 3
        else -> 0
    }
}

internal fun renderIntended(report: IntendedLogReport): String {

    val sb = StringBuilder()

    for (c in report.checks) {
        sb.append(if (c.passed) "  [ok]   " else "  [FAIL] ").append(c.name).append(": ").append(c.detail).append('\n')
    }

    sb.append('\n')
    when (report.level) {
        Level.REJECTED -> sb.append("RESULT: REJECTED. Do not rely on this log.\n")
        Level.INTEGRITY_VERIFIED -> sb.append(
            "RESULT: INTEGRITY VERIFIED. This is the log the probe wrote; nothing in it has been altered.\n" +
                    "        It is NOT proven to come from a genuine device (no hardware attestation).\n"
        )
        Level.HARDWARE_ATTESTED -> sb.append(
            "RESULT: VERIFIED AND HARDWARE-ATTESTED. Nothing has been altered, and the signing key\n" +
                    "        lives in secure hardware on a genuine device.\n"
        )
    }

    if (report.accepted) {
        val byOp = report.accesses.groupingBy { it.op }.eachCount().toSortedMap()
        val byStratum = report.accesses.groupingBy { it.expectedStratum }.eachCount().toSortedMap()
        sb.append('\n')
        sb.append("  Intended accesses: ").append(report.accesses.size).append('\n')
        sb.append("  By operation:      ").append(byOp.entries.joinToString(", ") { "${it.key} x${it.value}" }).append('\n')
        sb.append("  By stratum:        ").append(byStratum.entries.joinToString(", ") { "${it.key} x${it.value}" }).append('\n')
        report.securityLevel?.let { sb.append("  Signing key:       ").append(it).append('\n') }
    }

    if (report.notes.isNotEmpty()) {
        sb.append("\nNotes:\n")
        report.notes.forEach { sb.append("  - ").append(it).append('\n') }
    }

    sb.append("\nThis shows the log is the one the probe wrote. It does not show the accesses really happened;\n")
    sb.append("compare it with PermSense's own ledger for that.\n")

    return sb.toString()
}

private fun usage(message: String): Int {
    System.err.println(message)
    System.err.println(USAGE.trimIndent())
    return 64
}

internal fun render(report: Report): String {

    val sb = StringBuilder()

    for (c in report.checks) {
        sb.append(if (c.passed) "  [ok]   " else "  [FAIL] ").append(c.name).append(": ").append(c.detail).append('\n')
    }

    sb.append('\n')
    when (report.level) {
        Level.REJECTED -> sb.append("RESULT: REJECTED. Do not rely on this report.\n")
        Level.INTEGRITY_VERIFIED -> sb.append(
            "RESULT: INTEGRITY VERIFIED. Nothing in this file has been altered since it was written.\n" +
                    "        It is NOT proven to come from a genuine device (no hardware attestation).\n"
        )
        Level.HARDWARE_ATTESTED -> sb.append(
            "RESULT: VERIFIED AND HARDWARE-ATTESTED. Nothing has been altered, and the signing key\n" +
                    "        lives in secure hardware on a genuine device.\n"
        )
    }

    report.summary?.let { s ->
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'").apply { timeZone = TimeZone.getTimeZone("UTC") }
        sb.append('\n')
        sb.append("  Report made:   ").append(fmt.format(Date(s.createdAtMs))).append('\n')
        if (s.firstRowMs != null && s.lastRowMs != null) {
            sb.append("  Covers:        ").append(fmt.format(Date(s.firstRowMs))).append(" to ").append(fmt.format(Date(s.lastRowMs))).append('\n')
        }
        sb.append("  Ledger rows:   ").append(s.rows).append(" (").append(s.chainStart).append(")\n")
        sb.append("  Checkpoints:   ").append(s.checkpoints).append(", ").append(s.signedCheckpoints).append(" signed\n")
        sb.append("  Findings:      ").append(s.findings).append('\n')
        s.securityLevel?.let { sb.append("  Signing key:   ").append(it).append('\n') }
        s.rulePackVersion?.let { sb.append("  Rule pack:     ").append(it).append('\n') }
    }

    if (report.notes.isNotEmpty()) {
        sb.append("\nNotes:\n")
        report.notes.forEach { sb.append("  - ").append(it).append('\n') }
    }

    sb.append(
        "\nWhat this does and does not show: it shows the report has not been altered since PermSense\n" +
                "wrote it. It does not show that the operating system's own data was true, and it cannot see\n" +
                "a phone that was already compromised when the data was recorded. It makes no claim about\n" +
                "admissibility in any legal proceeding.\n"
    )

    return sb.toString()
}

/** Attestation roots. Only certificates the user can see and has chosen are ever trusted. */
object TrustedRoots {

    private const val RESOURCE = "/trusted_roots.pem"

    fun bundled(): List<X509Certificate> {
        val stream = TrustedRoots::class.java.getResourceAsStream(RESOURCE) ?: return emptyList()
        return parsePem(stream.readBytes().toString(Charsets.UTF_8))
    }

    fun parsePem(text: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val blocks = Regex("-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .map { it.value }
            .toList()
        return blocks.map { factory.generateCertificate(ByteArrayInputStream(it.toByteArray(Charsets.US_ASCII))) as X509Certificate }
    }
}
