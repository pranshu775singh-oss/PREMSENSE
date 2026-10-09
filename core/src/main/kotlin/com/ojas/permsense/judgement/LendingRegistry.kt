package com.ojas.permsense.judgement

/**
 * Which installed apps count as digital-lending apps for the RBI rules.
 *
 * Android cannot say: ApplicationInfo.category is developer-declared and often undefined, and
 * looking an app up on Play would need the network, which PermSense deliberately never has.
 * So the answer is a list in the rule pack plus whatever the user tags by hand.
 *
 * The bundled pack holds only the team's own labelled test app. Real lending-app package names
 * must be added from a reviewed source, never guessed.
 */
object LendingRegistry {

    /** The QuickCash probe app used in the demo. Clearly a test app. */
    const val PROBE_QUICKCASH = "com.ojas.quickcash"

    fun packages(
        userTagged: Set<String>,
        pack: PolicyPack = PolicyPackLoader.loadBundled(),
    ): Set<String> = pack.lendingApps + userTagged
}
