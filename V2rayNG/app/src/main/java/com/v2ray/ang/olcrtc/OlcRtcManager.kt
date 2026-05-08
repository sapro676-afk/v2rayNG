package com.v2ray.ang.olcrtc

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant

object OlcRtcManager {
    private const val TAG = AppConfig.TAG
    private const val MARKER = "olcrtc-socks"
    private const val SOCKS_HOST = "127.0.0.1"
    private const val SOCKS_PORT = "18080"
    private const val SOCKS_START_TIMEOUT_MS = 8_000L
    private const val DATA_ASSET_DIR = "olcrtc-data"
    private const val DIAG_FILE = "olcrtc.log"

    @Volatile
    private var process: Process? = null

    @Volatile
    private var socksReportedListening: Boolean = false

    @Volatile
    private var lastSocksConnectError: String = ""

    fun isRequired(guid: String): Boolean {
        val config = MmkvManager.decodeServerConfig(guid)
        val raw = MmkvManager.decodeServerRaw(guid).orEmpty()
        return raw.contains(MARKER) || config?.remarks?.contains("olcrtc", ignoreCase = true) == true
    }

    @Synchronized
    fun startIfRequired(context: Context, guid: String) {
        if (!isRequired(guid)) {
            stop()
            return
        }
        if (process?.isAlive == true) {
            return
        }

        val key = BuildConfig.OLCRTC_KEY
        val roomId = BuildConfig.OLCRTC_ROOM_ID
        val clientId = BuildConfig.OLCRTC_CLIENT_ID.ifBlank { "v2rayng-android" }
        socksReportedListening = false
        lastSocksConnectError = ""
        resetDiagnostics(context)
        writeDiagnostics(context, "start requested; roomConfigured=${roomId.isNotBlank()}; clientId=$clientId")
        if (key.isBlank() || roomId.isBlank()) {
            writeDiagnostics(context, "missing olcRTC credentials in BuildConfig")
            error("olcRTC fallback is not configured in this APK")
        }

        val binary = File(context.applicationInfo.nativeLibraryDir, "libolcrtc.so")
        writeDiagnostics(
            context,
            "binary=${binary.absolutePath}; exists=${binary.exists()}; canExecute=${binary.canExecute()}; size=${binary.length()}"
        )
        if (!binary.canExecute()) {
            writeDiagnostics(context, "binary is missing or not executable")
            error("olcRTC binary is missing or not executable")
        }

        val dataDir = ensureDataDir(context)
        writeDiagnostics(context, "dataDir=${dataDir.absolutePath}; names=${File(dataDir, "names").length()}; surnames=${File(dataDir, "surnames").length()}")
        val cmd = mutableListOf(
            binary.absolutePath,
            "-mode", "cnc",
            "-carrier", "wbstream",
            "-transport", "datachannel",
            "-id", roomId,
            "-client-id", clientId,
            "-key", key,
            "-link", "direct",
            "-data", dataDir.absolutePath,
            "-socks-host", SOCKS_HOST,
            "-socks-port", SOCKS_PORT,
            "-dns", "1.1.1.1:53",
            "-debug"
        )

        LogUtil.w(TAG, "olcRTC: starting client")
        writeDiagnostics(context, "starting client with wbstream/datachannel/direct on $SOCKS_HOST:$SOCKS_PORT")
        process = try {
            ProcessBuilder(cmd)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()
                .also { proc ->
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            proc.inputStream.bufferedReader().useLines { lines ->
                                lines.forEach { line ->
                                    if (line.contains("SOCKS5 server listening on $SOCKS_HOST:$SOCKS_PORT")) {
                                        socksReportedListening = true
                                    }
                                    writeDiagnostics(context, line)
                                    LogUtil.w(TAG, "olcRTC: $line")
                                }
                            }
                        } catch (e: InterruptedIOException) {
                            writeDiagnostics(context, "stdout reader closed: ${e.javaClass.simpleName}: ${e.message}")
                        } catch (e: IOException) {
                            writeDiagnostics(context, "stdout reader ended: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                }
        } catch (e: Exception) {
            writeDiagnostics(context, "failed to start process: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }

        if (!waitForSocks(context)) {
            val proc = process
            val message = if (proc?.isAlive == true) {
                "olcRTC SOCKS did not open on $SOCKS_HOST:$SOCKS_PORT"
            } else {
                "olcRTC client exited during startup, exit=${runCatching { proc?.exitValue() }.getOrNull()}"
            }
            writeDiagnostics(context, message)
            error(message)
        }
        writeDiagnostics(context, "SOCKS listener is ready on $SOCKS_HOST:$SOCKS_PORT")
        LogUtil.w(TAG, "olcRTC: SOCKS listener is ready on $SOCKS_HOST:$SOCKS_PORT")
    }

    @Synchronized
    fun stop() {
        val running = process ?: return
        LogUtil.w(TAG, "olcRTC: stopping client")
        runCatching { running.destroy() }
        process = null
    }

    fun readDiagnostics(context: Context): String {
        return diagnosticFile(context).takeIf { it.exists() }?.readText().orEmpty()
    }

    private fun ensureDataDir(context: Context): File {
        val dir = File(context.filesDir, DATA_ASSET_DIR)
        dir.mkdirs()
        copyAsset(context, "$DATA_ASSET_DIR/names", File(dir, "names"))
        copyAsset(context, "$DATA_ASSET_DIR/surnames", File(dir, "surnames"))
        return dir
    }

    private fun copyAsset(context: Context, assetName: String, target: File) {
        if (target.length() > 0) return
        context.assets.open(assetName).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun waitForSocks(context: Context): Boolean {
        val deadline = System.currentTimeMillis() + SOCKS_START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive != true) return false
            if (socksReportedListening || canConnectToSocks()) return true
            Thread.sleep(250L)
        }
        writeDiagnostics(context, "SOCKS wait timed out after ${SOCKS_START_TIMEOUT_MS}ms; lastConnectError=$lastSocksConnectError")
        return false
    }

    private fun canConnectToSocks(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(SOCKS_HOST, SOCKS_PORT.toInt()), 250)
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
