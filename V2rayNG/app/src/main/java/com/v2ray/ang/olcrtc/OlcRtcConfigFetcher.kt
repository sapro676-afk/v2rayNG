package com.v2ray.ang.olcrtc

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object OlcRtcConfigFetcher {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val FETCH_TIMEOUT_MS = 12_000L

    fun fetchIfConfigured(config: OlcRtcConfig, log: (String) -> Unit): OlcRtcConfig {
        if (config.configUrl.isBlank()) return config

        log("fetching olcRTC room lease from ${redactUrl(config.configUrl)}")
        val response = fetchOnWorker(config.configUrl, config.configToken)
        val override = OlcRtcConfig.parseOverride(response)
            ?: error("olcRTC broker response does not contain room config")
        val resolved = config.apply(override).normalized()

        if (!resolved.isComplete()) {
            error("olcRTC broker response is missing room_id or key")
        }

        log(
            "fetched olcRTC room lease; roomConfigured=${resolved.roomId.isNotBlank()}; " +
                "clientId=${resolved.clientId}; provider=${resolved.provider}; transport=${resolved.transport}"
        )
        return resolved
    }

    private fun fetchOnWorker(url: String, token: String): String {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "olcrtc-config-fetch").apply { isDaemon = true }
        }
        val future = executor.submit<String> { fetch(url, token) }
        return try {
            future.get(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw IllegalStateException("timed out fetching olcRTC broker config", e)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun fetch(urlText: String, token: String): String {
        val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json, text/plain;q=0.9, */*;q=0.1")
            setRequestProperty("User-Agent", "v2rayNG-olcrtc")
            if (token.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                error("olcRTC broker returned HTTP $status: ${body.take(300)}")
            }
            body.ifBlank { error("olcRTC broker returned an empty response") }
        } finally {
            connection.disconnect()
        }
    }

    private fun redactUrl(url: String): String {
        return try {
            val uri = URI(url)
            URI(uri.scheme, uri.authority, uri.path, null, null).toString()
        } catch (_: Exception) {
            url.substringBefore('?')
        }
    }
}
