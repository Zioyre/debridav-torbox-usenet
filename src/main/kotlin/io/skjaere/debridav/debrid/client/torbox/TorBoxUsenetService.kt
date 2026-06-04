package io.skjaere.debridav.debrid.client.torbox

import io.github.resilience4j.kotlin.ratelimiter.executeSuspendFunction
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.http.userAgent
import io.skjaere.debridav.category.CategoryService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.client.torbox.model.usenet.CreateUsenetDownloadResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.CreatedUsenetDownload
import io.skjaere.debridav.debrid.client.torbox.model.usenet.UsenetListItem
import io.skjaere.debridav.debrid.client.torbox.model.usenet.UsenetListItemFile
import io.skjaere.debridav.debrid.client.torbox.model.usenet.UsenetListResponse
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridCachedUsenetReleaseContent
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.usenet.UsenetDownload
import io.skjaere.debridav.usenet.UsenetDownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('torbox')}")
class TorBoxUsenetService(
    private val torboxHttpClient: HttpClient,
    private val torBoxConfiguration: TorBoxConfigurationProperties,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val usenetRepository: UsenetRepository,
    private val fileService: DatabaseFileService,
    private val categoryService: CategoryService,
    rateLimiterRegistry: RateLimiterRegistry
) {
    private val logger = LoggerFactory.getLogger(TorBoxUsenetService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rateLimiter: RateLimiter

    companion object {
        private const val RATE_LIMIT_WINDOW_SECONDS = 59L
        private const val RATE_LIMIT_REQUESTS_IN_WINDOW = 60
        private const val RATE_LIMIT_TIMEOUT_SECONDS = 5L
        private const val USER_AGENT = "DebriDav/0.12-torbox-usenet"
        private const val POLL_INTERVAL_MS = 10_000L
        private const val POLL_TIMEOUT_MINUTES = 60L
    }

    init {
        val rateLimiterConfig = RateLimiterConfig.custom()
            .limitRefreshPeriod(Duration.ofSeconds(RATE_LIMIT_WINDOW_SECONDS))
            .limitForPeriod(RATE_LIMIT_REQUESTS_IN_WINDOW)
            .timeoutDuration(Duration.ofSeconds(RATE_LIMIT_TIMEOUT_SECONDS))
            .build()
        rateLimiterRegistry.rateLimiter("torbox-usenet", rateLimiterConfig)
        rateLimiter = rateLimiterRegistry.rateLimiter("torbox-usenet")
    }

    suspend fun submitNzb(
        nzbBytes: ByteArray,
        releaseName: String,
        hash: String,
        categoryName: String
    ): UsenetDownload {
        val usenetDownload = createQueuedUsenetDownload(releaseName, hash, categoryName)

        scope.launch {
            try {
                processUsenetDownload(usenetDownload, nzbBytes, releaseName)
            } catch (e: Exception) {
                logger.error("TorBox usenet processing failed for '$releaseName'", e)
                usenetDownload.status = UsenetDownloadStatus.FAILED
                usenetRepository.save(usenetDownload)
            }
        }

        return usenetDownload
    }

    private suspend fun processUsenetDownload(
        usenetDownload: UsenetDownload,
        nzbBytes: ByteArray,
        releaseName: String
    ) {
        // Upload NZB via sync endpoint (returns download ID)
        val created: CreatedUsenetDownload? = rateLimiter.executeSuspendFunction {
            uploadNzb(nzbBytes, releaseName)
        }

        if (created == null) {
            logger.error("TorBox usenet upload failed for '$releaseName'")
            usenetDownload.status = UsenetDownloadStatus.FAILED
            usenetRepository.save(usenetDownload)
            return
        }

        val usenetId = created.usenetDownloadId
        logger.info("TorBox usenet upload accepted for '$releaseName' (id=$usenetId), polling...")
        usenetDownload.status = UsenetDownloadStatus.DOWNLOADING
        usenetRepository.save(usenetDownload)

        // Poll mylist by download ID
        val usenetItem = pollById(usenetId, releaseName)

        if (usenetItem == null) {
            logger.error("TorBox usenet polling timed out for '$releaseName' (id=$usenetId)")
            usenetDownload.status = UsenetDownloadStatus.FAILED
            usenetRepository.save(usenetDownload)
            return
        }

        val files = usenetItem.files ?: emptyList()
        if (files.isEmpty()) {
            logger.warn("TorBox usenet download '$releaseName' complete but has no files")
            usenetDownload.status = UsenetDownloadStatus.FAILED
            usenetRepository.save(usenetDownload)
            return
        }

        logger.info("TorBox usenet download '$releaseName' complete with ${files.size} files")

        val debridFiles = withContext(Dispatchers.IO) {
            files.map { file ->
                createDebridFileFromUsenetFile(file, usenetItem.id, releaseName, usenetDownload.hash!!, nzbBytes)
            }
        }.toMutableList()

        usenetDownload.debridFiles = debridFiles
        usenetDownload.status = UsenetDownloadStatus.COMPLETED
        usenetDownload.percentCompleted = 1.0
        usenetDownload.size = files.firstOrNull()?.size ?: 0
        usenetRepository.save(usenetDownload)
    }

    private suspend fun uploadNzb(nzbBytes: ByteArray, releaseName: String): CreatedUsenetDownload? {
        val response: HttpResponse = torboxHttpClient.submitFormWithBinaryData(
            url = "${getBaseUrl()}/api/usenet/createusenetdownload",
            formData = formData {
                append("file", nzbBytes, io.ktor.http.Headers.build {
                    append(HttpHeaders.ContentType, "application/x-nzb")
                    append(HttpHeaders.ContentDisposition, "filename=\"${releaseName}.nzb\"")
                })
            }
        ) {
            headers {
                accept(ContentType.Application.Json)
                bearerAuth(torBoxConfiguration.apiKey)
                userAgent(USER_AGENT)
            }
            timeout {
                requestTimeoutMillis = 60_000
                socketTimeoutMillis = 60_000
            }
        }

        return if (response.status.isSuccess()) {
            val body = response.body<CreateUsenetDownloadResponse>()
            if (body.success && body.data != null) {
                logger.debug("TorBox upload response: id=${body.data.usenetDownloadId}")
                body.data
            } else {
                logger.error("TorBox upload failed: error=${body.error}")
                null
            }
        } else {
            logger.error("TorBox upload failed: status=${response.status}")
            null
        }
    }

    private suspend fun pollById(usenetId: Long, releaseName: String): UsenetListItem? {
        val deadline = System.currentTimeMillis() + (POLL_TIMEOUT_MINUTES * 60 * 1000)
        var firstPoll = true

        while (System.currentTimeMillis() < deadline) {
            if (!firstPoll) {
                delay(POLL_INTERVAL_MS)
            }
            firstPoll = false

            val item = rateLimiter.executeSuspendFunction {
                findUsenetDownloadById(usenetId)
            }

            if (item == null) {
                logger.debug("TorBox usenet download id=$usenetId not found yet for '$releaseName'")
                continue
            }

            val state = item.downloadState
            logger.debug("TorBox usenet '$releaseName' state: $state")

            when (state) {
                "cached", "completed" -> {
                    logger.info("TorBox usenet '$releaseName' completed (state=$state)")
                    return item
                }
                "failed" -> {
                    logger.error("TorBox usenet download '$releaseName' failed")
                    return null
                }
                "downloading" -> {
                    logger.debug("TorBox usenet '$releaseName' downloading...")
                    continue
                }
            }
        }

        logger.error("TorBox usenet '$releaseName' timed out after ${POLL_TIMEOUT_MINUTES}min")
        return null
    }

    private suspend fun findUsenetDownloadById(usenetId: Long): UsenetListItem? {
        val response: HttpResponse = torboxHttpClient.get(
            "${getBaseUrl()}/api/usenet/mylist"
        ) {
            headers {
                accept(ContentType.Application.Json)
                bearerAuth(torBoxConfiguration.apiKey)
                userAgent(USER_AGENT)
            }
            timeout {
                requestTimeoutMillis = torBoxConfiguration.requestTimeoutMillis
                socketTimeoutMillis = torBoxConfiguration.socketTimeoutMillis
            }
        }

        return if (response.status.isSuccess()) {
            val listResponse = response.body<UsenetListResponse>()
            listResponse.data?.find { it.id == usenetId }
        } else {
            logger.warn("TorBox mylist returned ${response.status}")
            null
        }
    }

    private suspend fun createDebridFileFromUsenetFile(
        file: UsenetListItemFile,
        usenetId: Long,
        releaseName: String,
        hash: String,
        nzbBytes: ByteArray
    ): RemotelyCachedEntity = withContext(Dispatchers.IO) {
        val downloadLink = getDownloadLink(usenetId, file.id)

        val contents = DebridCachedUsenetReleaseContent(
            originalPath = file.name,
            size = file.size,
            modified = Instant.now().toEpochMilli(),
            releaseName = releaseName,
            mimeType = file.mimeType ?: "application/octet-stream",
            debridLinks = mutableListOf()
        ).also { it.nzbBytes = nzbBytes }

        fileService.createDebridFile(
            "${debridavConfigurationProperties.downloadPath}/$releaseName/${file.name}",
            hash,
            contents
        )
    }

    private fun getDownloadLink(usenetId: Long, fileId: Int): String {
        return "${getBaseUrl()}/api/usenet/requestdl" +
                "?token=${torBoxConfiguration.apiKey}" +
                "&usenet_id=$usenetId" +
                "&file_id=$fileId" +
                "&redirect=true"
    }

    private suspend fun createQueuedUsenetDownload(
        releaseName: String,
        hash: String,
        categoryName: String
    ): UsenetDownload = withContext(Dispatchers.IO) {
        val category = categoryService.getOrCreateCategory(categoryName)
        val usenetDownload = UsenetDownload()
        usenetDownload.status = UsenetDownloadStatus.QUEUED
        usenetDownload.name = releaseName
        usenetDownload.hash = hash
        usenetDownload.category = category
        usenetDownload.storagePath =
            "${debridavConfigurationProperties.mountPath}${debridavConfigurationProperties.downloadPath}/$releaseName"
        usenetDownload.percentCompleted = 0.0
        usenetDownload.size = 0
        usenetRepository.save(usenetDownload)
    }

    private fun getBaseUrl(): String = "${torBoxConfiguration.baseUrl}/${torBoxConfiguration.version}"

    /**
     * Resubmit an NZB for re-caching purposes. Uploads the NZB bytes, polls for completion,
     * and returns the completed files as CachedFile entries with download links.
     * Returns an empty list if the download is still in progress (files not available yet),
     * or null if upload or polling fails.
     */
    suspend fun resubmitNzb(nzbBytes: ByteArray, releaseName: String): List<CachedFile>? {
        val created: CreatedUsenetDownload? = rateLimiter.executeSuspendFunction {
            uploadNzb(nzbBytes, releaseName)
        }

        if (created == null) {
            logger.error("TorBox usenet re-upload failed for '$releaseName'")
            return null
        }

        val usenetId = created.usenetDownloadId
        logger.info("TorBox usenet re-upload accepted for '$releaseName' (id=$usenetId), polling...")

        val item = pollById(usenetId, releaseName) ?: return null

        val files = item.files ?: return null
        if (files.isEmpty()) return emptyList()  // download still in progress, no files yet

        return files.map { file ->
            CachedFile(
                path = file.name,
                size = file.size,
                mimeType = file.mimeType ?: "application/octet-stream",
                link = getDownloadLink(usenetId, file.id),
                params = mapOf(
                    "usenet_id" to usenetId.toString(),
                    "file_id" to file.id.toString()
                ),
                lastChecked = Instant.now().toEpochMilli(),
                provider = DebridProvider.TORBOX
            )
        }
    }
}
