package app.kotowski.sms2email

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// values-ru mirrors the English file section by section, so its entry order must stay
// identical to values/ (the translatable subset): a future locale file (e.g. values-zh)
// is written against the English order, and any drift breaks the one-to-one diffing.
class LocalizationOrderTest {
    private val resDir =
        listOf(File("src/main/res"), File("app/src/main/res"))
            .firstOrNull { it.isDirectory }
            ?: error("strings dir not found")

    private fun entryOrder(
        file: File,
        skipNonTranslatable: Boolean,
    ): List<Pair<String, String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val order = mutableListOf<Pair<String, String>>()
        val children = document.documentElement.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as Element
            if (element.tagName !in setOf("string", "plurals")) continue
            val nonTranslatable =
                skipNonTranslatable && element.getAttribute("translatable") == "false"
            if (!nonTranslatable) order += element.tagName to element.getAttribute("name")
        }
        return order
    }

    @Test
    fun `russian strings follow the english entry order`() {
        val en = entryOrder(resDir.resolve("values/strings.xml"), skipNonTranslatable = true)
        val ru = entryOrder(resDir.resolve("values-ru/strings.xml"), skipNonTranslatable = false)
        assertEquals(en, ru)
    }

    @Test
    fun `russian smtp errors follow the english entry order`() {
        val en = entryOrder(resDir.resolve("values/smtp_errors.xml"), skipNonTranslatable = false)
        val ru =
            entryOrder(resDir.resolve("values-ru/smtp_errors.xml"), skipNonTranslatable = false)
        assertEquals(en, ru)
    }
}
