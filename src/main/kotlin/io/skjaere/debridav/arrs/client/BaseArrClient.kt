package io.skjaere.debridav.arrs.client

import io.ktor.client.statement.HttpResponse

sealed interface BaseArrClient {
    suspend fun parse(itemName: String): HttpResponse
    suspend fun blocklist(downloadId: String)
}
