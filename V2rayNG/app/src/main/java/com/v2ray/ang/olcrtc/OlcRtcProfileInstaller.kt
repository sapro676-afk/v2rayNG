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
        val configText = readAsset(context) ?: generatedConfigText() ?: return false
        if (hasInstalledProfile()) {
            return false
        }

        return installCustomConfig(configText) != null
    }

    fun reinstall(context: Context): Boolean {
        val configText = readAsset(context) ?: generatedConfigText() ?: return false
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

    fun hasInstalledProfile(): Boolean {
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

    private fun generatedConfigText(): String? {
        val config = OlcRtcConfig.fromBuildConfig()
        if (!config.isComplete()) return null
        return """
            {
              "remarks": "Abumba video fallback (olcrtc-socks)",
              "olcrtc": {
                "provider": ${config.provider.jsonString()},
                "transport": ${config.transport.jsonString()},
                "room_id": ${config.roomId.jsonString()},
                "client_id": ${config.clientId.jsonString()},
                "key": ${config.key.jsonString()},
                "link": ${config.link.jsonString()},
                "socks_host": ${config.socksHost.jsonString()},
                "socks_port": ${config.socksPort},
                "socks_user": ${config.socksUser.jsonString()},
                "socks_pass": ${config.socksPass.jsonString()},
                "vp8_fps": ${config.vp8Fps},
                "vp8_batch": ${config.vp8Batch}
              },
              "log": {
                "loglevel": "warning"
              },
              "outbounds": [
                {
                  "tag": "olcrtc-socks",
                  "protocol": "socks",
                  "settings": {
                    "servers": [
                      {
                        "address": ${config.socksHost.jsonString()},
                        "port": ${config.socksPort}
                      }
                    ]
                  }
                },
                {
                  "tag": "direct",
                  "protocol": "freedom",
                  "settings": {}
                }
              ],
              "routing": {
                "domainStrategy": "AsIs",
                "rules": [
                  {
                    "type": "field",
                    "network": "tcp,udp",
                    "outboundTag": "olcrtc-socks"
                  }
                ]
              }
            }
        """.trimIndent()
    }

    private fun String.jsonString(): String {
        return "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
