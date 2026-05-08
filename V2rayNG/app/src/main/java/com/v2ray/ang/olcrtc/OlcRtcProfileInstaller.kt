package com.v2ray.ang.olcrtc

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.fmt.CustomFmt
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil

object OlcRtcProfileInstaller {
    private const val ASSET_CONFIG = "olcrtc/abumba_video_fallback.json"
    private const val MARKER = "olcrtc-socks"

    fun ensureInstalled(context: Context): Boolean {
        val configText = readAsset(context) ?: return false
        if (hasInstalledProfile()) {
            return false
        }

        return installCustomConfig(configText) != null
    }

    fun reinstall(context: Context): Boolean {
        val configText = readAsset(context) ?: return false
        return installCustomConfig(configText) != null
    }

    private fun readAsset(context: Context): String? {
        return try {
            context.assets.open(ASSET_CONFIG).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "olcRTC fallback profile asset is not bundled", e)
            null
        }
    }

    private fun hasInstalledProfile(): Boolean {
        return MmkvManager.decodeAllServerList().any { guid ->
            MmkvManager.decodeServerRaw(guid).orEmpty().contains(MARKER)
        }
    }

    private fun selectInstalledProfile() {
        MmkvManager.decodeAllServerList().firstOrNull { guid ->
            MmkvManager.decodeServerRaw(guid).orEmpty().contains(MARKER)
        }?.let { MmkvManager.setSelectServer(it) }
    }

    private fun installCustomConfig(configText: String): String? {
        return try {
            val config = CustomFmt.parse(configText)
            config.subscriptionId = AppConfig.DEFAULT_SUBSCRIPTION_ID
            config.description = config.remarks
            val guid = MmkvManager.encodeServerConfig("", config)
            MmkvManager.encodeServerRaw(guid, configText)
            MmkvManager.setSelectServer(guid)
            guid
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "failed to install bundled olcRTC fallback profile", e)
            null
        }
    }
}
