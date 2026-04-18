package io.skjaere.debridav.debrid


import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.repository.LibraryStats
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class LibraryMetricsService(
    private val debridFileContentsRepository: DebridFileContentsRepository,
    meterRegistry: MeterRegistry,
) {

    private val cachedStatusGauge = MultiGauge
        .builder("debridav.library.metrics")
        .description("Metrics for library files")
        .register(meterRegistry)

    private val librarySizeGauge = MultiGauge
        .builder("debridav.library.size")
        .description("Metrics for library files")
        .register(meterRegistry)

    @Scheduled(fixedRate = 60000)
    fun recordLibraryMetrics() {
        val numberOfTorrentEntities = debridFileContentsRepository.numberOfRemotelyCachedTorrentEntities()
        val numberOfUsenetEntities = debridFileContentsRepository.numberOfRemotelyCachedUsenetEntities()

        librarySizeGauge.register(
            listOf(
                MultiGauge.Row.of(Tags.of("source", "torrent"), numberOfTorrentEntities.toDouble()),
                MultiGauge.Row.of(Tags.of("source", "usenet"), numberOfUsenetEntities.toDouble()),
            ),
            true,
        )

        val perProviderStats = debridFileContentsRepository.getLibraryMetricsTorrents()
            .toLibraryTorrentStats(numberOfTorrentEntities)
        cachedStatusGauge.register(
            perProviderStats.map {
                MultiGauge.Row.of(
                    Tags.of("provider", it.provider, "type", it.type),
                    it.count.toDouble(),
                )
            },
            true,
        )
    }

    fun List<Map<String, Any>>.toLibraryTorrentStats(numberOfTotalEntities: Long): List<LibraryStats> {
        return this.map {
            LibraryStats(
                (it["provider"] as String).replace("\"", ""),
                (it["type"] as String).replace("\"", ""),
                (it["count"] as Long)
            )
        }.groupBy { it.provider }
            .mapValues { entry ->
                if (entry.value.sumOf { it.count } < numberOfTotalEntities) {
                    val mutableEntryValue = entry.value.toMutableList()
                    mutableEntryValue.add(
                        LibraryStats(
                            entry.key,
                            "Unknown",
                            numberOfTotalEntities - entry.value.sumOf { it.count }
                        )
                    )
                    mutableEntryValue
                } else entry.value.toMutableList()
            }.values.flatten()

    }
}
