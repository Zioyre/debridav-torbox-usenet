package io.skjaere.debridav.usenet.pgmq

import io.skjaere.debridav.arrs.ArrService
import io.skjaere.debridav.repository.NzbDocumentRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty("nntp.enabled", havingValue = "true")
class NzbHealthRepairHandler(
    private val nzbDocumentRepository: NzbDocumentRepository,
    private val arrService: ArrService
) {
    private val logger = LoggerFactory.getLogger(NzbHealthRepairHandler::class.java)

    fun handle(msg: NzbHealthRepairMessage) {
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
            return
        }

        if (arrService.getClientForCategory(category) != null) {
            val downloadId = nzbDocument.downloadId
            if (downloadId != null) {
                logger.info(
                    "Blocklisting downloadId '{}' for '{}' (category: {})",
                    downloadId,
                    name,
                    category
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
                name,
                category
            )
            runBlocking { arrService.deleteFileAndSearch(name, category) }
        }
    }
}
