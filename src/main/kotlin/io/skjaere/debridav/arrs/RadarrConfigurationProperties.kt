package io.skjaere.debridav.arrs

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "radarr")
data class RadarrConfigurationProperties(
    override val integrationEnabled: Boolean,
    override val host: String,
    override val port: Int = 7878,
    override val apiBasePath: String = "/api/v3",
    override val apiKey: String,
    override val category: String,
    ): ArrConfiguration

