package app.rawline.mobile.database

import androidx.sqlite.db.SupportSQLiteDatabase
import app.rawline.mobile.Key
import app.rawline.mobile.SagerNet

/** Seed only Room's onCreate transaction, never an upgrade, launch or backup restore. */
internal object BratikiDefaults {
    fun seed(db: SupportSQLiteDatabase) {
        val values = mapOf(
            Key.SERVICE_MODE to Key.MODE_VPN,
            Key.PROXY_APPS to "true",
            Key.BYPASS_MODE to "false",
            // Keep package names, not device-specific UIDs; also works if installed later.
            Key.INDIVIDUAL to ("org.telegram.messenger\norg.telegram.messenger.web\ncom.google.android.youtube" +
                if (SagerNet.isTv) "\ncom.google.android.youtube.tv" else ""),
            Key.ALLOW_BYPASS to "false",
            "vpn_mtu" to "1200",
            "block_unowned_tun" to "true",
            "block_quic" to "true",
            "inbound_address" to "127.0.0.1",
            "lan_sharing_enabled" to "false",
            "remote_dns" to "https://1.1.1.1/dns-query",
            "direct_dns" to "localhost",
            "dns_final_out" to "remote",
            "enable_dns_routing" to "true",
            "fakedns" to "false",
            "log_level" to "warn",
            "xray_log_level" to "warning",
            "enable_stats" to "false",
            "exit_country_lookup" to "false",
            "core_box_clash_api" to "-9090",
            "core_box_api_port" to "-9091",
            // Existing one-time upstream migrations must not undo the new-install preset.
            "block_unowned_tun_112_migrated" to "true",
            "diagnosticLogsConfigured" to "true",
        )
        for ((key, value) in values) {
            db.execSQL("INSERT INTO `settings` (`key`, `value`) VALUES (?, ?)", arrayOf(key, value))
        }
    }
}
