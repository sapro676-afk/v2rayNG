package com.v2ray.ang.olcrtc

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import mobile.LogWriter
import mobile.Mobile
import mobile.SocketProtector
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant

object OlcRtcManager {
    private const val TAG = AppConfig.TAG
    private const val SOCKS_START_TIMEOUT_MS = 30_000L
    private const val DIAG_FILE = "olcrtc.log"

    @Volatile
    private var lastSocksConnectError: String = ""

    @Volatile
    private var activeConfig: OlcRtcConfig? = null

    fun isRequired(guid: String): Boolean {
        val config = MmkvManager.decodeServerConfig(guid)
        val raw = MmkvManager.decodeServerRaw(guid).orEmpty()
        return raw.contains(OlcRtcConfig.MARKER) ||
            raw.contains(OlcRtcConfig.URI_PREFIX) ||
            raw.contains("\"olcrtc\"") ||
            config?.remarks?.contains("olcrtc", ignoreCase = true) == true
    }

    @Synchronized
    fun startIfRequired(
        context: Context,
        guid: String,
        protectSocket: ((Int) -> Boolean)? = null
    ) {
        if (!isRequired(guid)) {
            stop()
            return
        }
        if (runCatching { Mobile.isRunning() }.getOrDefault(false)) {
            return
        }

        val raw = MmkvManager.decodeServerRaw(guid)
        val config = OlcRtcConfig.resolve(raw)
        activeConfig = config
        lastSocksConnectError = ""
        resetDiagnostics(context)

        writeDiagnostics(
            context,
            "start requested; roomConfigured=${config.roomId.isNotBlank()}; " +
                "clientId=${config.clientId}; provider=${config.provider}; transport=${config.transport}; " +
                "link=${config.link}; socks=${config.socksHost}:${config.socksPort}"
        )

        if (!config.isComplete()) {
            writeDiagnostics(context, "missing olcRTC credentials")
            error("olcRTC fallback is not configured in this APK/profile")
        }

        try {
            installMobileCallbacks(context, protectSocket)
            configureMobile(config)
            LogUtil.w(TAG, "olcRTC: starting sidecar")
            writeDiagnostics(context, "starting mobile sidecar")
            Mobile.startWithTransport(
                config.provider,
                config.transport,
                config.roomId,
                config.clientId,
                config.key,
                config.socksPort.toLong(),
                config.socksUser,
                config.socksPass
            )
            Mobile.waitReady(SOCKS_START_TIMEOUT_MS)
        } catch (e: Exception) {
            writeDiagnostics(context, "failed to start mobile sidecar: ${e.javaClass.simpleName}: ${e.message}")
            stop()
            throw e
        }

        if (!canConnectToSocks(config)) {
            val message = "olcRTC SOCKS did not open on ${config.socksHost}:${config.socksPort}; lastConnectError=$lastSocksConnectError"
            writeDiagnostics(context, message)
            stop()
            error(message)
        }

        writeDiagnostics(context, "SOCKS listener is ready on ${config.socksHost}:${config.socksPort}")
        LogUtil.w(TAG, "olcRTC: SOCKS listener is ready on ${config.socksHost}:${config.socksPort}")
    }

    @Synchronized
    fun stop() {
        if (!runCatching { Mobile.isRunning() }.getOrDefault(false)) {
            activeConfig = null
            return
        }
        LogUtil.w(TAG, "olcRTC: stopping sidecar")
        runCatching { Mobile.stop() }
        activeConfig = null
    }

    fun readDiagnostics(context: Context): String {
        return diagnosticFile(context).takeIf { it.exists() }?.readText().orEmpty()
    }

    private fun installMobileCallbacks(context: Context, protectSocket: ((Int) -> Boolean)?) {
        Mobile.setProtector(object : SocketProtector {
            override fun protect(fd: Long): Boolean {
                val protected = protectSocket?.invoke(fd.toInt()) ?: true
                writeDiagnostics(context, "protect fd=$fd result=$protected")
                return protected
            }
        })
        Mobile.setLogWriter(object : LogWriter {
            override fun writeLog(msg: String) {
                val line = msg.trimEnd()
                writeDiagnostics(context, line)
                LogUtil.w(TAG, "olcRTC: $line")
            }
        })
        Mobile.setProviders()
    }

    private fun configureMobile(config: OlcRtcConfig) {
        Mobile.setProviders()
        Mobile.setDebug(true)
        Mobile.setLink(config.link)
        Mobile.setTransport(config.transport)
        Mobile.setDNS("1.1.1.1:53")
        Mobile.setVP8Options(config.vp8Fps.toLong(), config.vp8Batch.toLong())
    }

    private fun canConnectToSocks(config: OlcRtcConfig): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(config.socksHost, config.socksPort), 500)
            }
            true
        } catch (e: Exception) {
            lastSocksConnectError = "${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    private fun resetDiagnostics(context: Context) {
        runCatching {
            diagnosticFile(context).writeText("")
        }
    }

    private fun writeDiagnostics(context: Context, message: String) {
        runCatching {
            diagnosticFile(context).appendText("${Instant.now()} $message\n")
        }
    }

    private fun diagnosticFile(context: Context): File {
        return File(context.filesDir, DIAG_FILE)
    }
}
