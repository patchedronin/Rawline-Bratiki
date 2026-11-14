package app.rawline.mobile.outbound.config

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DnsRulesStringsTest {
    // Every language the app offers (language_values) shows the DNS rules in its own words.
    @Test fun everyOfferedLanguageHasEveryString() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        fun keys(folder: String) = Regex("""name="(dns_rules_[a-z_]+)"""").findAll(File(res, "$folder/dns_rules_strings.xml").readText())
            .map { it.groupValues[1] }.toSet()
        val english = keys("values")
        for (folder in listOf("values-ru", "values-ja", "values-ko", "values-fa", "values-zh-rCN", "values-zh-rTW"))
            assertEquals(folder, english, keys(folder))
    }
}
