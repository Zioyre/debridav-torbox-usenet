package io.skjaere.debridav.config.auth

import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Service
import java.util.Date
import javax.crypto.SecretKey

@Service
class JwtService(
    private val authConfig: AuthConfigurationProperties
) {
    private val key: SecretKey by lazy {
        Keys.hmacShaKeyFor(authConfig.jwtSecret.toByteArray())
    }

    fun generateToken(username: String): String {
        val now = Date()
        val expiration = Date(now.time + authConfig.tokenExpirationHours * 3600 * 1000)

        return Jwts.builder()
            .subject(username)
            .issuedAt(now)
            .expiration(expiration)
            .signWith(key)
            .compact()
    }

    fun validateTokenAndGetUsername(token: String): String? = try {
        Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
            .subject
    } catch (_: JwtException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
