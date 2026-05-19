package com.v2ray.ang.olcrtc

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.util.JsonUtil

data class OlcRtcConfig(
    val provider: String = DEFAULT_PROVIDER,
    val transport: String = DEFAULT_TRANSPORT,
    val roomId: String = "",
    val clientId: String = DEFAULT_CLIENT_ID,
    val key: String = "",
    val link: String = DEFAULT_LINK,
    val socksHost: String = DEFAULT_SOCKS_HOST,
    val socksPort: Int = DEFAULT_SOCKS_PORT,
    val socksUser: String = "",
    val socksPass: String = "",
    val vp8Fps: Int = DEFAULT_VP8_FPS,
    val vp8Batch: Int = DEFAULT_VP8_BATCH
) {
    fun normalized(): OlcRtcConfig {
        val normalizedProvider = normalizeProvider(provider)
        return copy(
            provider = normalizedProvider,
            transport = normalizeTransport(transport, normalizedProvider),
            roomId = roomId.trim(),
            clientId = clientId.trim().ifBlank { DEFAULT_CLIENT_ID },
            key = key.trim(),
            link = link.trim().ifBlank { DEFAULT_LINK },
            socksHost = socksHost.trim().ifBlank { DEFAULT_SOCKS_HOST },
            socksPort = socksPort.takeIf { it in 1..65535 } ?: DEFAULT_SOCKS_PORT,
            socksUser = socksUser.trim(),
            socksPass = socksPass.trim(),
            vp8Fps = vp8Fps.coerceIn(1, 120),
            vp8Batch = vp8Batch.coerceIn(1, 64)
        )
    }

    fun isComplete(): Boolean = roomId.isNotBlank() && key.isNotBlank()

    fun apply(override: OlcRtcConfigOverride?): OlcRtcConfig {
        if (override == null) return this
        return copy(
            provider = override.provider ?: provider,
            transport = override.transport ?: transport,
            roomId = override.roomId ?: roomId,
            clientId = override.clientId ?: clientId,
            key = override.key ?: key,
            link = override.link ?: link,
            socksHost = override.socksHost ?: socksHost,
            socksPort = override.socksPort ?: socksPort,
            socksUser = override.socksUser ?: socksUser,
            socksPass = override.socksPass ?: socksPass,
            vp8Fps = override.vp8Fps ?: vp8Fps,
            vp8Batch = override.vp8Batch ?: vp8Batch
        )
    }

    companion object {
        const val MARKER = "olcrtc-socks"
        const val URI_PREFIX = "olcrtc://"

        const val PROVIDER_JAZZ = "jazz"
        const val PROVIDER_TELEMOST = "telemost"
        const val PROVIDER_WB_STREAM = "wbstream"
        const val PROVIDER_JITSI = "jitsi"
        const val DEFAULT_PROVIDER = PROVIDER_WB_STREAM

        const val TRANSPORT_DATACHANNEL = "datachannel"
        const val TRANSPORT_VP8CHANNEL = "vp8channel"
        const val TRANSPORT_SEICHANNEL = "seichannel"
        const val DEFAULT_TRANSPORT = TRANSPORT_VP8CHANNEL

        const val DEFAULT_LINK = "direct"
        const val DEFAULT_CLIENT_ID = "v2rayng-android"
        const val DEFAULT_SOCKS_HOST = "127.0.0.1"
        const val DEFAULT_SOCKS_PORT = 18080
        const val DEFAULT_VP8_FPS = 60
        const val DEFAULT_VP8_BATCH = 64

        fun fromBuildConfig(): OlcRtcConfig {
            return OlcRtcConfig(
                provider = BuildConfig.OLCRTC_CARRIER.ifBlank { DEFAULT_PROVIDER },
                transport = BuildConfig.OLCRTC_TRANSPORT.ifBlank { DEFAULT_TRANSPORT },
                roomId = BuildConfig.OLCRTC_ROOM_ID,
                clientId = BuildConfig.OLCRTC_CLIENT_ID.ifBlank { DEFAULT_CLIENT_ID },
                key = BuildConfig.OLCRTC_KEY,
                link = BuildConfig.OLCRTC_LINK.ifBlank { DEFAULT_LINK }
            ).normalized()
        }

        fun resolve(raw: String?): OlcRtcConfig {
            return fromBuildConfig()
                .apply(parseUri(raw))
                .apply(parseJsonMetadata(raw))
                .apply(parseSocksOutbound(raw))
                .normalized()
        }

        fun normalizeProvider(value: String): String {
            return when (value.trim().lowercase()) {
                PROVIDER_JAZZ, "sberjazz", "sber_jazz" -> PROVIDER_JAZZ
                PROVIDER_TELEMOST, "yandex", "yandex_telemost" -> PROVIDER_TELEMOST
                PROVIDER_WB_STREAM, "wbstream", "wb-stream", "wildberries" -> PROVIDER_WB_STREAM
                PROVIDER_JITSI, "jitsi-meet", "jitsi_meet", "meet" -> PROVIDER_JITSI
                else -> DEFAULT_PROVIDER
            }
        }

        fun normalizeTransport(value: String, provider: String = DEFAULT_PROVIDER): String {
            val normalized = when (value.trim().lowercase()) {
                TRANSPORT_DATACHANNEL, "data", "dc" -> TRANSPORT_DATACHANNEL
                TRANSPORT_VP8CHANNEL, "vp8", "video_vp8", "video-vp8" -> TRANSPORT_VP8CHANNEL
                TRANSPORT_SEICHANNEL, "sei", "sei_channel", "sei-channel", "h264_sei" -> TRANSPORT_SEICHANNEL
                else -> DEFAULT_TRANSPORT
            }
            val supported = when (normalizeProvider(provider)) {
                PROVIDER_TELEMOST -> setOf(TRANSPORT_VP8CHANNEL, TRANSPORT_SEICHANNEL)
                PROVIDER_JITSI -> setOf(TRANSPORT_DATACHANNEL)
                else -> setOf(TRANSPORT_DATACHANNEL, TRANSPORT_VP8CHANNEL, TRANSPORT_SEICHANNEL)
            }
            return normalized.takeIf { it in supported } ?: supported.first()
        }

        private fun parseUri(raw: String?): OlcRtcConfigOverride? {
            val input = raw ?: return null
            val start = input.indexOf(URI_PREFIX)
            if (start < 0) return null
            val uri = input.substring(start)
                .takeWhile { !it.isWhitespace() && it != '"' && it != '\'' && it != '\\' }
                .removeSuffix(",")
                .removeSuffix("}")
            val payload = uri.removePrefix(URI_PREFIX)

            val transportMarker = payload.indexOf('?')
            val roomMarker = payload.indexOf('@', startIndex = transportMarker + 1)
            val keyMarker = payload.indexOf('#', startIndex = roomMarker + 1)
            if (transportMarker <= 0 || roomMarker <= transportMarker || keyMarker <= roomMarker) {
                return null
            }

            val clientMarker = payload.indexOf('%', startIndex = keyMarker + 1).takeIf { it >= 0 }
            val nameMarker = payload.indexOf('$', startIndex = keyMarker + 1).takeIf { it >= 0 }
            val keyEnd = listOfNotNull(clientMarker, nameMarker).minOrNull() ?: payload.length

            val (transport, options) = parseTransportToken(
                payload.substring(transportMarker + 1, roomMarker).trim()
            )
            val clientId = clientMarker?.let { marker ->
                val end = nameMarker?.takeIf { it > marker } ?: payload.length
                payload.substring(marker + 1, end).trim().ifBlank { null }
            }

            return OlcRtcConfigOverride(
                provider = payload.substring(0, transportMarker).trim().ifBlank { null },
                transport = transport.ifBlank { null },
                roomId = payload.substring(roomMarker + 1, keyMarker).trim().ifBlank { null },
                key = payload.substring(keyMarker + 1, keyEnd).trim().ifBlank { null },
                clientId = clientId,
                vp8Fps = options["vp8-fps"] ?: options["fps"],
                vp8Batch = options["vp8-batch"] ?: options["batch"]
            )
        }

        private fun parseJsonMetadata(raw: String?): OlcRtcConfigOverride? {
            val root = raw?.asJsonObjectOrNull() ?: return null
            val olcrtc = root.get("olcrtc")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            return OlcRtcConfigOverride(
                provider = olcrtc.string("provider", "bypass_provider", "carrier"),
                transport = olcrtc.string("transport"),
                roomId = olcrtc.string("room_id", "roomId", "id", "server"),
                clientId = olcrtc.string("client_id", "clientId"),
                key = olcrtc.string("key", "password"),
                link = olcrtc.string("link"),
                socksHost = olcrtc.string("socks_host", "socksHost"),
                socksPort = olcrtc.int("socks_port", "socksPort"),
                socksUser = olcrtc.string("socks_user", "socksUser", "user", "username"),
                socksPass = olcrtc.string("socks_pass", "socksPass", "pass", "password"),
                vp8Fps = olcrtc.int("vp8_fps", "vp8Fps"),
                vp8Batch = olcrtc.int("vp8_batch", "vp8Batch")
            )
        }

        private fun parseSocksOutbound(raw: String?): OlcRtcConfigOverride? {
            val root = raw?.asJsonObjectOrNull() ?: return null
            val outbounds = root.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            val outbound = outbounds.firstObjectOrNull { item ->
                item.string("tag") == MARKER || item.string("tag")?.contains(MARKER, ignoreCase = true) == true
            } ?: return null
            val settings = outbound.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val direct = OlcRtcConfigOverride(
                socksHost = settings.string("address"),
                socksPort = settings.int("port"),
                socksUser = settings.string("user"),
                socksPass = settings.string("pass")
            )
            val server = settings.get("servers")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.firstObjectOrNull()
                ?: return direct.takeIf { it.hasSocksValue() }
            val user = server.get("users")?.takeIf { it.isJsonArray }?.asJsonArray?.firstObjectOrNull()
            return direct.copy(
                socksHost = direct.socksHost ?: server.string("address"),
                socksPort = direct.socksPort ?: server.int("port"),
                socksUser = direct.socksUser ?: user?.string("user"),
                socksPass = direct.socksPass ?: user?.string("pass")
            ).takeIf { it.hasSocksValue() }
        }

        private fun parseTransportToken(token: String): Pair<String, Map<String, Int>> {
            val optionsStart = token.indexOf('<')
            val optionsEnd = token.lastIndexOf('>')
            if (optionsStart < 0 || optionsEnd <= optionsStart) return token to emptyMap()
            val transport = token.substring(0, optionsStart).trim()
            val options = token.substring(optionsStart + 1, optionsEnd)
                .split('&')
                .mapNotNull { part ->
                    val separator = part.indexOf('=')
                    if (separator <= 0) return@mapNotNull null
                    val key = part.substring(0, separator).trim().lowercase()
                    val value = part.substring(separator + 1).trim().toIntOrNull() ?: return@mapNotNull null
                    key to value
                }
                .toMap()
            return transport to options
        }

        private fun String.asJsonObjectOrNull(): JsonObject? {
            return JsonUtil.parseString(this)?.takeIf { it.isJsonObject }?.asJsonObject
        }

        private fun JsonObject.string(vararg names: String): String? {
            return names.firstNotNullOfOrNull { name ->
                val primitive = get(name) as? JsonPrimitive ?: return@firstNotNullOfOrNull null
                primitive.asString.trim().ifBlank { null }
            }
        }

        private fun JsonObject.int(vararg names: String): Int? {
            return names.firstNotNullOfOrNull { name ->
                val primitive = get(name) as? JsonPrimitive ?: return@firstNotNullOfOrNull null
                primitive.asString.trim().toIntOrNull()
            }
        }

        private fun JsonArray.firstObjectOrNull(predicate: (JsonObject) -> Boolean = { true }): JsonObject? {
            return firstOrNull { it.isJsonObject && predicate(it.asJsonObject) }?.asJsonObject
        }
    }
}

data class OlcRtcConfigOverride(
    val provider: String? = null,
    val transport: String? = null,
    val roomId: String? = null,
    val clientId: String? = null,
    val key: String? = null,
    val link: String? = null,
    val socksHost: String? = null,
    val socksPort: Int? = null,
    val socksUser: String? = null,
    val socksPass: String? = null,
    val vp8Fps: Int? = null,
    val vp8Batch: Int? = null
) {
    fun hasSocksValue(): Boolean {
        return socksHost != null || socksPort != null || socksUser != null || socksPass != null
    }
}
