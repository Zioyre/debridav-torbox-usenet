package io.skjaere.debridav.health

import io.skjaere.debridav.config.ConfigProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "health-check")
class HealthCheckConfigurationProperties {
    @ConfigProperty(name = "Repair Enabled", description = "Enable automatic repair of unhealthy torrents and NZBs")
    var repairEnabled: Boolean = true

    @ConfigProperty(name = "Torrent Interval", description = "How often to reverify torrent availability")
    var torrentInterval: Duration = Duration.ofDays(1)

    @ConfigProperty(name = "Torrent Poll Rate", description = "How often to poll for torrents needing health checks")
    var torrentPollRate: Duration = Duration.ofMinutes(5)

    @ConfigProperty(name = "NZB Interval", description = "How often to reverify NZB segments")
    var nzbInterval: Duration = Duration.ofDays(7)

    @ConfigProperty(name = "NZB Poll Rate", description = "How often to poll for NZBs needing health checks")
    var nzbPollRate: Duration = Duration.ofMinutes(5)
}
