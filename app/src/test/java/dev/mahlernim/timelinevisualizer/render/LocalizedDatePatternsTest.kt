package dev.mahlernim.timelinevisualizer.render

import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Guards every translation of render_date_pattern: a translated pattern letter (such as "aaaa"
 * or "jjjj" for "yyyy") makes DateTimeFormatter.ofPattern throw during the first rendered
 * frame, which closed the app for Spanish, French, Portuguese, and German users right after a
 * Timeline import succeeded.
 */
class LocalizedDatePatternsTest {
    @Test
    fun everyLocalizedRenderDatePatternCompilesAndShowsMonthAndYear() {
        val resources = sequenceOf(File("src/main/res"), File("app/src/main/res"))
            .firstOrNull(File::isDirectory)
            ?: error("Resource directory not found from ${File(".").absolutePath}")
        val patternFiles = resources.listFiles { file -> file.name.startsWith("values") }
            .orEmpty()
            .mapNotNull { valuesDir -> File(valuesDir, "strings.xml").takeIf(File::isFile) }
        assertTrue("No strings.xml files found", patternFiles.isNotEmpty())

        val sample = ZonedDateTime.of(2025, 8, 19, 12, 0, 0, 0, ZoneOffset.UTC)
        val problems = mutableListOf<String>()
        var patternsChecked = 0
        patternFiles.forEach { stringsFile ->
            val pattern = renderDatePattern(stringsFile) ?: return@forEach
            patternsChecked += 1
            val formatter = try {
                DateTimeFormatter.ofPattern(pattern)
            } catch (error: IllegalArgumentException) {
                problems += "${stringsFile.parentFile.name}: pattern \"$pattern\" is invalid (${error.message})"
                return@forEach
            }
            val formatted = formatter.format(sample)
            if (!formatted.contains("2025")) {
                problems += "${stringsFile.parentFile.name}: pattern \"$pattern\" renders \"$formatted\" without the year"
            }
            if (formatted == formatter.format(sample.withMonth(9))) {
                problems += "${stringsFile.parentFile.name}: pattern \"$pattern\" renders \"$formatted\" without the month"
            }
        }
        assertTrue("render_date_pattern missing from default resources", patternsChecked > 0)
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    private fun renderDatePattern(stringsFile: File): String? {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsFile)
        val strings = document.getElementsByTagName("string")
        for (index in 0 until strings.length) {
            val node = strings.item(index)
            val name = node.attributes?.getNamedItem("name")?.nodeValue
            if (name == "render_date_pattern") return node.textContent
        }
        return null
    }
}
