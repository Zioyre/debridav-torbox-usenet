package io.skjaere.debridav.arrs

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "sonarr")
data class SonarrConfigurationProperties(
    override val integrationEnabled: Boolean,
    override val host: String,
    override val port: Int = 8989,
    override val apiBasePath: String = "/api/v3",
    override val apiKey: String,
    override val category: String,
): ArrConfiguration
