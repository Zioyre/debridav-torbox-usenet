package io.skjaere.debridav.usenet

import io.skjaere.debridav.config.ConfigProperty
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.config.NntpConfig
import io.skjaere.nzbstreamer.config.SeekConfig
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Suppress("MagicNumber")
@ConfigurationProperties(prefix = "nntp")
class NntpConfigurationProperties {
    @ConfigProperty(name = "Enabled", description = "Enable NNTP")
    var enabled: Boolean = false
    @ConfigProperty(name = "Concurrency", description = "NNTP streaming concurrency")
    var concurrency: Int = 4
    var readAheadSegments: Int? = null
    @ConfigProperty(name = "Forward Threshold Bytes", description = "NNTP forward threshold bytes")
    var forwardThresholdBytes: Long = 102400L
    @ConfigProperty(name = "Health Check Interval", description = "NNTP health check interval")
    var healthCheckInterval: Duration = Duration.ofDays(7)
    @ConfigProperty(name = "Health Check Poll Rate", description = "NNTP health check poll rate")
    var healthCheckPollRate: Duration = Duration.ofMinutes(5)
    var pools: List<NntpPoolProperties> = emptyList()
}

@Configuration
class NzbStreamerConfiguration {
    private val logger = LoggerFactory.getLogger(NzbStreamerConfiguration::class.java)

    @Bean
    @ConditionalOnProperty("nntp.enabled", havingValue = "true")
    fun nzbStreamer(props: NntpConfigurationProperties): NzbStreamer {
        logger.info(
            "Creating NzbStreamer with host='{}', port={}, useTls={}, username='{}', concurrency={}, maxConnections={}",
            props.host, props.port, props.useTls, props.username, props.concurrency, props.maxConnections
        )
        return NzbStreamer.fromConfig(
            NntpConfig(
                host = props.host,
                port = props.port,
                username = props.username,
                password = props.password,
                useTls = props.useTls,
                concurrency = props.concurrency,
                maxConnections = props.maxConnections,
                readAheadSegments = props.readAheadSegments ?: props.concurrency
            ),
            SeekConfig(
                forwardThresholdBytes = props.forwardThresholdBytes
            )
        )
    }
}
