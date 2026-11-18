package dev.clonelab.app.tools.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkExportTest {
    @Test
    fun fileNameIsLabelAndVersion() {
        assertEquals("Signal_7.20.2.apk", ApkNames.fileName("Signal", "7.20.2", "org.thoughtcrime.securesms", "apk"))
        assertEquals("Maps.apks", ApkNames.fileName("Maps", "", "com.google.android.apps.maps", "apks"))
        assertEquals("Maps.apk", ApkNames.fileName("Maps", null, "x", "apk"))
    }

    @Test
    fun unsafeCharactersBecomeUnderscoresAndScriptsSurvive() {
        assertEquals("a_b_c", ApkNames.sanitize("a/b:\\c", 90))
        assertEquals("微信", ApkNames.sanitize("  微信 ", 90))
        assertEquals("Hello World", ApkNames.sanitize("Hello   World!", 90))
        assertEquals("Hello _ World", ApkNames.sanitize("Hello \t World", 90)) // a tab is not kept
        assertEquals("app", ApkNames.fileName("///", null, "...", "apk").substringBefore('.'))
        assertEquals("com.example_1.apk", ApkNames.fileName("★", "1", "com.example", "apk"))
    }

    @Test
    fun sanitizeCutsAtByteLimitWithoutSplittingCharacters() {
        assertEquals("微信", ApkNames.sanitize("微信支付", 7)) // 3 bytes each
        assertTrue(ApkNames.sanitize("x".repeat(500), 90).length == 90)
    }

    @Test
    fun zipEntryNamesAreUniqueIgnoringCase() {
        val names = zipEntryNames(listOf("/a/base.apk", "/a/split_config.en.apk", "/b/Split_Config.EN.apk", "/c/"))
        assertEquals(listOf("base.apk", "split_config.en.apk", "Split_Config.EN_2.apk", "split3.apk"), names)
        assertEquals(listOf("base.apk", "base_2.apk"), zipEntryNames(listOf("/x/base.apk", "/y/base.apk")))
    }

    @Test
    fun writeZipStoresEveryEntryAndReportsBytes() = runTest {
        val one = ByteArray(100_000) { it.toByte() }
        val two = "split".toByteArray()
        val out = ByteArrayOutputStream()
        var reported = 0L
        writeZip(
            listOf(ZipSource("base.apk") { ByteArrayInputStream(one) }, ZipSource("s.apk") { ByteArrayInputStream(two) }),
            out,
        ) { reported += it }
        assertEquals((one.size + two.size).toLong(), reported)

        val read = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                read[entry.name] = zip.readBytes()
            }
        }
        assertEquals(listOf("base.apk", "s.apk"), read.keys.toList())
        assertArrayEquals(one, read["base.apk"])
        assertArrayEquals(two, read["s.apk"])
    }
}
