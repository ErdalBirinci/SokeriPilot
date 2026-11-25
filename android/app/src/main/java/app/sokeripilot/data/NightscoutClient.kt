package app.sokeripilot.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Nightscout REST client.
 *
 * Security decisions:
 * - The URL is re-validated on every request (the settings file may have been tampered with).
 * - Redirects are not followed; 3xx responses are rejected (api-secret must not leak to another host).
 * - Response size is limited.
 * - Error messages contain no URL or key.
 */
class NightscoutClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
    private val maxResponseBytes: Int = 512 * 1024
) {

    suspend fun fetchLatest(config: NightscoutConfig, count: Int = 24): List<GlucoseReading> =
        withContext(Dispatchers.IO) {
            val validated = NightscoutSecurity.validateBaseUrl(config.baseUrl)
            if (validated !is UrlValidation.Valid) throw SourceException("Nightscout URL is invalid.")
            if (!NightscoutSecurity.isValidDigest(config.apiSecretSha1)) {
                throw SourceException("Nightscout key is invalid.")
            }

            val safeCount = count.coerceIn(1, NightscoutEntryParser.MAX_ENTRIES)
            val url = try {
                URL("${validated.normalizedBaseUrl}/api/v1/entries/sgv.json?count=$safeCount")
            } catch (_: MalformedURLException) {
                throw SourceException("Nightscout URL is invalid.")
            }

            val connection = url.openConnection() as? HttpURLConnection
                ?: throw SourceException("Could not establish a connection.")
            connection.apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "SokeriPilot/1.0")
                setRequestProperty(NightscoutConfig.SECRET_HEADER, config.apiSecretSha1)
            }

            try {
                val code = connection.responseCode
                when {
                    code in 300..399 -> throw SourceException("Nightscout redirect was rejected for security reasons.")
                    code == 401 || code == 403 -> throw SourceException("Nightscout key was rejected.")
                    code != 200 -> throw SourceException("Nightscout returned $code.")
                }
                val body = readLimited(connection.inputStream, maxResponseBytes)
                NightscoutEntryParser.parse(body)
            } catch (error: SourceException) {
                throw error
            } catch (_: IOException) {
                throw SourceException("Could not connect to Nightscout.")
            } finally {
                connection.disconnect()
            }
        }

    private fun readLimited(stream: InputStream, limit: Int): String {
        val buffer = ByteArray(8 * 1024)
        val output = ByteArrayOutputStream()
        var total = 0
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > limit) throw SourceException("Nightscout response is too large.")
                output.write(buffer, 0, read)
            }
        }
        return output.toString("UTF-8")
    }
}
