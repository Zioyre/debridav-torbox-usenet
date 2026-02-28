package io.skjaere.debridav.config

import org.springframework.data.repository.CrudRepository

interface ConfigOverrideRepository : CrudRepository<ConfigOverride, Long> {
    fun findByPropKey(key: String): ConfigOverride?
    fun findAllByPropKeyIn(keys: Collection<String>): List<ConfigOverride>
}
