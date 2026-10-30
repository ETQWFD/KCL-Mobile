/*
 * KCL Mobile — PCL2 UI port
 *
 * 版本级「登录方式」的取值口径与地址归一化（对应 PCL 的
 * `Pages/PageInstance/PageInstanceSetup.xaml` → 服务器 → 登录方式）。
 *
 * PCL 在这个下拉里给了五项：
 *   正版登录或离线登录 / 仅正版登录 / 仅离线登录 /
 *   第三方登录：统一通行证 / 第三方登录：Authlib Injector 或 LittleSkin
 * 选择后两个时才展开「认证服务器 / 注册链接 / 服务器名称」这几行。
 *
 * 这里只做数据层的事（归一化、匹配账户、LittleSkin 预设），
 * 界面在 `pages/PclInstancePages.kt`，启动前的账户解析在 `data/PclRuntime.kt`。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.kcl.pcl.ui.pcl2.data

import com.kcl.pcl.game.account.Account
import com.kcl.pcl.game.account.isAuthServerAccount
import com.kcl.pcl.game.version.installed.VersionConfig
import com.kcl.pcl.game.version.installed.VersionLoginType

/** LittleSkin 的 Yggdrasil 地址（PCL 的「设置为 LittleSkin」写的就是它）。 */
const val LITTLESKIN_AUTH_SERVER = "https://littleskin.cn/api/yggdrasil"

/** LittleSkin 的注册页。 */
const val LITTLESKIN_REGISTER_URL = "https://littleskin.cn/auth/register"

/** LittleSkin 显示的服务器名。 */
const val LITTLESKIN_SERVER_NAME = "LittleSkin"

/** 统一通行证（Nide8）的固定前缀，后面接服主给的服务器 ID。 */
private const val NIDE8_AUTH_PREFIX = "https://auth.mc-user.com:233/"

/** 这个版本要求的登录方式（旧配置缺字段时按「正版登录或离线登录」）。 */
fun VersionConfig.loginTypeValue(): VersionLoginType = VersionLoginType.from(loginType)

/** 是不是第三方登录（两种都要证书服务器地址 / ID）。 */
fun VersionLoginType.isThirdParty(): Boolean =
    this == VersionLoginType.UNIFIED_PASSPORT || this == VersionLoginType.AUTHLIB

/** 地址归一化：补 `https://`、去掉结尾斜杠；空串原样返回。 */
fun normalizeAuthServerUrl(raw: String): String {
    var url = raw.trim()
    if (url.isEmpty()) return ""
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
        url = "https://$url"
    }
    return url.trimEnd('/')
}

/**
 * 真正拿去登录 / 启动的认证服务器地址。
 *
 * · 统一通行证：输入框里是**服务器 ID**，这里拼成 `https://auth.mc-user.com:233/<id>`
 *   （与核心 `addOtherServer()` 的处理完全一致，否则 Nide8 账户会被当成普通 authlib 服务器）。
 * · Authlib：输入框里是完整地址，补协议、去尾斜杠即可。
 * · 其它登录方式返回空串（没有第三方服务器）。
 */
fun VersionConfig.loginBaseUrl(): String {
    val raw = loginAuthServer.trim()
    if (raw.isEmpty()) return ""
    return when (loginTypeValue()) {
        VersionLoginType.UNIFIED_PASSPORT -> {
            val id = raw.removePrefix(NIDE8_AUTH_PREFIX).trim().trim('/')
            if (id.isEmpty()) "" else "$NIDE8_AUTH_PREFIX$id"
        }
        VersionLoginType.AUTHLIB -> normalizeAuthServerUrl(raw)
        else -> ""
    }
}

/** 登录页 / 提示条上显示的服务器名。没填就给个通用的。 */
fun VersionConfig.loginServerTitle(): String =
    loginServerName.trim().ifEmpty { "第三方登录" }

/** 这个账户是不是属于某个认证服务器。 */
fun Account.belongsToAuthServer(baseUrl: String): Boolean {
    if (!isAuthServerAccount()) return false
    val normalized = normalizeAuthServerUrl(baseUrl)
    if (normalized.isEmpty()) return false
    return normalizeAuthServerUrl(otherBaseUrl.orEmpty()).equals(normalized, ignoreCase = true)
}

/** 下拉里每一项的文案（与 PCL 的中文原文一致）。 */
fun VersionLoginType.displayName(): String = when (this) {
    VersionLoginType.MICROSOFT_OR_OFFLINE -> "正版登录或离线登录"
    VersionLoginType.MICROSOFT_ONLY -> "仅正版登录"
    VersionLoginType.OFFLINE_ONLY -> "仅离线登录"
    VersionLoginType.UNIFIED_PASSPORT -> "第三方登录：统一通行证"
    VersionLoginType.AUTHLIB -> "第三方登录：Authlib Injector 或 LittleSkin"
}

/** 下拉里每一项的一句解释（放在卡片底部的蓝色提示里）。 */
fun VersionLoginType.description(): String = when (this) {
    VersionLoginType.MICROSOFT_OR_OFFLINE ->
        "跟随当前选中的账户启动，正版账户与离线账户都可以。"
    VersionLoginType.MICROSOFT_ONLY ->
        "启动时强制使用微软（正版）账户；当前账户不是正版时会自动切到已登录的正版账户。"
    VersionLoginType.OFFLINE_ONLY ->
        "启动时强制使用离线账户；适合不愿意用正版账户进游戏的版本。"
    VersionLoginType.UNIFIED_PASSPORT ->
        "统一通行证（Nide8）：填写服主给的服务器 ID，启动时用该服务器上的角色。"
    VersionLoginType.AUTHLIB ->
        "通用的外置登录（authlib-injector）。认证服务器填 Yggdrasil API 的完整地址，" +
                "LittleSkin、各类自建皮肤站都属于这一类。"
}
