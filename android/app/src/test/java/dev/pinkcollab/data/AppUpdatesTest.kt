package dev.pinkcollab.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {
    @Test fun release_tag_uses_android_version_code_formula() {
        assertEquals(2_000, versionCodeFromTag("v0.2.0"))
        assertEquals(2_010_009, versionCodeFromTag("v2.10.9"))
        assertEquals(2_100_000_000, versionCodeFromTag("v2100.0.0"))
        for (invalid in listOf("0.2.0", "v1.02.3", "v1.2", "v1.2.3-rc.1", "v1.1000.0",
            "v1.2.1000", "v2100.0.1", "v999999999999999999999.0.0")) {
            assertNull(invalid, versionCodeFromTag(invalid))
        }
    }

    @Test fun newer_release_compares_codes_not_text() {
        val release = AppRelease("v0.10.0", 10_000, "", "https://github.com/release")
        assertTrue(isNewerRelease(release, 9_999))
        assertFalse(isNewerRelease(release, 10_000))
        assertFalse(isNewerRelease(release, 10_001))
    }

    @Test fun release_selects_named_apk_and_includes_notes() {
        val release = releaseJson().put("assets", JSONArray()
            .put(asset("gateway.tar.gz", "https://github.com/gateway"))
            .put(asset("pinkcollab-android.apk", "https://github.com/pinkcollab.apk")))
        assertEquals(AppRelease("v0.3.0", 3_000, "Changes and fixes",
            "https://github.com/pinkcollab.apk"), parseAppRelease(release.toString()))
    }

    @Test fun release_uses_page_when_apk_missing_or_url_unsafe() {
        val release = releaseJson().put("assets", JSONArray()
            .put(asset("pinkcollab-android.apk", "http://example.com/pinkcollab.apk")))
        assertEquals("https://github.com/3xian/PinkCollab/releases/tag/v0.3.0",
            parseAppRelease(release.toString())?.updateUrl)
        assertNull(parseAppRelease(release.toString())?.apkUrl)
        release.put("assets", JSONArray())
        assertEquals("https://github.com/3xian/PinkCollab/releases/tag/v0.3.0",
            parseAppRelease(release.toString())?.updateUrl)
        release.put("assets", JSONArray()
            .put(asset("pinkcollab-android.apk", "http://example.com/invalid"))
            .put(asset("pinkcollab-android.apk", "https://github.com/valid.apk")))
        assertEquals("https://github.com/valid.apk", parseAppRelease(release.toString())?.updateUrl)
    }

    @Test fun malformed_release_does_not_produce_an_update() {
        assertNull(parseAppRelease("not json"))
        assertNull(parseAppRelease(releaseJson().put("tag_name", "oops").toString()))
        assertNull(parseAppRelease(releaseJson().put("html_url", "javascript:alert(1)").toString()))
        assertEquals("", parseAppRelease(releaseJson().put("body", JSONObject.NULL).toString())?.notes)
        assertEquals("https://github.com/3xian/PinkCollab/releases/tag/v0.3.0",
            parseAppRelease(releaseJson().put("assets", "unexpected").toString())?.updateUrl)
    }

    private fun releaseJson() = JSONObject()
        .put("tag_name", "v0.3.0")
        .put("body", "Changes and fixes")
        .put("html_url", "https://github.com/3xian/PinkCollab/releases/tag/v0.3.0")

    private fun asset(name: String, url: String) = JSONObject()
        .put("name", name)
        .put("browser_download_url", url)
}
