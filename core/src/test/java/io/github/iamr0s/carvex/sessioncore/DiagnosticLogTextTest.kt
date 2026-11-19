package io.github.iamr0s.carvex.sessioncore

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class DiagnosticLogTextTest {
    private fun line(index: Int) = "${Instant.EPOCH.plusMillis(index.toLong())} [session] line=$index"

    @Test fun retentionKeepsStartupAndNewestWindowRatherThanUiFourHundred() {
        val all = (0 until DiagnosticLogText.MEMORY_LINES + 500).map(::line)
        val retained = DiagnosticLogText.retain(all)
        assertEquals(DiagnosticLogText.MEMORY_LINES, retained.size)
        assertEquals(all.take(24), retained.take(24))
        assertEquals(all.takeLast(DiagnosticLogText.MEMORY_LINES - 24), retained.drop(24))
    }

    @Test fun smallLogIsNotTruncated() {
        val all = (0 until 600).map(::line)
        assertEquals(all, DiagnosticLogText.retain(all))
    }

    @Test fun exportIncludesDiskHistoryAndPendingMemoryWithoutDuplicateRows() {
        val disk = (0 until 2700).map(::line)
        val recent = (1000 until 3000).map(::line)
        val exported = DiagnosticLogText.merge(1, 1, disk, listOf(line(15)), recent)
        assertEquals((0 until 3000).map(::line), exported)
        assertTrue(exported.first().startsWith("1970-01-01T00:00:00Z [session]"))
    }

    @Test fun pendingClearExcludesOldDiskEvenBeforeWriterTruncatesIt() {
        val exported = DiagnosticLogText.merge(2, 1, listOf(line(1)), emptyList(), listOf(line(2)))
        assertEquals(listOf(line(2)), exported)
    }

    @Test fun completedClearAllowsNewGenerationDisk() {
        val exported = DiagnosticLogText.merge(2, 2, listOf(line(2)), emptyList(), listOf(line(3)))
        assertEquals(listOf(line(2), line(3)), exported)
    }

    @Test fun timestampsSortChronologicallyAcrossZeroAndFractionalSeconds() {
        assertEquals(listOf(line(0), line(1), line(1000)),
            DiagnosticLogText.merge(0, 0, listOf(line(1)), listOf(line(1000)), listOf(line(0))))
    }

    @Test fun diskReadRetainsMoreThanFourHundredLinesAndUtf8() {
        val file = Files.createTempFile("carvex-log-test", ".txt").toFile()
        try {
            val lines = (0 until 3000).map { line(it) + " 导航" }
            file.writeText(lines.joinToString("\n", postfix = "\n"))
            assertEquals(lines, DiagnosticLogText.readDisk(file))
        } finally { file.delete() }
    }

    @Test fun oversizedDiskReadIsBoundedAndDiscardsPartialLeadingLine() {
        val file = Files.createTempFile("carvex-log-test", ".txt").toFile()
        try {
            // 行宽约 740B，6000 行 ≈ 4.4MB，跨过 4MB 轮转上限才会触发截断断言。
            val lines = (0 until 6000).map { line(it) + " 导航".repeat(100) }
            file.writeText(lines.joinToString("\n", postfix = "\n"))
            val exported = DiagnosticLogText.readDisk(file)
            assertTrue(exported.size < lines.size)
            assertEquals(lines.takeLast(exported.size), exported)
            assertTrue(exported.joinToString("\n").toByteArray(Charsets.UTF_8).size <= DiagnosticLogText.DISK_BYTES)
        } finally { file.delete() }
    }

    @Test fun liveLoggerActuallyUpdatesMemoryAndRawExport() {
        BridgeStore.clearLogs()
        val session = BridgeStore.begin(1)
        try {
            repeat(DiagnosticLogText.MEMORY_LINES + 100) { BridgeStore.log(session, "retention-regression-$it") }
            assertEquals(DiagnosticLogText.MEMORY_LINES, BridgeStore.logs.value.size)
            assertTrue(BridgeStore.logs.value.first().endsWith("retention-regression-0"))
            assertTrue(BridgeStore.logs.value.last().endsWith("retention-regression-${DiagnosticLogText.MEMORY_LINES + 99}"))
            val exported = BridgeStore.export()
            assertTrue(exported.contains("[$session] retention-regression-0"))
            assertTrue(exported.contains("[$session] retention-regression-2099"))
            assertTrue(exported.lineSequence().count() > 400)
            BridgeStore.clearLogs()
            assertFalse(BridgeStore.export().contains("retention-regression-"))
        } finally { BridgeStore.clearLogs(); BridgeStore.idle() }
    }
}
