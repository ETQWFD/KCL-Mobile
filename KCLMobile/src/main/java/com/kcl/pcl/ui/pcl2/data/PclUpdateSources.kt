/*
 * KCL Mobile — PCL2 UI port
 *
 * 启动器更新弹窗用的「网盘入口」，**链接从官网拿**。
 *
 * 官网（pcl-homepage-go）提供 `GET /api/downloads`，内容就是后台
 * 「下载方式」那一栏（`/admin/downloads`）里启用中的条目：
 *
 *     {
 *       "pan123": {"key":"123pan","name":"123云盘","url":"https://…"},
 *       "items":  [ …同上结构… ]
 *     }
 *
 * 后台把那条的地址改掉，启动器这边下次拉到就跟着变 —— 换 123 云盘分享链接
 * 不需要重新打包发版。
 *
 * 拿不到（未部署新版官网 / 断网 / 后台没配这一条）时返回 null，调用方就不画
 * 那个按钮，绝不影响原有的「立即更新」流程。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.kcl.pcl.ui.pcl2.data

import com.kcl.pcl.setting.launcherMMKV
import com.kcl.pcl.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val LOG_TAG = "PclUpdateSources"

/** 成功拿到之后多久内不再重复请求（更新框与选架构框会先后各问一次）。 */
private const val FRESH_MS = 5 * 60 * 1000L

object PclUpdateSources {

    /** MMKV key：把上一次成功的结果落盘，断网/官网挂了也能照样显示入口。 */
    private const val KEY_CACHE = "pcl_update_sources_cache"

    private const val TIMEOUT_MS = 8000

    /**
     * 一条网盘入口。
     *
     * @param key 官网给的语义标识（`123pan` / `github` / `lanzou` …），比名字稳。
     * @param name 按钮上显示的文字，直接用后台那条的名称。
     * @param url 点开就跳的地址。
     */
    data class Source(val key: String, val name: String, val url: String)

    @Volatile
    private var cached: Source? = null

    @Volatile
    private var diskLoaded = false

    @Volatile
    private var lastSuccessAt = 0L

    /** 内存/磁盘里上一次的结果，纯同步，首帧就能用（没有就 null）。 */
    fun cachedSource(): Source? {
        if (!diskLoaded) {
            diskLoaded = true
            cached = readDisk()
        }
        return cached
    }

    /**
     * 联网拉「123 云盘」那条；失败退回缓存。
     *
     * 5 分钟内成功过就直接用缓存，避免更新框 → 选架构框连续两次请求。
     */
    suspend fun fetch(): Source? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val fromMemory = cachedSource()
        if (fromMemory != null && now - lastSuccessAt < FRESH_MS) return@withContext fromMemory

        val fetched = runCatching { fetchBlocking() }.getOrElse { e ->
            Logger.warning(LOG_TAG, "拉取网盘入口失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (fetched != null) {
            cached = fetched
            lastSuccessAt = System.currentTimeMillis()
            writeDisk(fetched)
            fetched
        } else {
            fromMemory
        }
    }

    private fun fetchBlocking(): Source? {
        val base = PclRemoteContent.baseUrl()
        if (base.isEmpty()) {
            // 默认未配置站点（旧 z-pcl.cc.cd:28722 已下线，DEFAULT_BASE_URL 置空）；
            // 这是正常跳过而非异常，降级为 debug，不刷 WARNING。
            Logger.debug(LOG_TAG, "未配置官网地址，跳过网盘入口")
            return null
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("$base/api/downloads").openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "KCLMobile/2")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Logger.warning(LOG_TAG, "GET /api/downloads 返回 HTTP $code")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 从响应里挑出 123 云盘那条；`pan123` 为 null 时按名字再兜一次。 */
    private fun parse(body: String): Source? {
        val root = JSONObject(body)
        root.optJSONObject("pan123")?.let { return toSource(it) }
        val items = root.optJSONArray("items") ?: return null
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val src = toSource(obj) ?: continue
            if (src.key == "123pan" || src.name.contains("123")) return src
        }
        return null
    }

    private fun toSource(obj: JSONObject): Source? {
        val url = obj.optString("url").trim()
        if (url.isEmpty()) return null
        val name = obj.optString("name").trim().ifEmpty { "123 云盘" }
        val key = obj.optString("key").trim()
        return Source(key = key, name = name, url = url)
    }

    private fun readDisk(): Source? = runCatching {
        val raw = launcherMMKV().getString(KEY_CACHE, "").orEmpty()
        if (raw.isBlank()) return@runCatching null
        val obj = JSONObject(raw)
        Source(
            key = obj.optString("key"),
            name = obj.optString("name").ifEmpty { "123 云盘" },
            url = obj.optString("url"),
        ).takeIf { it.url.isNotBlank() }
    }.getOrNull()

    private fun writeDisk(src: Source) {
        runCatching {
            launcherMMKV().putString(
                KEY_CACHE,
                JSONObject()
                    .put("key", src.key)
                    .put("name", src.name)
                    .put("url", src.url)
                    .toString(),
            )
        }
    }
}
