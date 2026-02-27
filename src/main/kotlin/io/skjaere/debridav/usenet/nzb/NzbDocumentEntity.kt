package io.skjaere.debridav.usenet.nzb

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType
import io.skjaere.compressionutils.RarFileEntry
import io.skjaere.compressionutils.SevenZipFileEntry
import io.skjaere.compressionutils.SplitInfo
import io.skjaere.nntp.YencHeaders
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.nzb.NzbFile
import io.skjaere.nzbstreamer.nzb.NzbSegment
import io.skjaere.nzbstreamer.stream.StreamableFile
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Type
import java.time.Instant

@Entity
@Table(name = "nzb_document")
open class NzbDocumentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Type(JsonBinaryType::class)
    @Column(name = "files", columnDefinition = "jsonb", nullable = false)
    open var files: List<NzbFileJson> = emptyList()

    @Type(JsonBinaryType::class)
    @Column(name = "streamable_files", columnDefinition = "jsonb", nullable = false)
    open var streamableFiles: List<StreamableFileJson> = emptyList()

    @Enumerated(EnumType.STRING)
    @Column(name = "archive_type", nullable = false)
    open var archiveType: NzbArchiveType = NzbArchiveType.RAW

    @Column(name = "last_verified")
    open var lastVerified: Instant? = null

    @Column(name = "health_check_enqueued_at")
    open var healthCheckEnqueuedAt: Instant? = null

    @Column(name = "category")
    open var category: String? = null

    @Column(name = "name")
    open var name: String? = null

    @Column(name = "download_id")
    open var downloadId: String? = null
}

enum class NzbArchiveType {
    RAW,
    RAR,
    SEVEN_ZIP,
    RAR_IN_SEVEN_ZIP,
    RAR_IN_RAR,
    SEVEN_ZIP_IN_RAR,
    SEVEN_ZIP_IN_SEVEN_ZIP;

    companion object {
        fun from(metadata: ExtractedMetadata): NzbArchiveType = when (metadata) {
            is ExtractedMetadata.Raw -> RAW
            is ExtractedMetadata.Archive -> when {
                metadata.entries.any { it is RarFileEntry } -> RAR
                metadata.entries.any { it is SevenZipFileEntry } -> SEVEN_ZIP
                else -> RAW
            }
            is ExtractedMetadata.NestedArchive -> nestedType(metadata)
        }

        private val RAR_EXTENSIONS = setOf("rar", "r00", "r01", "s00", "s01", "part01.rar", "part001.rar")
        private val SEVEN_ZIP_EXTENSIONS = setOf("7z", "7z.001", "7z.002")

        private fun looksLikeRarPath(path: String): Boolean {
            val lower = path.lowercase()
            return RAR_EXTENSIONS.any { lower.endsWith(".$it") } ||
                    Regex("""\.(r|s)\d{2,3}$""").containsMatchIn(lower) ||
                    Regex("""\.part\d+\.rar$""").containsMatchIn(lower)
        }

        private fun looksLike7zPath(path: String): Boolean {
            val lower = path.lowercase()
            return SEVEN_ZIP_EXTENSIONS.any { lower.endsWith(".$it") } ||
                    Regex("""\.7z\.\d{3}$""").containsMatchIn(lower)
        }

        private fun nestedType(metadata: ExtractedMetadata.NestedArchive): NzbArchiveType {
            val outerIsRar = metadata.outerEntries.any { it is RarFileEntry }
            val outerIs7z = metadata.outerEntries.any { it is SevenZipFileEntry }

            // Inner entries are always TranslatedFileEntry after nested archive processing,
            // so we infer the inner archive type from the archive-extension filenames
            // present in the outer entries (those are the inner archive volumes).
            val innerVolumeNames = metadata.outerEntries
                .filter { !it.isDirectory }
                .map { it.path }
            val innerIsRar = innerVolumeNames.any { looksLikeRarPath(it) }
            val innerIs7z = innerVolumeNames.any { looksLike7zPath(it) }

            return when {
                outerIs7z && innerIsRar -> RAR_IN_SEVEN_ZIP
                outerIs7z && innerIs7z -> SEVEN_ZIP_IN_SEVEN_ZIP
                outerIsRar && innerIsRar -> RAR_IN_RAR
                outerIsRar && innerIs7z -> SEVEN_ZIP_IN_RAR
                else -> RAW
            }
        }
    }
}

data class NzbFileJson(
    val yencSize: Long,
    val yencPartEnd: Long? = null,
    val segments: List<NzbSegmentJson>
) : java.io.Serializable

data class NzbSegmentJson(
    val articleId: String,
    val number: Int,
    val bytes: Long
) : java.io.Serializable

data class StreamableFileJson(
    val path: String,
    val totalSize: Long,
    val startVolumeIndex: Int,
    val startOffsetInVolume: Long,
    val continuationHeaderSize: Long,
    val endOfArchiveSize: Long,
    val preComputedSplits: List<SplitInfoJson>? = null
) : java.io.Serializable

data class SplitInfoJson(
    val volumeIndex: Int,
    val dataStartPosition: Long,
    val dataSize: Long
) : java.io.Serializable

fun NzbDocumentEntity.toNzbDocument(): NzbDocument = NzbDocument(
    files = files.map { fileJson ->
        NzbFile(
            poster = "",
            date = 0,
            subject = "",
            groups = emptyList(),
            segments = fileJson.segments.map { seg ->
                NzbSegment(
                    articleId = seg.articleId,
                    number = seg.number,
                    bytes = seg.bytes
                )
            },
            yencHeaders = YencHeaders(
                line = 128,
                size = fileJson.yencSize,
                name = "",
                partEnd = fileJson.yencPartEnd
            )
        )
    }
)

fun StreamableFileJson.toStreamableFile(): StreamableFile = StreamableFile(
    path = path,
    totalSize = totalSize,
    startVolumeIndex = startVolumeIndex,
    startOffsetInVolume = startOffsetInVolume,
    continuationHeaderSize = continuationHeaderSize,
    endOfArchiveSize = endOfArchiveSize,
    preComputedSplits = preComputedSplits?.map {
        SplitInfo(
            volumeIndex = it.volumeIndex,
            dataStartPosition = it.dataStartPosition,
            dataSize = it.dataSize
        )
    }
)

fun NzbDocumentEntity.findStreamableFile(path: String): StreamableFile? =
    streamableFiles.firstOrNull { it.path == path }?.toStreamableFile()
