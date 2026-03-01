package io.skjaere.debridav.usenet.queue

import io.skjaere.debridav.repository.NzbImportRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service

@Service
class UsenetQueueService(private val nzbImportRepository: NzbImportRepository) {

    companion object {
        val PROCESSING_STATUSES = listOf(
            NzbImportStatus.IMPORTING
        )

        val PENDING_STATUSES = listOf(
            NzbImportStatus.QUEUED
        )

        val HISTORY_STATUSES = listOf(
            NzbImportStatus.COMPLETED,
            NzbImportStatus.FAILED,
            NzbImportStatus.ARTICLES_MISSING
        )

        private val ALLOWED_SORT_FIELDS = setOf("updatedAt", "name")
    }

    fun getQueueStatus(): QueueStatusResponse {
        val processing = nzbImportRepository.findByStatusInOrderByUpdatedAtDesc(PROCESSING_STATUSES)
            .map { it.toDto() }
        val pending = nzbImportRepository.findByStatusInOrderByIdAsc(PENDING_STATUSES)
            .map { it.toDto() }

        return QueueStatusResponse(
            processing = processing,
            pending = pending,
            history = emptyList()
        )
    }

    fun getHistory(page: Int, size: Int, search: String, sort: String, direction: String): HistoryPageResponse {
        val sortField = if (sort in ALLOWED_SORT_FIELDS) sort else "updatedAt"
        val sortDir = if (direction.equals("asc", ignoreCase = true)) Sort.Direction.ASC else Sort.Direction.DESC
        val pageResult = nzbImportRepository.findByStatusInAndNameSearch(
            HISTORY_STATUSES,
            search,
            PageRequest.of(page, size, Sort.by(sortDir, sortField))
        )
        return HistoryPageResponse(
            content = pageResult.content.map { it.toDto() },
            page = pageResult.number,
            size = pageResult.size,
            totalElements = pageResult.totalElements,
            totalPages = pageResult.totalPages,
            last = pageResult.isLast
        )
    }

    private fun NzbImportRecord.toDto() = QueueItemDto(
        id = id!!,
        name = name,
        status = status.name,
        size = size,
        errorMessage = errorMessage,
        updatedAt = updatedAt,
        createdAt = createdAt,
        archiveType = archiveType,
        files = files
    )
}
