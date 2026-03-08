package io.skjaere.debridav.usenet.pgmq

import io.skjaere.debridav.pgmq.PgmqConfigurationProperties
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Instant

@Service
class PgmqArchiveCleanupService(
    private val jdbc: JdbcTemplate,
    private val props: PgmqConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(PgmqArchiveCleanupService::class.java)

    companion object {
        private val QUEUE_NAMES = listOf(
            "nzb_import", "nzb_health_check", "nzb_health_repair",
            "torrent_health_check", "torrent_health_repair"
        )
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    fun cleanupArchivedMessages() {
        val cutoff = Instant.now().minus(props.archiveRetention)
        logger.debug("Cleaning up archived PGMQ messages older than {}", cutoff)

        var totalDeleted = 0L
        for (queueName in QUEUE_NAMES) {
            val deleted = jdbc.update(
                "DELETE FROM pgmq.a_$queueName WHERE archived_at < ?",
                Timestamp.from(cutoff)
            )
            if (deleted > 0) {
                logger.info("Deleted {} archived messages from queue '{}'", deleted, queueName)
                totalDeleted += deleted
            }
        }

        if (totalDeleted > 0) {
            logger.info("Archive cleanup complete: {} total messages removed", totalDeleted)
        } else {
            logger.debug("Archive cleanup complete: no expired messages found")
        }
    }
}
