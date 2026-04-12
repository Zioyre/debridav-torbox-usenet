package io.skjaere.debridav.usenet.pgmq

import io.skjaere.debridav.arrs.ArrService
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.health.RepairAction
import io.skjaere.debridav.health.RepairConfigurationProperties
import io.skjaere.debridav.health.RepairOutcomeService
import io.skjaere.debridav.repository.NzbDocumentRepository
import io.skjaere.debridav.repository.UsenetRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NzbHealthRepairHandler(
    private val nzbDocumentRepository: NzbDocumentRepository,
    private val usenetRepository: UsenetRepository,
    private val arrService: ArrService,
    private val fileService: DatabaseFileService,
    private val repairConfig: RepairConfigurationProperties,
    private val repairOutcomeService: RepairOutcomeService
) {
    private val logger = LoggerFactory.getLogger(NzbHealthRepairHandler::class.java)

    @Transactional
    fun handle(msg: NzbHealthRepairMessage, msgId: Long) {
        if (!repairConfig.enabled) {
            logger.debug("Repair is disabled, skipping NZB document {}", msg.nzbDocumentId)
            repairOutcomeService.record(QUEUE_NAME, msgId, RepairAction.SKIPPED)
            return
        }

        val nzbDocument = nzbDocumentRepository.findById(msg.nzbDocumentId).orElse(null)
        if (nzbDocument == null) {
            logger.warn("No NzbDocument found for ID {}", msg.nzbDocumentId)
            return
        }

        val category = nzbDocument.category
        val name = nzbDocument.name
        if (category == null || name == null) {
            logger.warn(
                "NzbDocument {} missing category or name, cannot notify Arr",
                nzbDocument.id
            )
            deleteVirtualFiles(nzbDocument.id!!)
            repairOutcomeService.record(QUEUE_NAME, msgId, RepairAction.DELETED)
            return
        }

        if (arrService.getClientForCategory(category) != null) {
            val downloadId = nzbDocument.downloadId
            if (downloadId != null) {
                logger.info(
                    "Blocklisting downloadId '{}' for '{}' (category: {})",
                    downloadId, name, category
                )
                runBlocking { arrService.blocklist(downloadId, category) }
            } else {
                logger.warn(
                    "NzbDocument {} has no downloadId, skipping blocklist",
                    nzbDocument.id
                )
            }

            logger.info(
                "Notifying Arr to delete file and search for '{}' (category: {})",
                name, category
            )
            val found = runBlocking { arrService.deleteFileAndSearch(name, category) }
            if (!found) {
                logger.info(
                    "Arr could not find '{}', deleting from virtual filesystem",
                    name
                )
                deleteVirtualFiles(nzbDocument.id!!)
                repairOutcomeService.record(QUEUE_NAME, msgId, RepairAction.DELETED)
            } else {
                repairOutcomeService.record(QUEUE_NAME, msgId, RepairAction.REPAIRED)
            }
        } else {
            logger.info(
                "No Arr client for NZB {} (category: {}), deleting files from virtual filesystem",
                nzbDocument.id, category
            )
            deleteVirtualFiles(nzbDocument.id!!)
            repairOutcomeService.record(QUEUE_NAME, msgId, RepairAction.DELETED)
        }
    }

    private fun deleteVirtualFiles(nzbDocumentId: Long) {
        val usenetDownload = usenetRepository.findByNzbDocumentId(nzbDocumentId)
        if (usenetDownload != null) {
            usenetDownload.debridFiles.forEach { file ->
                logger.info("Deleting '{}' from virtual filesystem", file.name)
                fileService.deleteFile(file)
            }
        } else {
            logger.warn("No UsenetDownload found for NzbDocument {}, cannot delete virtual files", nzbDocumentId)
        }
    }

    companion object {
        const val QUEUE_NAME = "nzb_health_repair"
    }
}
