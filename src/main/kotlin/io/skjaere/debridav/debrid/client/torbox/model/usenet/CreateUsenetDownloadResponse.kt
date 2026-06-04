package io.skjaere.debridav.debrid.client.torbox.model.usenet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateUsenetDownloadResponse(
    val success: Boolean,
    val error: String? = null,
    val detail: String? = null,
    val data: CreatedUsenetDownload? = null
)

@Serializable
data class CreatedUsenetDownload(
    @SerialName("usenetdownload_id") val usenetDownloadId: Long,
    val name: String? = null,
    val hash: String? = null
)
