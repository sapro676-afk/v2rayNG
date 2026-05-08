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

object OlcRtcManager {
    private const val TAG = AppConfig.TAG
    private const val MARKER = "olcrtc-socks"
    private const val SOCKS_HOST = "127.0.0.1"
    private const val SOCKS_PORT = "18080"
    private const val DATA_ASSET_DIR = "olcrtc-data"

    @Volatile
    private var process: Process? = null

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
        if (key.isBlank() || roomId.isBlank()) {
            error("olcRTC fallback is not configured in this APK")
        }

        val binary = File(context.applicationInfo.nativeLibraryDir, "libolcrtc.so")
        if (!binary.canExecute()) {
            error("olcRTC binary is missing or not executable")
        }

        val dataDir = ensureDataDir(context)
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

        LogUtil.i(TAG, "olcRTC: starting client")
        process = ProcessBuilder(cmd)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .start()
            .also { proc ->
                CoroutineScope(Dispatchers.IO).launch {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> LogUtil.i(TAG, "olcRTC: $line") }
                    }
                }
            }

        Thread.sleep(1200L)
        if (process?.isAlive != true) {
            error("olcRTC client exited during startup")
        }
    }

    @Synchronized
    fun stop() {
        val running = process ?: return
        LogUtil.i(TAG, "olcRTC: stopping client")
        runCatching { running.destroy() }
        process = null
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
}
