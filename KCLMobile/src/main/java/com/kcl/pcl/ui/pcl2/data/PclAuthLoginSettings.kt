/*
 * KCL Mobile — PCL2 UI port
 *
 * 主页「外置登录」面板里填的邮箱 / 密码（对应 PCL `PageLoginAuth` 的
 * `ComboName` + `TextPass` + `CheckRemember` 三个控件）。
 *
 * 为什么要有这一层：版本设置里选了第三方登录之后，**启动那一刻**要拿这对凭据去
 * 认证服务器换 token。主页面板是用户唯一会输入它们的地方，所以必须留一份给
 * 启动链路读（见 `PclThirdPartyLogin.ask`），否则每次启动都得再弹一次输入框 ——
 * 那正是用户抱怨的「设置完还要再问我一遍」。
 *
 * 存两份：
 *  · 内存里一份 —— 不管有没有勾「记住密码」，这一次输入的值启动时都要能用；
 *  · 磁盘上一份 —— 只有勾了「记住密码」才写密码，没勾就只留邮箱。
 *
 * 磁盘落在应用私有的 SharedPreferences（`/data/data/<包名>/shared_prefs`），
 * 与 PCL 一样是明文配置，没有额外加密 —— 方便用户自己清掉。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.kcl.pcl.ui.pcl2.data

import android.content.Context

object PclAuthLoginSettings {
    private const val PREFS = "pcl_auth_login"
    private const val KEY_EMAIL = "email_"
    private const val KEY_PASSWORD = "password_"
    private const val KEY_REMEMBER = "remember_"

    /** 一台认证服务器上记住的一组凭据。 */
    data class Saved(
        val email: String = "",
        val password: String = "",
        val remember: Boolean = false,
    ) {
        val usable: Boolean get() = email.isNotBlank() && password.isNotBlank()
    }

    /**
     * 本次进程里的最新值。
     *
     * 必须比磁盘优先：用户刚在面板里改完密码就点了启动，磁盘那份还可能是上一组
     * （只有勾了「记住密码」才会写盘）。
     */
    private val cache = HashMap<String, Saved>()

    fun load(context: Context, baseUrl: String): Saved {
        if (baseUrl.isBlank()) return Saved()
        cache[baseUrl]?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = Saved(
            email = prefs.getString(KEY_EMAIL + baseUrl, "").orEmpty(),
            password = prefs.getString(KEY_PASSWORD + baseUrl, "").orEmpty(),
            remember = prefs.getBoolean(KEY_REMEMBER + baseUrl, false),
        )
        // 只有真的存过东西才进缓存：否则「没配过」会被永久记成空值，
        // 之后别处再写盘就读不到了。
        if (saved.email.isNotEmpty() || saved.password.isNotEmpty()) cache[baseUrl] = saved
        return saved
    }

    fun save(context: Context, baseUrl: String, saved: Saved) {
        if (baseUrl.isBlank()) return
        cache[baseUrl] = saved
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                putString(KEY_EMAIL + baseUrl, saved.email)
                if (saved.remember) {
                    putString(KEY_PASSWORD + baseUrl, saved.password)
                } else {
                    remove(KEY_PASSWORD + baseUrl)
                }
                putBoolean(KEY_REMEMBER + baseUrl, saved.remember)
            }
            .apply()
    }

    /** 启动链路要用的那一组：内存里最新的优先，其次磁盘。 */
    fun current(context: Context, baseUrl: String): Saved = load(context, baseUrl)
}
