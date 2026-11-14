package app.rawline.mobile.ui.settings

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.rawline.mobile.R
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.outbound.config.DnsRule
import app.rawline.mobile.outbound.config.DnsRules
import app.rawline.mobile.ui.MobileAlertDialogBuilder
import app.rawline.mobile.ui.MobilePalette
import app.rawline.mobile.ui.MobileSheetDialog
import app.rawline.mobile.ui.ServerUi

/** The DNS rules of the settings: which sites resolve through which server, through the proxy or directly. */
internal object DnsRulesEditor {
    fun summary(): String {
        val rules = DnsRules.decode(DataStore.dnsRules)
        val on = rules.count { it.enabled }
        return if (rules.isEmpty()) "Свой DNS-сервер для выбранных сайтов" else "Правил: ${rules.size}, включено: $on"
    }

    fun show(context: Context, changed: () -> Unit) {
        val ui = ServerUi(context)
        val dialog = MobileSheetDialog(context)
        val body = ui.column(16)
        fun render() {
            body.removeAllViews()
            body.addView(ui.text("DNS-правила", 19f, bold = true))
            body.addView(ui.text("Сайты из правила разрешаются через его DNS-сервер раньше остальных правил DNS.", 14f, MobilePalette.muted).apply { setPadding(0, ui.dp(6), 0, ui.dp(8)) })
            val rules = DnsRules.decode(DataStore.dnsRules)
            rules.forEachIndexed { index, rule ->
                val title = rule.sites.take(2).joinToString(", ") + if (rule.sites.size > 2) " и ещё ${rule.sites.size - 2}" else ""
                val subtitle = listOfNotNull(rule.server, if (rule.direct) "напрямую" else "через прокси", "выключено".takeIf { !rule.enabled }).joinToString(" · ")
                body.addView(ui.tile(title, subtitle, R.drawable.ic_action_dns) { edit(context, index) { render(); changed() } },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
            }
            body.addView(ui.tile("Добавить правило", null, R.drawable.ic_mobile_add) { edit(context, null) { render(); changed() } },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        }
        render()
        dialog.setContentView(ScrollView(context).apply { addView(body) })
        dialog.show()
    }

    private fun edit(context: Context, index: Int?, saved: () -> Unit) {
        val ui = ServerUi(context)
        val rules = DnsRules.decode(DataStore.dnsRules).toMutableList()
        val rule = index?.let(rules::getOrNull) ?: DnsRule()
        val dialog = MobileSheetDialog(context)
        val body = ui.column(16)
        body.addView(ui.text(if (index == null) "Новое правило" else "Правило", 19f, bold = true))
        val sites = ui.field(body, "Сайты, по одному в строке", rule.sites.joinToString("\n"), multiline = true)
        body.addView(ui.text("domain:example.com с поддоменами, full: только этот адрес, keyword:, regexp:, geosite:category-ai-chat-!cn", 13f, MobilePalette.muted).apply { setPadding(ui.dp(4), ui.dp(4), 0, 0) })
        val server = ui.field(body, "DNS-сервер, например https://dns.example/dns-query", rule.server)
        val direct = ui.switchRow(body, "Напрямую, без прокси", rule.direct)
        val enabled = ui.switchRow(body, "Включено", rule.enabled)
        val error = TextView(context).apply { setTextColor(android.graphics.Color.rgb(232, 120, 112)); textSize = 14f; setPadding(ui.dp(4), ui.dp(8), 0, 0) }
        body.addView(error)
        fun store(list: List<DnsRule>) { DataStore.dnsRules = DnsRules.encode(list); dialog.dismiss(); saved() }
        body.addView(ui.button("Сохранить", primary = true) {
            val next = DnsRule(enabled.isChecked, DnsRules.lines(sites.text.toString()), server.text.toString().trim(), direct.isChecked)
            val problem = next.copy(enabled = true).problem()
            if (problem.isNotEmpty()) { error.text = problem; return@button }
            if (index == null || index !in rules.indices) rules += next else rules[index] = next
            store(rules)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        if (index != null && index in rules.indices) body.addView(ui.button("Удалить", danger = true) {
            MobileAlertDialogBuilder(context).setMessage("Удалить правило?")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Удалить") { _, _ -> rules.removeAt(index); store(rules) }
                .show()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        dialog.setContentView(ScrollView(context).apply { addView(body) })
        dialog.show()
    }
}
