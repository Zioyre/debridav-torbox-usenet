package io.skjaere.debridav.usenet

import com.vdsirotkin.pgmq.PgmqClient
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.NzbContents
import io.skjaere.debridav.repository.NzbDocumentRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.usenet.nzb.NzbArchiveType
import io.skjaere.debridav.usenet.nzb.NzbDocumentEntity
import io.skjaere.debridav.usenet.nzb.NzbFileJson
import io.skjaere.debridav.usenet.nzb.NzbSegmentJson
import io.skjaere.debridav.usenet.nzb.SplitInfoJson
import io.skjaere.debridav.usenet.nzb.StreamableFileJson
import io.skjaere.debridav.usenet.pgmq.NzbImportMessage
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.PrepareResult
import io.skjaere.nzbstreamer.stream.StreamableFile
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.*

@Service
@ConditionalOnProperty("nntp.enabled", havingValue = "true")
class NzbImportService(
    private val nzbStreamer: NzbStreamer,
    private val nzbDocumentRepository: NzbDocumentRepository,
    private val usenetRepository: UsenetRepository,
    private val pgmqClient: PgmqClient,
    private val databaseFileService: DatabaseFileService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(NzbImportService::class.java)

    fun scheduleImport(nzbBytes: ByteArray, usenetDownload: UsenetDownload) {
        pgmqClient.send(
            "nzb_import",
            NzbImportMessage(
                nzbBytesBase64 = Base64.getEncoder().encodeToString(nzbBytes),
                usenetDownloadId = usenetDownload.id!!
            )
        )
    }

    @Transactional
    @Suppress("LongMethod", "ReturnCount")
    fun executeImport(taskData: NzbImportTaskData) {
        val usenetDownload = usenetRepository.findById(taskData.usenetDownloadId).orElseThrow {
            IllegalStateException("UsenetDownload not found: ${taskData.usenetDownloadId}")
        }
        try {
            logger.info("Importing ${usenetDownload.name}")
            val nzbBytes = Base64.getDecoder().decode(taskData.nzbBytesBase64)
            val prepareResult = runBlocking { nzbStreamer.prepare(nzbBytes) }

            when (prepareResult) {
                is PrepareResult.MissingArticles -> {
                    logger.warn(
                        "Articles missing from Usenet for '{}': {}",
                        usenetDownload.name,
                        prepareResult.message
                    )
                    usenetDownload.status = UsenetDownloadStatus.FAILED
                    return
                }

                is PrepareResult.Failure -> {
                    logger.error(
                        "NNTP failure importing '{}': {}",
                        usenetDownload.name,
                        prepareResult.message,
                        prepareResult.cause
                    )
                    usenetDownload.status = UsenetDownloadStatus.FAILED
                    return
                }

                is PrepareResult.UnsupportedArchive -> {
                    logger.warn(
                        "Unsupported archive type for '{}': {}",
                        usenetDownload.name,
                        prepareResult.message
                    )
                    usenetDownload.status = UsenetDownloadStatus.FAILED
                    return
                }

                is PrepareResult.Success -> {
                    val metadata = prepareResult.metadata
                    val streamableFiles = nzbStreamer.resolveStreamableFiles(metadata)
                    val documentEntity = toDocumentEntity(metadata, streamableFiles)
                    documentEntity.category = usenetDownload.category?.name
                    documentEntity.name = usenetDownload.name
                    documentEntity.downloadId = usenetDownload.id.toString()
                    documentEntity.lastVerified = Instant.now()
                    val savedDocument = nzbDocumentRepository.save(documentEntity)
                    usenetDownload.nzbDocument = savedDocument

                    usenetDownload.debridFiles = savedDocument.streamableFiles.map { sf ->
                        val nzbContents = NzbContents().apply {
                            nzbDocument = savedDocument
                            originalPath = sf.path
                            size = sf.totalSize
                            modified = Instant.now().toEpochMilli()
                        }
                        val path = "${debridavConfigurationProperties.downloadPath}" +
                                "/${usenetDownload.name}/${sf.path}"
                        databaseFileService.createDebridFile(
                            path,
                            usenetDownload.hash!!,
                            nzbContents
                        )
                    }.toMutableList()

                    usenetDownload.status = UsenetDownloadStatus.COMPLETED
                    logger.info("Imported ${usenetDownload.name}")
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error("Failed to import NZB for download '${usenetDownload.name}'", e)
            usenetDownload.status = UsenetDownloadStatus.FAILED
        } finally {
            usenetRepository.save(usenetDownload)
        }
    }

    /** Strip null bytes — PostgreSQL JSONB rejects \u0000 */
    private fun String.sanitize(): String = replace("\u0000", "")

    private fun toDocumentEntity(
        metadata: ExtractedMetadata,
        streamableFiles: List<StreamableFile>
    ): NzbDocumentEntity {
        val entity = NzbDocumentEntity()
        entity.archiveType = NzbArchiveType.from(metadata)
        entity.files = metadata.orderedArchiveNzb.files.map { file ->
            NzbFileJson(
                yencSize = file.yencHeaders!!.size,
                yencPartEnd = file.yencHeaders!!.partEnd,
                segments = file.segments.map { segment ->
                    NzbSegmentJson(
                        articleId = segment.articleId.sanitize(),
                        number = segment.number,
                        bytes = segment.bytes
                    )
                }
            )
        }
        entity.streamableFiles = streamableFiles.map { sf ->
            StreamableFileJson(
                path = sf.path.sanitize(),
                totalSize = sf.totalSize,
                startVolumeIndex = sf.startVolumeIndex,
                startOffsetInVolume = sf.startOffsetInVolume,
                continuationHeaderSize = sf.continuationHeaderSize,
                endOfArchiveSize = sf.endOfArchiveSize,
                preComputedSplits = sf.preComputedSplits?.map {
                    SplitInfoJson(
                        volumeIndex = it.volumeIndex,
                        dataStartPosition = it.dataStartPosition,
                        dataSize = it.dataSize
                    )
                }
            )
        }
        return entity
    }
}
