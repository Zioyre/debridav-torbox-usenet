package io.skjaere.debridav.usenet.pgmq

data class NzbImportMessage(
    val nzbBytesBase64: String,
    val usenetDownloadId: Long
)

data class NzbHealthCheckMessage(
    val nzbDocumentId: Long
)

data class NzbHealthRepairMessage(
    val nzbDocumentId: Long,
    val message: String
)
