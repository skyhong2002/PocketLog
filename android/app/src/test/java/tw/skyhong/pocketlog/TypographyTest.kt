package tw.skyhong.pocketlog

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Round 5 rule: user-facing text never uses ASCII `...`; it uses `…`. */
class TypographyTest {
    private val literal = Regex("\"(?:[^\"\\\\\n]|\\\\.)*\"")

    @Test fun noAsciiEllipsisInStringResourcesOrKotlinLiterals() {
        val main = listOf(File("src/main"), File("app/src/main"), File("android/app/src/main")).first { it.isDirectory }
        val offenders = mutableListOf<String>()
        main.walkTopDown().filter { it.isFile && (it.extension == "kt" || (it.extension == "xml" && it.parentFile.name.startsWith("values"))) }.forEach { file ->
            file.readLines().forEachIndexed { n, line ->
                val texts = if (file.extension == "kt") literal.findAll(line).map { it.value } else sequenceOf(line)
                if (texts.any { "..." in it }) offenders += "${file.name}:${n + 1}"
            }
        }
        assertTrue("ASCII ... in UI text: $offenders", offenders.isEmpty())
        assertTrue(literal.containsMatchIn("val x = \"a...b\""))
    }
}
