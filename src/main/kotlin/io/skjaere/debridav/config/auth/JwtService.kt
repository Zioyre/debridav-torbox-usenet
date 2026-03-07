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

    @Suppress("MagicNumber")
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

    @Suppress("MagicNumber")
    fun generateStreamToken(path: String): String {
        val now = Date()
        val expiration = Date(now.time + STREAM_TOKEN_EXPIRY_SECONDS * 1000)

        return Jwts.builder()
            .subject(path)
            .claim("type", "stream")
            .issuedAt(now)
            .expiration(expiration)
            .signWith(key)
            .compact()
    }

    fun validateStreamToken(token: String): String? = try {
        val claims = Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
        if (claims["type"] == "stream") claims.subject else null
    } catch (_: JwtException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        const val STREAM_TOKEN_EXPIRY_SECONDS = 86400L // 24 hours
    }
}
