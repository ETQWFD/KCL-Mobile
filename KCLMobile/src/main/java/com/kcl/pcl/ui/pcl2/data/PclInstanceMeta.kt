/*
 * KCL Mobile — PCL2 UI port
 *
 * 概览页「分类 / 启动次数 / 图标选择」这三项在 KCL 内核里没有对应字段
 * （内核的 `VersionConfig` 只有自定义描述 `customInfo`、自定义图标文件等），
 * 所以 PCL2 界面这一侧用 MMKV 单独记一份，不污染内核配置：
 *
 *   · [categoryOf]    —— 概览页「分类」组合框选的值；版本选择页分组时优先采纳
 *                        （对应 PCL 的 `McInstance.DisplayType` / `Setup.ini` 里的 `DisplayType`）；
 *   · [startCount]    —— 概览页「启动次数」显示的值，每次本界面成功发起启动时 +1
 *                        （对应 PCL `Setup.ini` 里的 `LaunchCount`）；
 *   · [iconChoiceOf]  —— 图标组合框选的是哪一项。图标本身是直接写进
 *                        `<版本目录>/PCL/VersionIcon.png`（内核原生支持），
 *                        但文件里看不出「当初选的是哪块方块」，所以额外记一份选择。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.kcl.pcl.ui.pcl2.data

import com.google.gson.reflect.TypeToken
import com.kcl.pcl.utils.GSON
import com.tencent.mmkv.MMKV

object PclInstanceMeta {
    // ---- 分类（PCL：MyComboBoxItem 的顺序，自动 = 未设置）
    const val CATEGORY_AUTO = "auto"
    const val CATEGORY_HIDDEN = "hidden"
    const val CATEGORY_MOD = "mod"
    const val CATEGORY_REGULAR = "regular"
    const val CATEGORY_UNCOMMON = "uncommon"
    const val CATEGORY_FOOL = "fool"

    // ---- 图标选择
    const val ICON_AUTO = "auto"
    const val ICON_CUSTOM = "custom"

    private const val KEY_CATEGORY = "pcl2.instance.category"
    private const val KEY_START_COUNT = "pcl2.instance.startCount"
    private const val KEY_ICON = "pcl2.instance.icon"

    fun categoryOf(versionName: String): String =
        readMap(KEY_CATEGORY)[versionName] ?: CATEGORY_AUTO

    fun setCategory(versionName: String, category: String) {
        val map = readMap(KEY_CATEGORY).toMutableMap()
        if (category == CATEGORY_AUTO) map.remove(versionName) else map[versionName] = category
        writeMap(KEY_CATEGORY, map)
    }

    fun startCount(versionName: String): Int =
        readMap(KEY_START_COUNT)[versionName]?.toIntOrNull() ?: 0

    fun increaseStartCount(versionName: String) {
        val map = readMap(KEY_START_COUNT).toMutableMap()
        map[versionName] = (startCount(versionName) + 1).toString()
        writeMap(KEY_START_COUNT, map)
    }

    fun iconChoiceOf(versionName: String): String =
        readMap(KEY_ICON)[versionName] ?: ICON_AUTO

    fun setIconChoice(versionName: String, choice: String) {
        val map = readMap(KEY_ICON).toMutableMap()
        if (choice == ICON_AUTO) map.remove(versionName) else map[versionName] = choice
        writeMap(KEY_ICON, map)
    }

    private fun readMap(key: String): Map<String, String> = runCatching {
        MMKV.defaultMMKV().decodeString(key)?.let { json ->
            GSON.fromJson<Map<String, String>>(json, object : TypeToken<Map<String, String>>() {}.type)
        } ?: emptyMap()
    }.getOrDefault(emptyMap())

    private fun writeMap(key: String, map: Map<String, String>) {
        runCatching { MMKV.defaultMMKV().encode(key, GSON.toJson(map)) }
    }
}
