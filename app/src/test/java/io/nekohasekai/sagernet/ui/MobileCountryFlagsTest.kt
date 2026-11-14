package app.rawline.mobile.ui

import org.junit.Assert.*
import org.junit.Test

class MobileCountryFlagsTest {
    private val de = "🇩🇪"
    private val fi = "🇫🇮"
    private val rainbow = "🏳️‍🌈"

    @Test fun flagIsTakenFromAnyPlaceInTheName() {
        assertEquals(de, MobileCountryFlags.first("$de Germany")?.value)
        assertEquals(de, MobileCountryFlags.first("Server $de Germany")?.value)
        assertEquals(de, MobileCountryFlags.first("Server $de$fi")?.value)
        assertEquals(fi, MobileCountryFlags.first("Helsinki | $fi | $de")?.value)
        assertNull(MobileCountryFlags.first("Auto Selector"))
    }

    @Test fun aJoinedFlagDoesNotSwallowTheNextWord() {
        assertEquals(rainbow, MobileCountryFlags.first("Pride ${rainbow}Server")?.value)
    }
}
