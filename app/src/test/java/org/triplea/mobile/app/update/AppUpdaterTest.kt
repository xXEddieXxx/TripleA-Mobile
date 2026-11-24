package org.triplea.mobile.app.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppUpdaterTest {
    @Test
    fun releaseVersionsCompareAsNumbers() {
        assertTrue(AppUpdater.isNewer("0.4.10", "0.4.9"))
        assertTrue(AppUpdater.isNewer("1.0.0", "0.9.9"))
        assertFalse(AppUpdater.isNewer("0.4.2", "0.4.2"))
        assertFalse(AppUpdater.isNewer("0.4.1", "0.4.2"))
    }

    @Test
    fun localBuildsAndOtherChannelsAreNeverOffered() {
        assertFalse(AppUpdater.isNewer("0.4.2", "0.0.0-local"))
        assertFalse(AppUpdater.isNewer("dev-20261113-f12db3d", "0.4.2"))
        assertFalse(AppUpdater.isNewer("0.4.2", "dev-20261113-f12db3d"))
        assertNull(AppUpdater.parseVersion("0.0.0-local"))
        assertEquals(listOf(0, 4, 2), AppUpdater.parseVersion("v0.4.2"))
    }

    @Test
    fun devBuildsCompareByDay() {
        assertTrue(AppUpdater.isNewer("dev-20261113-f12db3d", "dev-20261109-6314ba5"))
        // same day: the other build may be the older one, so it waits for the next day
        assertFalse(AppUpdater.isNewer("dev-20261113-f12db3d", "dev-20261113-aaaaaaa"))
        assertFalse(AppUpdater.isNewer("dev-20261113-f12db3d", "dev-20261113-f12db3d"))
        assertFalse(AppUpdater.isNewer("dev-20261109-6314ba5", "dev-20261113-f12db3d"))
    }

    private fun asset(name: String, digest: String?, url: String = "https://github.com/x/$name", size: Long = 100) =
        """{"name":"$name","size":$size,"browser_download_url":"$url"${digest?.let { ""","digest":"sha256:$it"""" } ?: ""}}"""

    private fun release(tag: String, vararg assets: String) = JSONObject("""{"tag_name":"$tag","assets":[${assets.joinToString(",")}]}""")

    @Test
    fun officialReleaseTakesTheVersionFromTheTag() {
        val parsed = AppUpdater.parseRelease(release("v0.4.2", asset("triplea-mobile-0.4.2.apk", "aa"), asset("triplea-mobile.apk", "aa")), dev = false)
        assertEquals(AppRelease("0.4.2", "https://github.com/x/triplea-mobile.apk", 100, "aa"), parsed)
    }

    @Test
    fun devReleaseTakesTheVersionFromTheDatedAssetWithTheSameChecksum() {
        val json = release(
            "dev",
            asset("triplea-mobile-dev-20261109-6314ba5.apk", "old"),
            asset("triplea-mobile-dev-20261113-f12db3d.apk", "new"),
            asset("triplea-mobile-dev.apk", "new"),
        )
        val parsed = AppUpdater.parseRelease(json, dev = true)
        assertEquals("dev-20261113-f12db3d", parsed?.version)
        // the dated file: a later push replaces triplea-mobile-dev.apk, not this one
        assertEquals("https://github.com/x/triplea-mobile-dev-20261113-f12db3d.apk", parsed?.url)
    }

    @Test
    fun unsafeOrIncompleteAssetsAreNotOffered() {
        // no checksum, also an explicit null
        assertNull(AppUpdater.parseRelease(release("v0.4.2", asset("triplea-mobile.apk", null)), dev = false))
        assertNull(AppUpdater.parseRelease(JSONObject("""{"tag_name":"v0.4.2","assets":[{"name":"triplea-mobile.apk","size":1,"browser_download_url":"https://x/a","digest":null}]}"""), dev = false))
        // not https
        assertNull(AppUpdater.parseRelease(release("v0.4.2", asset("triplea-mobile.apk", "aa", url = "http://x/a.apk")), dev = false))
        // no size
        assertNull(AppUpdater.parseRelease(release("v0.4.2", asset("triplea-mobile.apk", "aa", size = 0)), dev = false))
        // dev APK without its dated twin
        assertNull(AppUpdater.parseRelease(release("dev", asset("triplea-mobile-dev.apk", "aa")), dev = true))
    }

    @Test
    fun compareRefsUseTagsOrDevCommits() {
        assertEquals("v0.4.0" to "v0.4.2", AppUpdater.compareRefs("0.4.0", "0.4.2"))
        assertEquals("6314ba5" to "f12db3d", AppUpdater.compareRefs("dev-20261109-6314ba5", "dev-20261113-f12db3d"))
        assertNull(AppUpdater.compareRefs("0.0.0-local", "0.4.2"))
    }

    @Test
    fun changesAreCommitTitlesForPlayersNewestFirst() {
        val messages = listOf(
            "fix: show die values above 6 as numbers (d12 maps)",
            "ci: dev builds come from the dev branch",
            "Merge branch 'main' into develop",
            "feat: add play by Forum functionality\n\nlong description",
            "fix(ci): sign dev builds with the stored key",
            "docs: update README",
            "Battle screen: show every die",
            // a word and a colon is not a commit type
            "Map browser: search by name",
        )
        val commits = JSONArray()
        messages.forEach { commits.put(JSONObject().put("commit", JSONObject().put("message", it))) }
        val json = JSONObject().put("commits", commits)
        assertEquals(
            listOf(
                Change(Change.Kind.CHANGED, "Map browser: search by name"),
                Change(Change.Kind.CHANGED, "Battle screen: show every die"),
                Change(Change.Kind.NEW, "Add play by Forum functionality"),
                Change(Change.Kind.FIXED, "Show die values above 6 as numbers (d12 maps)"),
            ),
            AppUpdater.parseChanges(json),
        )
    }
}
