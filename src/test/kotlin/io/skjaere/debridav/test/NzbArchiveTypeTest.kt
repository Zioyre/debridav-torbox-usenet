package io.skjaere.debridav.test

import io.skjaere.compressionutils.RarFileEntry
import io.skjaere.compressionutils.SevenZipFileEntry
import io.skjaere.debridav.usenet.nzb.NzbArchiveType
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.nzb.NzbDocument
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NzbArchiveTypeTest {

    private val emptyNzb = NzbDocument(emptyList())
    private val emptyResponse = NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList())

    private val rarEntry = RarFileEntry(
        path = "file.rar", uncompressedSize = 1000, compressedSize = 1000,
        headerPosition = 0, dataPosition = 0, isDirectory = false
    )
    private val sevenZipEntry = SevenZipFileEntry(
        path = "file.7z", size = 1000, packedSize = 1000,
        dataOffset = 0, isDirectory = false
    )

    // Outer entries whose paths look like inner archive volumes (for nested archive tests).
    // The fixed nestedType() infers the inner archive type from outer entry paths,
    // not from innerEntries type, since inner entries become TranslatedFileEntry.
    private val sevenZipOuterWithRarVolumes = SevenZipFileEntry(
        path = "inner.rar", size = 1000, packedSize = 1000,
        dataOffset = 0, isDirectory = false
    )
    private val rarOuterWith7zVolumes = RarFileEntry(
        path = "inner.7z", uncompressedSize = 1000, compressedSize = 1000,
        headerPosition = 0, dataPosition = 0, isDirectory = false
    )

    @Test
    fun `Raw metadata maps to RAW`() {
        val metadata = ExtractedMetadata.Raw(response = emptyResponse, orderedArchiveNzb = emptyNzb)
        assertEquals(NzbArchiveType.RAW, NzbArchiveType.from(metadata))
    }

    @Test
    fun `Archive with RarFileEntry maps to RAR`() {
        val metadata = ExtractedMetadata.Archive(
            response = emptyResponse, orderedArchiveNzb = emptyNzb, entries = listOf(rarEntry)
        )
        assertEquals(NzbArchiveType.RAR, NzbArchiveType.from(metadata))
    }

    @Test
    fun `Archive with SevenZipFileEntry maps to SEVEN_ZIP`() {
        val metadata = ExtractedMetadata.Archive(
            response = emptyResponse, orderedArchiveNzb = emptyNzb, entries = listOf(sevenZipEntry)
        )
        assertEquals(NzbArchiveType.SEVEN_ZIP, NzbArchiveType.from(metadata))
    }

    @Test
    fun `Archive with no recognized entries maps to RAW`() {
        val metadata = ExtractedMetadata.Archive(
            response = emptyResponse, orderedArchiveNzb = emptyNzb, entries = emptyList()
        )
        assertEquals(NzbArchiveType.RAW, NzbArchiveType.from(metadata))
    }

    @Test
    fun `NestedArchive with 7z outer and RAR inner maps to RAR_IN_SEVEN_ZIP`() {
        val metadata = ExtractedMetadata.NestedArchive(
            response = emptyResponse, orderedArchiveNzb = emptyNzb,
            outerEntries = listOf(sevenZipOuterWithRarVolumes), innerEntries = listOf(rarEntry)
        )
        assertEquals(NzbArchiveType.RAR_IN_SEVEN_ZIP, NzbArchiveType.from(metadata))
    }

    @Test
    fun `NestedArchive with 7z outer and 7z inner maps to SEVEN_ZIP_IN_SEVEN_ZIP`() {
        val metadata = ExtractedMetadata.NestedArchive(
            response = emptyResponse, orderedArchiveNzb = emptyNzb,
            outerEntries = listOf(sevenZipEntry), innerEntries = listOf(sevenZipEntry)
        )
        assertEquals(NzbArchiveType.SEVEN_ZIP_IN_SEVEN_ZIP, NzbArchiveType.from(metadata))
    }

    @Test
    fun `NestedArchive with RAR outer and RAR inner maps to RAR_IN_RAR`() {
        val metadata = ExtractedMetadata.NestedArchive(
            response = emptyResponse, orderedArchiveNzb = emptyNzb,
            outerEntries = listOf(rarEntry), innerEntries = listOf(rarEntry)
        )
        assertEquals(NzbArchiveType.RAR_IN_RAR, NzbArchiveType.from(metadata))
    }

    @Test
    fun `NestedArchive with RAR outer and 7z inner maps to SEVEN_ZIP_IN_RAR`() {
        val metadata = ExtractedMetadata.NestedArchive(
            response = emptyResponse, orderedArchiveNzb = emptyNzb,
            outerEntries = listOf(rarOuterWith7zVolumes), innerEntries = listOf(sevenZipEntry)
        )
        assertEquals(NzbArchiveType.SEVEN_ZIP_IN_RAR, NzbArchiveType.from(metadata))
    }

    @Test
    fun `NestedArchive with no recognized entries maps to RAW`() {
        val metadata = ExtractedMetadata.NestedArchive(
            response = emptyResponse, orderedArchiveNzb = emptyNzb,
            outerEntries = emptyList(), innerEntries = emptyList()
        )
        assertEquals(NzbArchiveType.RAW, NzbArchiveType.from(metadata))
    }
}
