/*
 * KCL Mobile
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.kcl.pcl.ui.activities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kcl.pcl.BuildConfig
import com.kcl.pcl.R
import com.kcl.pcl.context.COPY_LABEL_THROWABLE_STACK
import com.kcl.pcl.ui.base.AbstractAppCompatActivity
import com.kcl.pcl.ui.theme.showThemed
import com.kcl.pcl.utils.copyText
import com.kcl.pcl.utils.getSerializableSafely
import dagger.hilt.android.AndroidEntryPoint

private const val BUNDLE_THROWABLE = "BUNDLE_THROWABLE"

/** 崩溃上报收件邮箱：调用系统邮件应用把崩溃信息发给开发者 */
private const val CRASH_REPORT_EMAIL = "2416444244@qq.com"

/**
 * 用于显示致命崩溃信息的 Activity
 *
 * 此 Activity 会向用户展示一个 AlertDialog，详细说明崩溃情况
 *
 * 它被设计为与主启动器相互独立，以确保即使启动器本身出现严重问题，也能正常显示该界面
 */
@AndroidEntryPoint
class FatalErrorActivity : AbstractAppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val extras = intent.extras ?: return run { finish() }
        val throwable = extras.getSerializableSafely(BUNDLE_THROWABLE, Throwable::class.java) ?: return run { finish() }

        val message = getString(R.string.crash_launch_crash_message)
        val throwableStack = Log.getStackTraceString(throwable)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.crash_launch_crash_title))
            .setMessage(message + "\n\n" + throwableStack)
            // 上报给开发者：调用手机上的邮件应用，收件人 2416444244@qq.com，
            // 正文预填版本信息与崩溃堆栈，用户确认发送即可（无需复制粘贴）。
            .setPositiveButton("上报给开发者") { _, _ ->
                val subject = "KCL Mobile ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) 崩溃报告"
                val body = buildString {
                    append("设备信息与崩溃日志如下，请开发者查收：\n\n")
                    append("版本：KCL Mobile ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}\n")
                    append("机型：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
                    append("Android：${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n\n")
                    append(throwableStack)
                }
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:$CRASH_REPORT_EMAIL")
                            putExtra(Intent.EXTRA_SUBJECT, subject)
                            putExtra(Intent.EXTRA_TEXT, body)
                        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure {
                    // 设备没有邮件应用时退回复制，保证日志不丢
                    copyText(COPY_LABEL_THROWABLE_STACK, body, this)
                }
                finish()
            }
            .setNeutralButton(android.R.string.copy) { _, _ ->
                copyText(COPY_LABEL_THROWABLE_STACK, throwableStack, this)
                finish()
            }
            .setNegativeButton(android.R.string.ok) { _, _ -> finish() }
            .setCancelable(false)
            .showThemed()
    }
}

/**
 * 使用指定的 throwable 显示致命错误界面
 */
fun showFatalError(context: Context, throwable: Throwable) {
    val intent = Intent(context, FatalErrorActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(BUNDLE_THROWABLE, throwable)
    }
    context.startActivity(intent)
}