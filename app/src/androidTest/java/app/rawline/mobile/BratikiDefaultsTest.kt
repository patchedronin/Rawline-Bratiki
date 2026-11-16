package app.rawline.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.rawline.mobile.database.SagerDatabase
import app.rawline.mobile.database.preference.SettingEntry
import app.rawline.mobile.database.preference.SettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BratikiDefaultsTest {
    @Test fun freshDatabaseIsSelectiveAndReopenPreservesUserChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "bratiki-defaults-regression.db"
        context.deleteDatabase(name)
        try {
            var db = SagerDatabase.openProfileDatabase(context, name)
            try {
                val store = SettingsStore({ db })
                assertTrue(store.getBoolean(Key.PROXY_APPS, false))
                assertFalse(store.getBoolean(Key.BYPASS_MODE, true))
                val expected = setOf("org.telegram.messenger", "org.telegram.messenger.web", "com.google.android.youtube") +
                    if (SagerNet.isTv) setOf("com.google.android.youtube.tv") else emptySet()
                assertEquals(expected,
                    store.getString(Key.INDIVIDUAL).orEmpty().lines().toSet())
                assertFalse(store.getBoolean(Key.ALLOW_BYPASS, true))
                assertTrue(store.getBoolean("block_unowned_tun", false))
                assertFalse(store.contains("inbound_pass"))
                assertFalse(store.contains("core_box_api_secret"))
                db.settingsDao().put(SettingEntry(Key.INDIVIDUAL, "user.browser"))
                db.settingsDao().put(SettingEntry(Key.PROXY_APPS, "false"))
                db.settingsDao().delete("vpn_mtu")
            } finally { db.close() }
            db = SagerDatabase.openProfileDatabase(context, name)
            try {
                val store = SettingsStore({ db })
                assertEquals("user.browser", store.getString(Key.INDIVIDUAL))
                assertFalse(store.getBoolean(Key.PROXY_APPS, true))
                assertFalse(store.contains("vpn_mtu"))
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
