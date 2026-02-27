package io.skjaere.debridav.usenet

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
data class NntpConfigurationProperties(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 563,
    val username: String = "",
    val password: String = "",
    val useTls: Boolean = true,
    val concurrency: Int = 4,
    val maxConnections: Int = 8,
    val readAheadSegments: Int? = null,
    val forwardThresholdBytes: Long = 102400L,
    val healthCheckInterval: Duration = Duration.ofDays(7),
    val healthCheckPollRate: Duration = Duration.ofMinutes(5)
)

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
