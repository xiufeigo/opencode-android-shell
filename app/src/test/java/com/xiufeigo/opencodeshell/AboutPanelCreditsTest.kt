package com.xiufeigo.opencodeshell

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-15: the About page's credits / publication / legal tail — upstream
 * `settings/about/about.tsx:36-93` beyond the version/license/description rows
 * the shell already drew: `writtenBy`, `illustratedBy`, `firstPublished`,
 * `firstIllustrated`, `trademark`, `typeset`, `tagline`.
 *
 * The panel is a `@Composable`, so this pins its source and the copy it reads:
 * the credited names are upstream's own lists (`about.tsx:9-31`), the labels
 * come from the generated `settings.about.*` resources (present in all three
 * locales), and the two pieces this shell deliberately does not draw — the
 * animated wordmark and the decorative SVGs — are named in the KDoc as skips.
 */
class AboutPanelCreditsTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun panel() = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt")

    @Test
    fun `the panel draws every upstream credit and publication row`() {
        val source = panel()
        val body = source.substringAfter("fun AboutPanel()").substringBefore("private fun CreditLine(")
        for (key in listOf(
            "settings_about_writtenBy",
            "settings_about_illustratedBy",
            "settings_about_firstPublished",
            "settings_about_firstIllustrated",
            "settings_about_trademark",
            "settings_about_typeset",
            "settings_about_tagline",
            "settings_about_otherContributor_other",
        )) {
            assertTrue("AboutPanel must read R.string.$key", body.contains("R.string.$key"))
        }
        // The conjunction lives in the shared line renderer (`about.tsx:96-121`).
        val creditLine = source.substringAfter("private fun CreditLine(").substringBefore("private val ABOUT_WRITERS")
        assertTrue(creditLine.contains("R.string.settings_about_and"))
        assertTrue(creditLine.contains("withLink(LinkAnnotation.Url(contributorProfile(name)))"))
        assertTrue(creditLine.contains("withLink(LinkAnnotation.Url(tailUrl))"))
        // The rows the shell already had stay.
        for (key in listOf("oc_about_version", "oc_about_license", "oc_about_description", "oc_about_copyright")) {
            assertTrue("AboutPanel must keep R.string.$key", body.contains("R.string.$key"))
        }
    }

    @Test
    fun `the credited names are upstream's lists in upstream order`() {
        val body = panel()
        // `about.tsx:9-25` writers, `:26` illustrators — same names, same order.
        val writers = listOf(
            "thdxr", "adamdotdev", "rekram1-node", "kitlangton", "iamdavidhill", "jayair", "fwang",
            "brendonovich", "nexxeln", "hona", "kommander", "jlongster", "vimtor", "r44vcorp",
            "simonklee", "arvsrn",
        )
        val illustrators = listOf("usrnk1", "ludvigrask_", "arvsrn", "iamdavidhill")
        val writersBlock = body.substringAfter("private val ABOUT_WRITERS = listOf(").substringBefore(")")
        val illustratorsBlock = body.substringAfter("private val ABOUT_ILLUSTRATORS = listOf(").substringBefore(")")
        assertEquals(
            writers,
            Regex("\"([^\"]+)\"").findAll(writersBlock).map { it.groupValues[1] }.toList(),
        )
        assertEquals(
            illustrators,
            Regex("\"([^\"]+)\"").findAll(illustratorsBlock).map { it.groupValues[1] }.toList(),
        )
        // `profile(name)` (`about.tsx:108-112`) — GitHub, with its two exceptions.
        val profile = body.substringAfter("private fun contributorProfile(").substringBefore("\n}")
        assertTrue(profile.contains("\"https://github.com/R44VC0RP\""))
        assertTrue(profile.contains("\"https://x.com/ludvigrask_\""))
        assertTrue(profile.contains("\"https://github.com/\$name\""))
        assertTrue(body.contains("https://github.com/anomalyco/opencode/graphs/contributors"))
    }

    @Test
    fun `the un-drawable desktop art is documented as a skip`() {
        val doc = panel().substringAfter("/**\n * `SettingsAbout`").substringBefore("@Composable")
        assertTrue(doc.contains("AnimatedWordmark"))
        assertTrue(doc.contains("legal.svg"))
        // The live GitHub contributor count is replaced by upstream's own fallback.
        assertTrue(doc.contains("FALLBACK_OTHER_CONTRIBUTORS"))
        assertTrue(panel().contains("private const val OTHER_CONTRIBUTORS = 935"))
    }

    @Test
    fun `every settings about key exists in all three locales`() {
        val keys = listOf(
            "settings_about_writtenBy",
            "settings_about_illustratedBy",
            "settings_about_and",
            "settings_about_firstPublished",
            "settings_about_firstIllustrated",
            "settings_about_trademark",
            "settings_about_typeset",
            "settings_about_tagline",
            "settings_about_otherContributor_other",
        )
        for (relative in listOf(
            "src/main/res/values/strings_oc_i18n.xml",
            "src/main/res/values-zh-rCN/strings_oc_i18n.xml",
            "src/main/res/values-zh-rTW/strings_oc_i18n.xml",
        )) {
            val body = source(relative)
            for (key in keys) assertTrue("$relative is missing $key", body.contains("name=\"$key\""))
        }
    }
}
