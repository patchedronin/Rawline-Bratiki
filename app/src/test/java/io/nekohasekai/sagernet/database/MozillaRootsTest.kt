package app.rawline.mobile.database

import org.junit.Assert.assertTrue
import org.junit.Test

class MozillaRootsTest {
    // qWDTT connected on Android 8.0 but every DNS over HTTPS lookup failed on its old system roots; 7 to 13 never get new roots without firmware.
    @Test fun oldAndroidUsesTheCoresOwnRoots() {
        for (sdk in 24..33) assertTrue("API $sdk", sdk < SettingsMapper.MOZILLA_ROOTS_BELOW_SDK)
    }
}
