package app.sokeripilot.data

import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.Locale

data class NightscoutConfig(
    val baseUrl: String,
    val apiSecretSha1: String
) {
    companion object {
        /** Header name expected by the Nightscout server. */
        const val SECRET_HEADER = "api-secret"
    }
}

sealed interface UrlValidation {
    data class Valid(val normalizedBaseUrl: String) : UrlValidation
    data class Invalid(val reason: String) : UrlValidation
}

/**
 * Security rules for Nightscout connections.
 *
 * - HTTPS only; http servers on a LAN are rejected.
 * - User info (https://user@host), query strings and fragments are rejected.
 * - The raw API key is never stored; only the SHA-1 digest required by the
 *   Nightscout protocol is kept. This is not a password-storage method; it is the
 *   protocol's shared-secret form.
 */
object NightscoutSecurity {

    private const val MAX_URL_LENGTH = 2048
    private val DIGEST_PATTERN = Regex("^[a-f0-9]{40}$")

    fun validateBaseUrl(raw: String): UrlValidation {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return UrlValidation.Invalid("Nightscout URL cannot be empty.")
        }
        if (trimmed.length > MAX_URL_LENGTH) {
            return UrlValidation.Invalid("URL is too long.")
        }
        if (trimmed.any { it.isWhitespace() || it.code < 0x20 }) {
            return UrlValidation.Invalid("URL contains invalid characters.")
        }
        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return UrlValidation.Invalid("URL could not be parsed.")
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return UrlValidation.Invalid("Only HTTPS URLs are supported.")
        }
        if (uri.isOpaque) {
            return UrlValidation.Invalid("URL is invalid.")
        }
        val host = uri.host
        if (host.isNullOrBlank()) {
            return UrlValidation.Invalid("URL is missing a hostname.")
        }
        if (host.startsWith(".") || host.endsWith(".")) {
            return UrlValidation.Invalid("Hostname is invalid.")
        }
        if (!uri.userInfo.isNullOrEmpty()) {
            return UrlValidation.Invalid("URL must not contain user info.")
        }
        if (!uri.query.isNullOrEmpty() || !uri.fragment.isNullOrEmpty()) {
            return UrlValidation.Invalid("URL must not contain a query or fragment.")
        }
        if (uri.port != -1 && uri.port !in 1..65535) {
            return UrlValidation.Invalid("Port is invalid.")
        }
        val path = uri.path ?: ""
        if (path.split('/').any { it == ".." }) {
            return UrlValidation.Invalid("URL path is invalid.")
        }
        return UrlValidation.Valid(trimmed.trimEnd('/'))
    }

    fun sha1Hex(secret: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(secret.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") {
            String.format(Locale.US, "%02x", it.toInt() and 0xFF)
        }
    }

    fun isValidDigest(value: String): Boolean = DIGEST_PATTERN.matches(value)
}
