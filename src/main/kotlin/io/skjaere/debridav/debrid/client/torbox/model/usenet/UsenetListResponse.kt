package io.skjaere.debridav.debrid.client.torbox.model.usenet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UsenetListResponse(
    val success: Boolean,
    val error: String? = null,
    val detail: String? = null,
    val data: List<UsenetListItem>? = null
)

@Serializable
data class UsenetListItem(
    val id: Long,
    val hash: String? = null,
    val name: String? = null,
    @SerialName("download_state") val downloadState: String? = null,
    val size: Long? = null,
    val progress: Double? = null,
    val cached: Boolean? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val files: List<UsenetListItemFile>? = listOf()
)

@Serializable
data class UsenetListItemFile(
    val id: Int,
    val name: String,
    val size: Long,
    @SerialName("mimetype") val mimeType: String? = null,
    @SerialName("short_name") val shortName: String? = null,
    @SerialName("s3_path") val s3Path: String? = null,
    @SerialName("absolute_path") val absolutePath: String? = null
)
