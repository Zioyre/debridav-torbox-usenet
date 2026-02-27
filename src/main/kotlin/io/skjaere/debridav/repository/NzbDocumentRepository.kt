package io.skjaere.debridav.repository

import io.skjaere.debridav.usenet.nzb.NzbDocumentEntity
import org.springframework.data.repository.CrudRepository
import java.time.Instant

interface NzbDocumentRepository : CrudRepository<NzbDocumentEntity, Long> {
    fun findByLastVerifiedIsNullOrLastVerifiedBefore(cutoff: Instant): List<NzbDocumentEntity>
}
