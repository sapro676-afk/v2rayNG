package com.v2ray.ang.olcrtc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.time.Instant

class OlcRtcDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> startOlcRtc(context.applicationContext)
            ACTION_STOP -> stopOlcRtc(context.applicationContext)
        }
    }

    private fun startOlcRtc(context: Context) {
        appendDiagnostics(context, "debug receiver start requested")
        try {
            OlcRtcProfileInstaller.ensureInstalled(context)
            val guid = MmkvManager.getSelectServer() ?: error("No selected server")
            appendDiagnostics(context, "selected=$guid")
            OlcRtcManager.startIfRequired(context, guid)
            appendDiagnostics(context, "debug receiver start completed")
        } catch (e: Exception) {
            appendDiagnostics(context, "debug receiver start failed: ${e.javaClass.simpleName}: ${e.message}")
            LogUtil.e(AppConfig.TAG, "olcRTC debug start failed", e)
        }
    }

    private fun stopOlcRtc(context: Context) {
        appendDiagnostics(context, "debug receiver stop requested")
        OlcRtcManager.stop()
    }

    private fun appendDiagnostics(context: Context, message: String) {
        runCatching {
            File(context.filesDir, DIAG_FILE).appendText("${Instant.now()} $message\n")
        }
    }

    private companion object {
        private const val ACTION_START = "com.v2ray.ang.olcrtc.action.OLCRTC_DEBUG_START"
        private const val ACTION_STOP = "com.v2ray.ang.olcrtc.action.OLCRTC_DEBUG_STOP"
        private const val DIAG_FILE = "olcrtc.log"
    }
}
