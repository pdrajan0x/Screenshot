package com.pdrajan.dot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The JVM's regex engine accepts some patterns that Android's ICU engine rejects at runtime, so
 * scan the sources for the known traps.
 */
class AndroidRegexCompatTest {

    private val roots = listOf("src/main", "../ml/src/main", "../media/src/main", "../design/src/main", "../../app-screenshots/src/main")
        .map(::File).filter { it.isDirectory }

    @Test
    fun noIcuIncompatibleRegexSyntax() {
        val problems = mutableListOf<String>()
        roots.flatMap { root -> root.walk().filter { it.extension == "kt" }.toList() }.forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                if (line.trimStart().startsWith("//")) return@forEachIndexed
                // A set starting with "[:" is a POSIX class to ICU ([:alpha:]); it then hunts for ":]".
                if (Regex("""(?<!\\)\[\^?:""").containsMatchIn(line) && '"' in line) problems += "${file.name}:${i + 1}: set starting with [:"
                // Java-only property names.
                if (Regex("""\\\\p\{(Is|java|In)""").containsMatchIn(line)) problems += "${file.name}:${i + 1}: Java-only \\p{} class"
                if (line.contains("UNICODE_CHARACTER_CLASS")) problems += "${file.name}:${i + 1}: UNICODE_CHARACTER_CLASS is unsupported on Android"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
