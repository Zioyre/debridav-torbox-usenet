package io.skjaere.debridav.usenet

import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@Endpoint(id = "nzbhealthcheck")
@ConditionalOnProperty("nntp.enabled", havingValue = "true")
class NzbHealthCheckActuatorEndpoint(
    private val nzbHealthCheckService: NzbHealthCheckService
) {
    @WriteOperation
    fun triggerHealthCheck() {
        nzbHealthCheckService.triggerFullHealthCheck()
    }
}
