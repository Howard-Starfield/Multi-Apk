package dev.clonelab.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Guards values/strings.xml against values-zh/strings.xml: same keys, same format placeholders. */
class StringsConsistencyTest {

    /** Key ("string:x", "plurals:x", "array:x") to the placeholders used anywhere in it. */
    private class Entry(val placeholders: Set<String>, val arraySize: Int = 0)

    private fun resFile(dir: String): File {
        // Gradle runs unit tests with the module directory as working directory.
        val file = File("src/main/res/$dir/strings.xml")
        assertTrue("Missing ${file.absolutePath}", file.isFile)
        return file
    }

    private fun placeholders(text: String): Set<String> =
        PLACEHOLDER.findAll(text).map { it.value }.filter { it != "%%" }.toSet()

    private fun parse(file: File, includeUntranslatable: Boolean): Map<String, Entry> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val result = linkedMapOf<String, Entry>()
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            if (!includeUntranslatable && node.getAttribute("translatable") == "false") continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> result["string:$name"] = Entry(placeholders(node.textContent))
                "plurals", "string-array" -> {
                    val items = node.getElementsByTagName("item")
                    val all = (0 until items.length).flatMap { placeholders(items.item(it).textContent) }.toSet()
                    val kind = if (node.tagName == "plurals") "plurals" else "array"
                    result["$kind:$name"] = Entry(all, if (kind == "array") items.length else 0)
                }
            }
        }
        return result
    }

    private val english get() = parse(resFile("values"), includeUntranslatable = false)
    private val chinese get() = parse(resFile("values-zh"), includeUntranslatable = true)

    @Test
    fun everyDefaultKeyIsTranslated() {
        val missing = english.keys - chinese.keys
        assertTrue("Missing from values-zh: $missing", missing.isEmpty())
    }

    @Test
    fun chineseHasNoExtraKeys() {
        val extra = chinese.keys - english.keys
        assertTrue("Only in values-zh: $extra", extra.isEmpty())
    }

    @Test
    fun placeholdersMatch() {
        val en = english
        val zh = chinese
        val mismatches = en.keys.intersect(zh.keys).filter { en.getValue(it).placeholders != zh.getValue(it).placeholders }
            .map { "$it: ${en.getValue(it).placeholders} vs ${zh.getValue(it).placeholders}" }
        assertTrue("Placeholder mismatch: $mismatches", mismatches.isEmpty())
    }

    @Test
    fun stringArraysKeepTheirLength() {
        val en = english
        val zh = chinese
        for (key in en.keys.filter { it.startsWith("array:") }.filter { it in zh }) {
            assertEquals(key, en.getValue(key).arraySize, zh.getValue(key).arraySize)
        }
    }

    @Test
    fun appNameIsNotTranslated() {
        val zh = parse(resFile("values-zh"), true)
        assertTrue(zh.containsKey("string:app_name"))
        val text = resFile("values-zh").readText()
        assertTrue(text.contains(">CloneLab<"))
    }

    private companion object {
        /** %1$s, %d, %05.2f, %% ... */
        val PLACEHOLDER = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]""")
    }
}
