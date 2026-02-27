package io.skjaere.debridav.arrs.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.skjaere.debridav.arrs.RadarrConfigurationProperties
import io.skjaere.debridav.arrs.client.models.radarr.RadarrParseResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component

@Component
@ConditionalOnExpression("\${radarr.integration-enabled:true}")
class RadarrApiClient(
    private val httpClient: HttpClient,
    private val radarrConfigurationProperties: RadarrConfigurationProperties
) : BaseArrClient by DefaultBaseArrClient(httpClient, radarrConfigurationProperties),
    ArrClient {
    private val logger = LoggerFactory.getLogger(RadarrApiClient::class.java)

    override suspend fun getItemIdFromName(name: String): Long {
        return parse(name).body<RadarrParseResponse>().movie.id
    }

    override fun getCategory(): String = radarrConfigurationProperties.category

    override suspend fun deleteFileAndSearch(name: String) {
        val parseResponse = parse(name).body<RadarrParseResponse>()
        val movie = parseResponse.movie

        if (movie.movieFileId > 0) {
            logger.info("Deleting movie file {} for '{}'", movie.movieFileId, name)
            val deleteResponse = httpClient.delete(
                "${radarrConfigurationProperties.getApiBaseUrl()}/moviefile/${movie.movieFileId}"
            ) {
                accept(ContentType.Application.Json)
                header("X-Api-Key", radarrConfigurationProperties.apiKey)
            }
            if (!deleteResponse.status.isSuccess()) {
                logger.error(
                    "Failed to delete movie file {} for '{}': {} {}",
                    movie.movieFileId,
                    name,
                    deleteResponse.status,
                    deleteResponse.bodyAsText()
                )
            }
        }

        logger.info("Triggering MoviesSearch for movie {} for '{}'", movie.id, name)
        val searchResponse = httpClient.post("${radarrConfigurationProperties.getApiBaseUrl()}/command") {
            accept(ContentType.Application.Json)
            header("X-Api-Key", radarrConfigurationProperties.apiKey)
            contentType(ContentType.Application.Json)
            setBody("""{"name": "MoviesSearch", "movieIds": [${movie.id}]}""")
        }
        if (!searchResponse.status.isSuccess()) {
            logger.error(
                "Failed to trigger MoviesSearch for '{}': {} {}",
                name,
                searchResponse.status,
                searchResponse.bodyAsText()
            )
        }
    }
}
