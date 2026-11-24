package org.avmedia.smartsync.plugin.trmnl

import org.avmedia.smartsync.plugin.api.PluginApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Repo rules: the versions agree, and no text has dashes of the long kind, tool lines or pointers at private notes. */
class RepoGuardTest {
    private val repo = File("..").canonicalFile

    @Test fun theBuildFileAndThePluginNameTheSameVersion() {
        val declared = Regex("""(?m)^version = '([^']+)'""").find(File("build.gradle").readText())?.groupValues?.get(1)
        assertEquals(TrmnlPlugin.VERSION, declared)
        assertTrue(PluginApi.compatible(TrmnlPlugin().apiVersion))
    }

    @Test fun theServiceFileNamesThePlugin() {
        val f = File("src/main/resources/META-INF/services/org.avmedia.smartsync.plugin.api.WatchPlugin")
        assertEquals(TrmnlPlugin::class.java.name, f.readText().trim())
    }

    @Test fun noTextBreaksTheRepoRules() {
        val dash = "—"
        val banned = Regex(
            listOf(
                dash, """Generated with""", """Co-Authored-By""", """noreply@anthropic""", """casio-protocol-notes""",
                """\bnotes? section""", """official app""", """decompil""", """jadx""", """gshock-briefs""",
            ).joinToString("|"),
            RegexOption.IGNORE_CASE,
        )
        val self = File("src/test/java/org/avmedia/smartsync/plugin/trmnl/RepoGuardTest.kt").canonicalFile
        val ext = setOf("kt", "kts", "java", "md", "yml", "yaml", "gradle", "toml", "txt", "json", "xml", "properties")
        val files = repo.walkTopDown()
            .onEnter { it.name !in setOf(".git", "build", ".gradle") }
            .filter { it.isFile && it.extension in ext && it.canonicalFile != self }.toList()
        assertTrue("too few files, wrong directory? $repo", files.size > 15)
        val hits = files.flatMap { f ->
            f.readLines().withIndex().filter { banned.containsMatchIn(it.value) }
                .map { "${f.relativeTo(repo)}:${it.index + 1}: ${it.value.trim()}" }
        }
        assertTrue("repo rules:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
