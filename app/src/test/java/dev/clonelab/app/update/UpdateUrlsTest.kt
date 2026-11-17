package dev.clonelab.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUrlsTest {

    @Test
    fun acceptsGithubHosts() {
        assertTrue(UpdateUrls.isAllowedDownload("https://github.com/Howard-Starfield/Multi-Apk/releases/download/v0.2.0/CloneLab.apk"))
        assertTrue(UpdateUrls.isAllowedDownload("https://objects.githubusercontent.com/github-production-release-asset/1?X=1"))
        assertTrue(UpdateUrls.isAllowedDownload("https://release-assets.githubusercontent.com/a/b"))
        assertTrue(UpdateUrls.isAllowedDownload("https://GitHub.com/x"))
        assertTrue(UpdateUrls.isAllowedDownload("https://github.com:443/x"))
    }

    @Test
    fun rejectsOtherSchemes() {
        assertFalse(UpdateUrls.isAllowedDownload("http://github.com/x.apk"))
        assertFalse(UpdateUrls.isAllowedDownload("ftp://github.com/x.apk"))
        assertFalse(UpdateUrls.isAllowedDownload("file:///sdcard/x.apk"))
        assertFalse(UpdateUrls.isAllowedDownload("//github.com/x"))
    }

    @Test
    fun rejectsLookalikeHosts() {
        assertFalse(UpdateUrls.isAllowedDownload("https://evilgithub.com/x"))
        assertFalse(UpdateUrls.isAllowedDownload("https://github.com.evil.example/x"))
        assertFalse(UpdateUrls.isAllowedDownload("https://githubusercontent.com/x"))
        assertFalse(UpdateUrls.isAllowedDownload("https://evilgithubusercontent.com/x"))
        assertFalse(UpdateUrls.isAllowedDownload("https://github.com@evil.example/x"))
        assertFalse(UpdateUrls.isAllowedDownload("https://evil.example/github.com"))
        assertFalse(UpdateUrls.isAllowedDownload("https://github.com:8443/x"))
    }

    @Test
    fun rejectsGarbage() {
        assertFalse(UpdateUrls.isAllowedDownload(null))
        assertFalse(UpdateUrls.isAllowedDownload(""))
        assertFalse(UpdateUrls.isAllowedDownload("not a url"))
        assertFalse(UpdateUrls.isAllowedDownload("https://"))
    }
}
