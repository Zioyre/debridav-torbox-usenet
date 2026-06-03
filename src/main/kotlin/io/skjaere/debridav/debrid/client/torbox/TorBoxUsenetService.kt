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
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.userAgent
import io.ktor.utils.io.core.toByteArray
import io.skjaere.debridav.category.CategoryService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.client.torbox.model.usenet.CreateUsenetDownloadResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.UsenetListItem
import io.skjaere.debridav.debrid.client.torbox.model.usenet.UsenetListItemFile
import io.skjaere.debridav.debrid.client.torbox.model.usenet.UsenetListResponse
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridFileContents
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
@ConditionalOnExpression("""#{'\${debridav.debrid-clients}'.contains('torbox')}""")
class TorBoxUsenetService(
    private val torboxHttpClient: HttpClient,
    private val torBoxConfiguration: TorBoxConfigurationProperties,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val usenetRepository: UsenetRepository,
    private val fileService: DatabaseFileService,
    rateLimiterRegistry: RateLimiterRegistry
) {
    private val logger = LoggerFactory.getLogger(TorBoxUsenetService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rateLimiter: RateLimiter

    companion object {
        const val RATE_LIMIT_WINDOW_SECONDS = 59L
        const val RATE_LIMIT_REQUESTS_IN_WINDOW = 60
        const val RATE_LIMIT_TIMEOUT_SECONDS = 5L
        const val USER_AGENT = "DebriDav/0.12.0-torbox-usenet (https://github.com/Zioyre/debridav-torbox-usenet)"
        const val POLL_INTERVAL_MS = 5000L
        const val POLL_TIMEOUT_MINUTES = 30L
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

    /**
     * Upload an NZB to TorBox's usenet API and start background polling.
     * Returns immediately with a QUEUED UsenetDownload.
     */
    suspend fun submitNzb(nzbBytes: ByteArray, releaseName: String, hash: String, categoryName: String): UsenetDownload {
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
        // Upload NZB to TorBox
        val torboxId = rateLimiter.executeSuspendFunction {
            createUsenetDownload(nzbBytes, releaseName)
        }

        if (torboxId == null) {
            logger.error("TorBox usenet creation returned null ID for '$releaseName'")
            usenetDownload.status = UsenetDownloadStatus.FAILED
            usenetRepository.save(usenetDownload)
            return
        }

        usenetDownload.status = UsenetDownloadStatus.DOWNLOADING
        usenetRepository.save(usenetDownload)

        // Poll until complete
        val usenetItem = pollUntilComplete(torboxId, releaseName)

        if (usenetItem == null) {
            logger.error("TorBox usenet polling timed out or failed for '$releaseName'")
            usenetDownload.status = UsenetDownloadStatus.FAILED
            usenetRepository.save(usenetDownload)
            return
        }

        // Create file entries
        val files = usenetItem.files ?: emptyList()
        if (files.isEmpty()) {
            logger.warn("TorBox usenet download '$releaseName' completed but has no files")
            usenetDownload.status = UsenetDownloadStatus.FAILED
            usenetRepository.save(usenetDownload)
            return
        }

        logger.info("TorBox usenet download '$releaseName' complete with ${files.size} files")

        val debridFiles = withContext(Dispatchers.IO) {
            files.map { file ->
                createDebridFileFromUsenetFile(
                    file,
                    torboxId,
                    releaseName,
                    usenetDownload.hash!!
                )
            }
        }.toMutableList()

        usenetDownload.debridFiles = debridFiles
        usenetDownload.status = UsenetDownloadStatus.COMPLETED
        usenetDownload.percentCompleted = 1.0
        usenetDownload.size = files.firstOrNull()?.size ?: 0
        usenetRepository.save(usenetDownload)
    }

    private suspend fun createUsenetDownload(nzbBytes: ByteArray, releaseName: String): Long? {
        val response: HttpResponse = torboxHttpClient.submitFormWithBinaryData(
            url = "${getBaseUrl()}/api/usenet/createusenetdownload",
            formData = formData {
                append("file", nzbBytes, Headers.build {
                    append(HttpHeaders.ContentType, "application/x-nzb")
                    append(HttpHeaders.ContentDisposition, "filename=\"${releaseName}.nzb\"")
                })
                append("as_queued", "true")
            }
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

        if (response.status.isSuccess()) {
            val body = response.body<CreateUsenetDownloadResponse>()
            logger.debug("TorBox create usenet response: success=${body.success}, id=${body.data?.usenetDownloadId}")
            return body.data?.usenetDownloadId
        } else {
            logger.error("TorBox usenet creation failed: ${response.status}")
            return null
        }
    }

    private suspend fun pollUntilComplete(
        torboxId: Long,
        releaseName: String
    ): UsenetListItem? {
        val deadline = System.currentTimeMillis() + (POLL_TIMEOUT_MINUTES * 60 * 1000)

        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)

            val item = rateLimiter.executeSuspendFunction {
                getUsenetDownload(torboxId)
            }

            if (item == null) {
                logger.warn("TorBox usenet download $torboxId not found yet for '$releaseName'")
                continue
            }

            val status = item.status
            logger.debug("TorBox usenet $torboxId ('$releaseName') status: $status")

            when (status) {
                "completed", "cached" -> return item
                "failed" -> {
                    logger.error("TorBox usenet download $torboxId ('$releaseName') failed")
                    return null
                }
                // "downloading", "queued", "processing", etc. — keep polling
            }
        }

        logger.error("TorBox usenet download $torboxId ('$releaseName') timed out after ${POLL_TIMEOUT_MINUTES}min")
        return null
    }

    private suspend fun getUsenetDownload(usenetId: Long): UsenetListItem? {
        val response: HttpResponse = torboxHttpClient.get(
            "${getBaseUrl()}/api/usenet/mylist?id=$usenetId"
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
            response.body<UsenetListResponse>().data
        } else {
            logger.warn("TorBox mylist returned ${response.status} for usenet $usenetId")
            null
        }
    }

    private suspend fun createDebridFileFromUsenetFile(
        file: UsenetListItemFile,
        usenetId: Long,
        releaseName: String,
        hash: String
    ): RemotelyCachedEntity = withContext(Dispatchers.IO) {
        val downloadLink = getDownloadLink(usenetId, file.id)

        val contents = DebridFileContents().apply {
            originalPath = file.name
            size = file.size
            modified = Instant.now().toEpochMilli()
            mimeType = file.mimeType ?: "application/octet-stream"
        }

        fileService.createDebridFile(
            "${debridavConfigurationProperties.downloadPath}/$releaseName/${file.name}",
            hash,
            contents
        )
    }

    private fun getDownloadLink(usenetId: Long, fileId: String): String {
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
        val usenetDownload = UsenetDownload()
        usenetDownload.status = UsenetDownloadStatus.QUEUED
        usenetDownload.name = releaseName
        usenetDownload.hash = hash
        usenetDownload.storagePath =
            "${debridavConfigurationProperties.mountPath}${debridavConfigurationProperties.downloadPath}/$releaseName"
        usenetDownload.percentCompleted = 0.0
        usenetDownload.size = 0
        usenetRepository.save(usenetDownload)
    }

    private fun getBaseUrl(): String = "${torBoxConfiguration.baseUrl}/${torBoxConfiguration.version}"
}
