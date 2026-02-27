package io.skjaere.debridav.arrs.client.models

import kotlinx.serialization.Serializable

@Serializable
data class HistoryResponse(
    val page: Int,
    val pageSize: Int,
    val totalRecords: Int,
    val records: List<HistoryRecord>
)

@Serializable
data class HistoryRecord(
    val id: Long,
    val sourceTitle: String? = null,
    val downloadId: String? = null,
    val eventType: String? = null
)
