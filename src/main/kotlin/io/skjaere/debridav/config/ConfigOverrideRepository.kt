package io.skjaere.debridav.config

import org.springframework.data.jpa.repository.JpaRepository

interface ConfigOverrideRepository : JpaRepository<ConfigOverride, Long> {
    fun findByPropKey(key: String): ConfigOverride?
    fun findAllByPropKeyIn(keys: Collection<String>): List<ConfigOverride>
    fun findAllByPropKeyStartingWith(prefix: String): List<ConfigOverride>
    fun deleteAllByPropKeyStartingWith(prefix: String)
}
