package io.skjaere.debridav.usenet.pgmq

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.vdsirotkin.pgmq.PgmqClient
import com.vdsirotkin.pgmq.config.PgmqConfiguration
import com.vdsirotkin.pgmq.config.PgmqConnectionFactory
import com.vdsirotkin.pgmq.serialization.JacksonPgmqSerializationProvider
import io.skjaere.debridav.usenet.NzbImportService
import io.skjaere.debridav.usenet.NzbImportTaskData
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import javax.sql.DataSource

@Suppress("MagicNumber")
@ConfigurationProperties(prefix = "pgmq")
class PgmqConfigurationProperties {
    var defaultVisibilityTimeout: Duration = Duration.ofMinutes(5)
    var importConcurrency: Int = 2
    var importVisibilityTimeout: Duration = Duration.ofMinutes(10)
    var importPollInterval: Duration = Duration.ofSeconds(2)
    var healthCheckConcurrency: Int = 1
    var healthCheckVisibilityTimeout: Duration = Duration.ofMinutes(5)
    var healthCheckPollInterval: Duration = Duration.ofSeconds(10)
    var healthRepairConcurrency: Int = 2
    var healthRepairVisibilityTimeout: Duration = Duration.ofMinutes(2)
    var healthRepairPollInterval: Duration = Duration.ofSeconds(5)
}

@Configuration
@ConditionalOnProperty("nntp.enabled", havingValue = "true")
class PgmqSpringConfiguration {

    @Bean
    fun pgmqConfiguration(props: PgmqConfigurationProperties): PgmqConfiguration =
        object : PgmqConfiguration {
            override val defaultVisibilityTimeout: java.time.Duration = props.defaultVisibilityTimeout
        }

    @Bean
    fun pgmqConnectionFactory(dataSource: DataSource): PgmqConnectionFactory = PgmqConnectionFactory {
        dataSource.connection
    }

    @Bean
    fun pgmqObjectMapper(): ObjectMapper =
        ObjectMapper().registerModule(KotlinModule.Builder().build())

    @Bean
    fun pgmqSerializationProvider(pgmqObjectMapper: ObjectMapper): JacksonPgmqSerializationProvider =
        JacksonPgmqSerializationProvider(pgmqObjectMapper)

    @Bean
    fun pgmqClient(
        connectionFactory: PgmqConnectionFactory,
        serializationProvider: JacksonPgmqSerializationProvider,
        configuration: PgmqConfiguration
    ): PgmqClient = PgmqClient(connectionFactory, serializationProvider, configuration)

    @Bean
    fun nzbImportConsumer(
        pgmqClient: PgmqClient,
        pgmqObjectMapper: ObjectMapper,
        props: PgmqConfigurationProperties,
        nzbImportService: NzbImportService
    ): PgmqConsumer<NzbImportMessage> = PgmqConsumer(
        pgmqClient = pgmqClient,
        objectMapper = pgmqObjectMapper,
        queueName = "nzb_import",
        messageType = NzbImportMessage::class.java,
        concurrency = props.importConcurrency,
        visibilityTimeout = props.importVisibilityTimeout,
        pollInterval = props.importPollInterval
    ) { msg ->
        nzbImportService.executeImport(
            NzbImportTaskData(msg.nzbBytesBase64, msg.usenetDownloadId)
        )
    }

    @Bean
    fun nzbHealthCheckConsumer(
        pgmqClient: PgmqClient,
        pgmqObjectMapper: ObjectMapper,
        props: PgmqConfigurationProperties,
        healthCheckHandler: NzbHealthCheckHandler
    ): PgmqConsumer<NzbHealthCheckMessage> = PgmqConsumer(
        pgmqClient = pgmqClient,
        objectMapper = pgmqObjectMapper,
        queueName = "nzb_health_check",
        messageType = NzbHealthCheckMessage::class.java,
        concurrency = props.healthCheckConcurrency,
        visibilityTimeout = props.healthCheckVisibilityTimeout,
        pollInterval = props.healthCheckPollInterval
    ) { msg ->
        healthCheckHandler.handle(msg)
    }

    @Bean
    fun nzbHealthRepairConsumer(
        pgmqClient: PgmqClient,
        pgmqObjectMapper: ObjectMapper,
        props: PgmqConfigurationProperties,
        healthRepairHandler: NzbHealthRepairHandler
    ): PgmqConsumer<NzbHealthRepairMessage> = PgmqConsumer(
        pgmqClient = pgmqClient,
        objectMapper = pgmqObjectMapper,
        queueName = "nzb_health_repair",
        messageType = NzbHealthRepairMessage::class.java,
        concurrency = props.healthRepairConcurrency,
        visibilityTimeout = props.healthRepairVisibilityTimeout,
        pollInterval = props.healthRepairPollInterval
    ) { msg ->
        healthRepairHandler.handle(msg)
    }
}
