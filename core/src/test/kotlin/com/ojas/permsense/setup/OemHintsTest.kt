package com.ojas.permsense.setup

import org.junit.Assert.assertEquals
import org.junit.Test

class OemHintsTest {

    @Test
    fun eachMakerMapsToItsOwnSteps() {
        assertEquals(Oem.XIAOMI, Oem.fromManufacturer("Xiaomi"))
        assertEquals(Oem.XIAOMI, Oem.fromManufacturer("Xiaomi", "Redmi"))
        assertEquals(Oem.XIAOMI, Oem.fromManufacturer("Xiaomi", "POCO"))
        assertEquals(Oem.OPPO, Oem.fromManufacturer("OPPO"))
        assertEquals(Oem.OPPO, Oem.fromManufacturer("realme"))
        assertEquals(Oem.OPPO, Oem.fromManufacturer("OnePlus"))
        assertEquals(Oem.VIVO, Oem.fromManufacturer("vivo"))
        assertEquals(Oem.VIVO, Oem.fromManufacturer("iQOO"))
        assertEquals(Oem.SAMSUNG, Oem.fromManufacturer("samsung"))
        assertEquals(Oem.HUAWEI, Oem.fromManufacturer("HUAWEI"))
        assertEquals(Oem.HUAWEI, Oem.fromManufacturer("HONOR"))
    }

    @Test
    fun theBrandIsUsedWhenTheManufacturerIsGeneric() {
        assertEquals(Oem.XIAOMI, Oem.fromManufacturer("unknown", "redmi"))
    }

    @Test
    fun aPixelAndAnythingUnknownFollowStockAndroid() {
        assertEquals(Oem.OTHER, Oem.fromManufacturer("Google"))
        assertEquals(Oem.OTHER, Oem.fromManufacturer(null))
        assertEquals(Oem.OTHER, Oem.fromManufacturer(""))
        assertEquals(Oem.OTHER, Oem.fromManufacturer("Nothing"))
    }

    @Test
    fun caseAndSpacesDoNotMatter() {
        assertEquals(Oem.SAMSUNG, Oem.fromManufacturer("  SAMSUNG "))
        assertEquals(Oem.OPPO, Oem.fromManufacturer("OnePlus Technology"))
    }
}
