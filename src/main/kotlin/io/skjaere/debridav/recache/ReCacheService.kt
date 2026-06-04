package io.skjaere.debridav.recache

import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.debrid.client.DebridCachedContentClient
import io.skjaere.debridav.debrid.client.torbox.TorBoxClient
import io.skjaere.debridav.debrid.client.torbox.TorBoxUsenetService
import io.skjaere.debridav.debrid.client.torbox.model.usenet.UsenetListItem
import io.skjaere.debridav.debrid.client.torbox.model.usenet.UsenetListItemFile
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridCachedTorrentContent
import io.skjaere.debridav.fs.DebridCachedUsenetReleaseContent
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.repository.DebridFileContentsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ReCacheService(
    private val debridFileRepository: DebridFileContentsRepository,
    private val fileService: DatabaseFileService,
    private val torBoxClient: TorBoxClient?,
    private val torBoxUsenetService: TorBoxUsenetService?,
    private val debridClients: List<DebridCachedContentClient>
) {
    private val logger = LoggerFactory.getLogger(ReCacheService::class.java)

    data class ReCacheResult(
        val entityId: Long,
        val status: String, // "recaching", "cached", "failed"
        val message: String,
        val estimatedSeconds: Long = 600
    )

    suspend fun recacheEntity(entityId: Long): ReCacheResult {
        val entity = debridFileRepository.findById(entityId).orElse(null)
            ?: return ReCacheResult(entityId, "failed", "Entity not found: $entityId")

        if (entity !is RemotelyCachedEntity) {
            return ReCacheResult(entityId, "failed", "Entity is not a remotely cached entity")
        }

        val contents = entity.contents ?: return ReCacheResult(entityId, "failed", "No contents found")

        return when (contents) {
            is DebridCachedTorrentContent -> recacheTorrent(entity, contents)
            is DebridCachedUsenetReleaseContent -> recacheUsenet(entity, contents)
            else -> ReCacheResult(entityId, "failed", "Unsupported content type: ${contents::class.simpleName}")
        }
    }

    private suspend fun recacheTorrent(
        entity: RemotelyCachedEntity,
        contents: DebridCachedTorrentContent
    ): ReCacheResult {
        val magnet = contents.magnet
        if (magnet.isNullOrBlank()) {
            return ReCacheResult(entity.id!!, "failed", "No magnet URI stored for torrent")
        }

        logger.info("Re-caching torrent: ${entity.name} (magnet hash from DB)")

        val torrentMagnet = TorrentMagnet(magnet)

        return try {
            // Re-submit magnet to TorBox — getCachedFiles with no params triggers addMagnet
            val torboxClient = debridClients.firstOrNull { it.getProvider() == DebridProvider.TORBOX }
                ?: return ReCacheResult(entity.id!!, "failed", "TorBox client not configured")

            val freshFiles = torboxClient.getCachedFiles(torrentMagnet, emptyMap())

            if (freshFiles.isEmpty()) {
                return ReCacheResult(entity.id!!, "failed", "No files returned after re-submit")
            }

            // Update entity links
            updateEntityLinks(entity, contents, freshFiles)

            ReCacheResult(
                entityId = entity.id!!,
                status = "cached",
                message = "Torrent re-cached successfully with ${freshFiles.size} files",
                estimatedSeconds = 0
            )
        } catch (e: Exception) {
            logger.error("Failed to re-cache torrent ${entity.name}", e)
            ReCacheResult(entity.id!!, "failed", "Re-cache failed: ${e.message}")
        }
    }

    private suspend fun recacheUsenet(
        entity: RemotelyCachedEntity,
        contents: DebridCachedUsenetReleaseContent
    ): ReCacheResult {
        val nzbBytes = contents.nzbBytes
        if (nzbBytes == null || nzbBytes.isEmpty()) {
            return ReCacheResult(entity.id!!, "failed", "No NZB bytes stored for usenet release")
        }

        val releaseName = contents.releaseName
        if (releaseName.isNullOrBlank()) {
            return ReCacheResult(entity.id!!, "failed", "No release name stored")
        }

        logger.info("Re-caching usenet release: $releaseName")

        return try {
            val freshFiles = torBoxUsenetService?.resubmitNzb(nzbBytes, releaseName)
                ?: return ReCacheResult(entity.id!!, "failed", "TorBox usenet service not configured")

            if (freshFiles.isEmpty()) {
                return ReCacheResult(entity.id!!, "recaching", "Re-submitted, waiting for TorBox to complete download")
            }

            updateEntityLinks(entity, contents, freshFiles)

            ReCacheResult(
                entityId = entity.id!!,
                status = "cached",
                message = "Usenet release re-cached successfully with ${freshFiles.size} files",
                estimatedSeconds = 0
            )
        } catch (e: Exception) {
            logger.error("Failed to re-cache usenet release $releaseName", e)
            ReCacheResult(entity.id!!, "failed", "Re-cache failed: ${e.message}")
        }
    }

    private suspend fun updateEntityLinks(
        entity: RemotelyCachedEntity,
        contents: DebridFileContents,
        freshFiles: List<CachedFile>
    ) = withContext(Dispatchers.IO) {
        // Replace all TorBox links with fresh ones
        contents.debridLinks.removeAll { it.provider == DebridProvider.TORBOX }
        contents.debridLinks.addAll(freshFiles)
        entity.contents = contents
        entity.lastModified = Instant.now().toEpochMilli()
        fileService.saveDbEntity(entity)
        logger.info("Updated links for entity ${entity.id}: ${freshFiles.size} fresh TorBox links")
    }
}
