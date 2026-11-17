package dev.clonelab.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {

    /** Trimmed, hand-written sample in the shape of GET /repos/{owner}/{repo}/releases/latest. */
    private fun sample(
        tag: String = "v0.2.0",
        draft: Boolean = false,
        prerelease: Boolean = false,
        body: String = "\"- Multi-select on the clone page\\r\\n- Settings button\"",
        assets: String = """
            [
              {"name": "checksums.txt", "size": 120, "browser_download_url": "https://github.com/Howard-Starfield/Multi-Apk/releases/download/v0.2.0/checksums.txt"},
              {"name": "CloneLab-0.2.0.apk", "size": 31457280, "content_type": "application/vnd.android.package-archive",
               "browser_download_url": "https://github.com/Howard-Starfield/Multi-Apk/releases/download/v0.2.0/CloneLab-0.2.0.apk"},
              {"name": "other.apk", "size": 5, "browser_download_url": "https://github.com/x/other.apk"}
            ]
        """,
    ) = """
        {
          "url": "https://api.github.com/repos/Howard-Starfield/Multi-Apk/releases/1",
          "html_url": "https://github.com/Howard-Starfield/Multi-Apk/releases/tag/$tag",
          "id": 1,
          "tag_name": "$tag",
          "name": "CloneLab 0.2.0",
          "draft": $draft,
          "prerelease": $prerelease,
          "body": $body,
          "assets": $assets
        }
    """

    private fun usable(json: String) = (ReleaseParser.parse(json) as ParsedRelease.Usable).info

    @Test
    fun readsTheFieldsAndTheFirstApkAsset() {
        val info = usable(sample())
        assertEquals("v0.2.0", info.tag)
        assertEquals("0.2.0", info.version)
        assertEquals("CloneLab 0.2.0", info.title)
        assertEquals("https://github.com/Howard-Starfield/Multi-Apk/releases/tag/v0.2.0", info.pageUrl)
        assertEquals("CloneLab-0.2.0.apk", info.apkName)
        assertEquals(31457280L, info.apkSize)
        assertEquals("https://github.com/Howard-Starfield/Multi-Apk/releases/download/v0.2.0/CloneLab-0.2.0.apk", info.apkUrl)
        assertEquals("CloneLab-0.2.0.apk", info.fileName)
        assertTrue(info.notes.contains("Multi-select"))
    }

    @Test
    fun draftAndPrereleaseAreIgnored() {
        assertEquals(ParsedRelease.Ignored, ReleaseParser.parse(sample(draft = true)))
        assertEquals(ParsedRelease.Ignored, ReleaseParser.parse(sample(prerelease = true)))
    }

    @Test
    fun releaseWithoutApkAssetIsIgnored() {
        val none = """[{"name": "notes.txt", "size": 1, "browser_download_url": "https://github.com/x/notes.txt"}]"""
        assertEquals(ParsedRelease.Ignored, ReleaseParser.parse(sample(assets = none)))
        assertEquals(ParsedRelease.Ignored, ReleaseParser.parse(sample(assets = "[]")))
    }

    @Test
    fun nullBodyIsEmptyNotTheWordNull() {
        assertEquals("", usable(sample(body = "null")).notes)
    }

    @Test
    fun malformedIsReported() {
        assertEquals(ParsedRelease.Malformed, ReleaseParser.parse("<html>rate limited</html>"))
        assertEquals(ParsedRelease.Malformed, ReleaseParser.parse("""{"message": "Not Found"}"""))
        assertEquals(ParsedRelease.Malformed, ReleaseParser.parse(""))
    }

    @Test
    fun checkerCompares() {
        val json = sample()
        assertTrue(UpdateChecker.interpret(json, "0.1.0") is CheckResult.Available)
        assertEquals(CheckResult.UpToDate("0.2.0"), UpdateChecker.interpret(json, "0.2.0"))
        assertEquals(CheckResult.UpToDate("0.3.0"), UpdateChecker.interpret(json, "0.3.0"))
        assertEquals(CheckResult.Failed, UpdateChecker.interpret("nope", "0.1.0"))
        assertEquals(CheckResult.UpToDate("0.1.0"), UpdateChecker.interpret(sample(prerelease = true), "0.1.0"))
    }

    @Test
    fun malformedTagIsNotNewer() {
        assertEquals(CheckResult.UpToDate("0.1.0"), UpdateChecker.interpret(sample(tag = "nightly"), "0.1.0"))
    }
}
