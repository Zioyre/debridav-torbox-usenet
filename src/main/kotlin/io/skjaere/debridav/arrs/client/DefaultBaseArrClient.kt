package io.skjaere.debridav.arrs.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.skjaere.debridav.arrs.ArrConfiguration
import io.skjaere.debridav.arrs.client.models.HistoryResponse
import org.slf4j.LoggerFactory

open class DefaultBaseArrClient(
    private val httpClient: HttpClient,
    private val arrConfiguration: ArrConfiguration
) : BaseArrClient {
    private val logger = LoggerFactory.getLogger(DefaultBaseArrClient::class.java)

    override suspend fun parse(itemName: String): HttpResponse {
        val response = httpClient.get("${arrConfiguration.getApiBaseUrl()}/parse") {
            url {
                parameters.append("title", itemName)
                parameters.append("apiKey", arrConfiguration.apiKey)
            }
            accept(ContentType.Application.Json)
            header("X-Api-Key", arrConfiguration.apiKey)
        }
        if (!response.status.isSuccess()) {
            logger.error(
                "Parse request for '{}' failed with status {}: {}",
                itemName,
                response.status,
                response.bodyAsText()
            )
        }
        return response
    }

    override suspend fun blocklist(downloadId: String) {
        val historyResponse = httpClient.get("${arrConfiguration.getApiBaseUrl()}/history") {
            url {
                parameters.append("downloadId", downloadId)
                parameters.append("eventType", "1")
                parameters.append("pageSize", "1")
            }
            accept(ContentType.Application.Json)
            header("X-Api-Key", arrConfiguration.apiKey)
        }

        if (!historyResponse.status.isSuccess()) {
            logger.error(
                "History lookup for downloadId '{}' failed with status {}: {}",
                downloadId,
                historyResponse.status,
                historyResponse.bodyAsText()
            )
            return
        }

        val history = historyResponse.body<HistoryResponse>()
        val record = history.records.firstOrNull()
        if (record == null) {
            logger.warn("No grabbed history record found for downloadId '{}'", downloadId)
            return
        }

        val failResponse = httpClient.post(
            "${arrConfiguration.getApiBaseUrl()}/history/failed/${record.id}"
        ) {
            accept(ContentType.Application.Json)
            header("X-Api-Key", arrConfiguration.apiKey)
        }

        if (!failResponse.status.isSuccess()) {
            logger.error(
                "Failed to mark history record {} as failed for downloadId '{}': {} {}",
                record.id,
                downloadId,
                failResponse.status,
                failResponse.bodyAsText()
            )
        } else {
            logger.info(
                "Marked history record {} as failed (blocklisted) for downloadId '{}'",
                record.id,
                downloadId
            )
        }
    }
}
