package io.skjaere.debridav.recache

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/recache")
class ReCacheController(
    private val reCacheService: ReCacheService
) {
    private val logger = LoggerFactory.getLogger(ReCacheController::class.java)

    @PostMapping("/{entityId}")
    fun recache(@PathVariable entityId: Long): ResponseEntity<ReCacheService.ReCacheResult> = runBlocking {
        logger.info("Re-cache requested for entity $entityId")
        val result = reCacheService.recacheEntity(entityId)
        logger.info("Re-cache result for entity $entityId: status=${result.status} message=${result.message}")

        when (result.status) {
            "cached" -> ResponseEntity.ok(result)
            "recaching" -> ResponseEntity.accepted().body(result)
            "failed" -> ResponseEntity.status(502).body(result)
            else -> ResponseEntity.internalServerError().body(result)
        }
    }
}
