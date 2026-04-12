package io.skjaere.debridav.ui

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/ui-config")
class UiConfigController(
    private val uiConfig: UiConfigurationProperties,
) {
    @GetMapping
    fun getUiConfig(): ResponseEntity<UiConfigDto> {
        val grafana = uiConfig.grafana
            .takeIf { it.baseUrl.isNotBlank() }
            ?.let { cfg ->
                GrafanaDto(
                    baseUrl = cfg.baseUrl.trimEnd('/'),
                    dashboards = cfg.dashboards.map { DashboardDto(it.label, it.path) },
                )
            }
        return ResponseEntity.ok(UiConfigDto(grafana = grafana))
    }
}

data class UiConfigDto(val grafana: GrafanaDto?)
data class GrafanaDto(val baseUrl: String, val dashboards: List<DashboardDto>)
data class DashboardDto(val label: String, val path: String)
