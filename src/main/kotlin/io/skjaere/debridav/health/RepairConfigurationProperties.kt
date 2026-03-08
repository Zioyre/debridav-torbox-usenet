package io.skjaere.debridav.health

import io.skjaere.debridav.config.ConfigProperty
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "repair")
class RepairConfigurationProperties {
    @ConfigProperty(name = "Enabled", description = "Enable automatic repair of unhealthy torrents and NZBs")
    var enabled: Boolean = true
}
