package com.ojas.permsense.setup

import java.util.Locale

/**
 * Phone makers whose software needs an extra step before Shizuku (or wireless debugging) works.
 * Written down by the Shizuku project; the guided setup shows the one that matches the phone, so a
 * person is not left guessing why a step "did nothing".
 */
enum class Oem {
    /** Xiaomi, Redmi, POCO: MIUI and HyperOS. USB debugging has a separate switch under Security. */
    XIAOMI,

    /** OPPO, Realme, OnePlus: ColorOS and OxygenOS. "Permission monitoring" must be off. */
    OPPO,

    /** vivo and iQOO: Funtouch and OriginOS. */
    VIVO,

    /** Samsung: One UI. Auto Blocker can switch wireless debugging off. */
    SAMSUNG,

    /** Huawei and Honor: EMUI and MagicOS. */
    HUAWEI,

    /** Pixel and anything else that follows stock Android. */
    OTHER;

    companion object {

        fun fromManufacturer(manufacturer: String?, brand: String? = null): Oem {
            val names = listOfNotNull(manufacturer, brand).map { it.lowercase(Locale.ROOT).trim() }
            return when {
                names.any { it.startsWith("xiaomi") || it.startsWith("redmi") || it.startsWith("poco") } -> XIAOMI
                names.any { it.startsWith("oppo") || it.startsWith("realme") || it.startsWith("oneplus") } -> OPPO
                names.any { it.startsWith("vivo") || it.startsWith("iqoo") } -> VIVO
                names.any { it.startsWith("samsung") } -> SAMSUNG
                names.any { it.startsWith("huawei") || it.startsWith("honor") } -> HUAWEI
                else -> OTHER
            }
        }
    }
}
