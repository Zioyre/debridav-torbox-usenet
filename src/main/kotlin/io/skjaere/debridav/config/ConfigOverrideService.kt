package io.skjaere.debridav.config

import io.skjaere.debridav.usenet.NntpConfigurationProperties
import io.skjaere.debridav.usenet.NntpPoolProperties
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.config.NntpConfig
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ConfigOverrideService(
    private val repository: ConfigOverrideRepository,
    private val environment: Environment,
    private val registry: ConfigPropertyRegistry,
    private val nntpConfig: NntpConfigurationProperties,
    private val nzbStreamer: NzbStreamer? = null
) {
    private val logger = LoggerFactory.getLogger(ConfigOverrideService::class.java)
    companion object {
        private const val MASKED = "***"
        private const val POOL_PREFIX = "nntp.pools["
    }

    fun listAll(): List<ConfigOverrideDto> {
        val overrides = repository.findAllByPropKeyIn(registry.properties.keys)
            .associateBy { it.propKey }

        return registry.properties.map { (key, meta) ->
            val override = overrides[key]
            val defaultValue = getDefaultValue(key)
            val effectiveValue = override?.propValue ?: defaultValue

            ConfigOverrideDto(
                key = key,
                name = meta.name,
                effectiveValue = if (meta.sensitive) effectiveValue?.let { MASKED } else effectiveValue,
                defaultValue = if (meta.sensitive) defaultValue?.let { MASKED } else defaultValue,
                hasOverride = override != null,
                sensitive = meta.sensitive,
                group = meta.group,
                description = meta.description,
                type = meta.type,
                advanced = meta.advanced
            )
        }
    }

    fun getEffective(key: String): ConfigOverrideDto {
        val meta = registry.getMeta(key)
            ?: throw KeyNotWhitelistedException(key)

        val override = repository.findByPropKey(key)
        val defaultValue = getDefaultValue(key)
        val effectiveValue = override?.propValue ?: defaultValue

        return ConfigOverrideDto(
            key = key,
            name = meta.name,
            effectiveValue = if (meta.sensitive) effectiveValue?.let { MASKED } else effectiveValue,
            defaultValue = if (meta.sensitive) defaultValue?.let { MASKED } else defaultValue,
            hasOverride = override != null,
            sensitive = meta.sensitive,
            group = meta.group,
            description = meta.description,
            type = meta.type,
            advanced = meta.advanced
        )
    }

    fun upsert(key: String, value: String?): ConfigOverrideDto {
        val meta = registry.getMeta(key)
            ?: throw KeyNotWhitelistedException(key)

        val now = Instant.now()
        val entity = repository.findByPropKey(key) ?: ConfigOverride().apply {
            propKey = key
            createdAt = now
        }
        entity.propValue = value
        entity.sensitive = meta.sensitive
        entity.updatedAt = now
        repository.save(entity)

        refreshEnvironment()

        return getEffective(key)
    }

    fun delete(key: String): ConfigOverrideDto {
        if (!registry.isWhitelisted(key)) {
            throw KeyNotWhitelistedException(key)
        }

        val override = repository.findByPropKey(key)
            ?: throw OverrideNotFoundException(key)
        repository.delete(override)

        refreshEnvironment()

        return getEffective(key)
    }

    fun getNntpPools(): List<NntpPoolDto> {
        val overrides = repository.findAllByPropKeyStartingWith(POOL_PREFIX)
        val pools = if (overrides.isEmpty()) {
            nntpConfig.pools.map { it.toDto() }
        } else {
            parsePoolOverrides(overrides)
        }
        return pools.sortedBy { it.priority }
    }

    @Transactional
    fun saveNntpPools(pools: List<NntpPoolDto>) {
        repository.deleteAllByPropKeyStartingWith(POOL_PREFIX)
        val now = Instant.now()
        pools.forEachIndexed { i, pool ->
            val entries = mapOf(
                "nntp.pools[$i].host" to pool.host,
                "nntp.pools[$i].port" to pool.port.toString(),
                "nntp.pools[$i].username" to pool.username,
                "nntp.pools[$i].password" to pool.password,
                "nntp.pools[$i].use-tls" to pool.useTls.toString(),
                "nntp.pools[$i].max-connections" to pool.maxConnections.toString(),
                "nntp.pools[$i].priority" to pool.priority.toString()
            )
            for ((key, value) in entries) {
                val entity = ConfigOverride().apply {
                    propKey = key
                    propValue = value
                    sensitive = key.endsWith(".password")
                    createdAt = now
                    updatedAt = now
                }
                repository.save(entity)
            }
        }
        syncRunningPools(pools)
    }

    private fun syncRunningPools(saved: List<NntpPoolDto>) {
        if (nzbStreamer == null) return
        val savedConfigs = saved.map { it.toNntpConfig() }.toSet()
        val runningConfigs = nzbStreamer.getPoolConfigs().toSet()

        val toRemove = runningConfigs - savedConfigs
        val toAdd = savedConfigs - runningConfigs

        toRemove.forEach { nzbStreamer.removePool(it) }
        toAdd.forEach { nzbStreamer.addPool(it) }

        if (toRemove.isNotEmpty() || toAdd.isNotEmpty()) {
            logger.info("Synced NNTP pools: removed={}, added={}", toRemove.size, toAdd.size)
        }
    }

    private fun NntpPoolDto.toNntpConfig() = NntpConfig(
        host = host,
        port = port,
        username = username,
        password = password,
        useTls = useTls,
        maxConnections = maxConnections,
        priority = priority
    )

    private fun parsePoolOverrides(overrides: List<ConfigOverride>): List<NntpPoolDto> {
        val poolMap = mutableMapOf<Int, MutableMap<String, String>>()
        val regex = Regex("""nntp\.pools\[(\d+)]\.(.+)""")
        for (ov in overrides) {
            val match = regex.matchEntire(ov.propKey) ?: continue
            val index = match.groupValues[1].toInt()
            val field = match.groupValues[2]
            poolMap.getOrPut(index) { mutableMapOf() }[field] = ov.propValue ?: ""
        }
        return poolMap.toSortedMap().map { (_, fields) ->
            NntpPoolDto(
                host = fields["host"] ?: "",
                port = fields["port"]?.toIntOrNull() ?: 563,
                username = fields["username"] ?: "",
                password = fields["password"] ?: "",
                useTls = fields["use-tls"]?.toBooleanStrictOrNull() ?: true,
                maxConnections = fields["max-connections"]?.toIntOrNull() ?: 8,
                priority = fields["priority"]?.toIntOrNull() ?: 0
            )
        }
    }

    private fun NntpPoolProperties.toDto() = NntpPoolDto(
        host = host,
        port = port,
        username = username,
        password = password,
        useTls = useTls,
        maxConnections = maxConnections,
        priority = priority
    )
}

class KeyNotWhitelistedException(val key: String) :
    RuntimeException("Property key '$key' is not whitelisted for override")

class OverrideNotFoundException(val key: String) :
    RuntimeException("No override found for key '$key'")
