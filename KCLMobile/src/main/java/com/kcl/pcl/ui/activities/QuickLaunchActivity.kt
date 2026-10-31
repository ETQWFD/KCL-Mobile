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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.kcl.pcl.ui.activities

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kcl.pcl.R
import com.kcl.pcl.components.jre.RuntimeProvisioner
import com.kcl.pcl.game.account.AccountsManager
import com.kcl.pcl.game.launch.GameLaunchFlow
import com.kcl.pcl.game.path.getGameHome
import com.kcl.pcl.game.version.installed.VersionsManager
import com.kcl.pcl.ui.toAndroidString
import com.kcl.pcl.utils.image.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "QuickLaunch"

/**
 * 桌面快捷方式「导出启动脚本」的落地 Activity。
 *
 * 从桌面图标被拉起时只带一个 [EXTRA_VERSION_NAME] extra（版本名）。
 * 启动链路与「从版本列表点启动」完全一致，全部复用现有公开入口，不新开登录/启动 UI：
 *  1. [RuntimeProvisioner.ensureFor] 做 Java 运行库自检/补装（required=0：至少有一个可用运行库，
 *     与 JvmLauncher 的 run-jar 路径同参；真正按版本要求的版本号由 GameLauncher 内部再校一次）；
 *  2. 按版本名从磁盘同步解析出 [com.kcl.pcl.game.version.installed.Version]，
 *     不依赖异步版本列表是否已刷新；
 *  3. 登录态直接读 [AccountsManager.currentAccountFlow]——没有当前账户就退回主界面让用户登录，
 *     不做新登录 UI；
 *  4. 用 [GameLaunchFlow.launch] 走与版本列表启动相同的任务流（账号校验 → 模组扫描 →
 *     完整性校验 → runGame）。
 */
class QuickLaunchActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val versionName = intent?.getStringExtra(ShortcutInstaller.EXTRA_VERSION_NAME).orEmpty()
        if (versionName.isBlank()) {
            toast(R.string.quick_launch_version_not_found)
            routeToLauncher()
            return
        }

        lifecycleScope.launch {
            // 1) Java 自检/补装（在 IO 线程：会扫目录、必要时解压内置运行库）
            withContext(Dispatchers.IO) {
                runCatching {
                    RuntimeProvisioner.ensureFor(this@QuickLaunchActivity, 0, "quick launch")
                }
            }

            // 2) 解析目标版本
            val version = withContext(Dispatchers.IO) {
                runCatching {
                    VersionsManager.loadVersion(getGameHome(), versionName)
                        ?.takeIf { it.isValid() }
                }.getOrNull()
            }
            if (version == null) {
                toast(R.string.quick_launch_version_not_found)
                routeToLauncher()
                return@launch
            }

            // 3) 登录态复用：没有已选账户就退回主界面登录
            if (AccountsManager.currentAccountFlow.value == null) {
                toast(R.string.quick_launch_no_account)
                routeToLauncher()
                return@launch
            }

            // 4) 复用现有公开启动流程
            val flow = GameLaunchFlow(lifecycleScope)
            flow.launch(
                context = this@QuickLaunchActivity,
                version = version,
                exitActivity = { finish() },
                submitError = { error ->
                    runOnUiThread {
                        Toast.makeText(
                            this@QuickLaunchActivity,
                            error.message.toAndroidString(this@QuickLaunchActivity),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    routeToLauncher()
                },
                onReloginRequired = { routeToLauncher() },
                onRefreshFailed = { _, _ -> routeToLauncher() },
                onComplete = { }
            )
        }
    }

    /** 失败/缺账号/版本不存在时退回正常启动器主界面，而不是把用户留在空 Activity 里。 */
    private fun routeToLauncher() {
        runCatching {
            val intent = Intent(this, SplashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        }
        finish()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
    }
}

/**
 * 桌面快捷方式安装器。创建端（版本详情页）与消费端（[QuickLaunchActivity]）共用这里的 extra key。
 */
object ShortcutInstaller {
    /** 目标版本名 extra（与 QuickLaunchActivity 消费端一致） */
    const val EXTRA_VERSION_NAME = "com.kcl.pcl.extra.QUICK_LAUNCH_VERSION_NAME"

    /** 预留：整合包/版本类型，当前两路径都按普通版本启动 */
    const val EXTRA_PACK_TYPE = "com.kcl.pcl.extra.QUICK_LAUNCH_PACK_TYPE"

    enum class Result { CREATED, ALREADY_EXISTS, FAILED }

    /** 构造指向 QuickLaunchActivity 的目标 Intent（创建端与系统快捷方式都引用它）。 */
    fun buildTargetIntent(context: Context, versionName: String): Intent {
        return Intent(context, QuickLaunchActivity::class.java).apply {
            // 显式 component + action：ShortcutInfo.setIntent 要求一个可解析、带 action 的 Intent
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_VERSION_NAME, versionName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    }

    /**
     * 以版本名为名在桌面创建快捷方式。
     * - API 26+：[ShortcutManager.requestPinShortcut]，系统弹确认；创建前用 pinnedShortcuts 查重。
     * - API < 26：发 [ACTION_INSTALL_SHORTCUT] 广播（旧桌面行为，由 Launcher 应用自己处理重复）。
     */
    fun install(context: Context, versionName: String): Result {
        val label = sanitizeLabel(versionName).ifBlank { versionName }
        val target = buildTargetIntent(context, versionName)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val sm = context.getSystemService(ShortcutManager::class.java)
                    ?: return Result.FAILED
                // 重名/重复：已存在指向同一版本的 pin 快捷方式就不再重复申请
                val alreadyPinned = sm.pinnedShortcuts.any { info ->
                    info.intent?.getStringExtra(EXTRA_VERSION_NAME) == versionName
                }
                if (alreadyPinned) return Result.ALREADY_EXISTS

                val shortcut = ShortcutInfo.Builder(context, "quick_launch_" + versionName.replace(' ', '_'))
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(target)
                    .build()
                sm.requestPinShortcut(shortcut, null)
                Result.CREATED
            } else {
                // 旧设备：EXTRA_SHORTCUT_ICON 要 Bitmap，把应用图标解码成位图
                val bitmap = runCatching {
                    ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap()
                }.getOrNull()
                val broadcast = Intent(ACTION_INSTALL_SHORTCUT).apply {
                    putExtra(Intent.EXTRA_SHORTCUT_INTENT, target)
                    putExtra(Intent.EXTRA_SHORTCUT_NAME, label)
                    if (bitmap != null) putExtra(Intent.EXTRA_SHORTCUT_ICON, bitmap)
                }
                context.sendBroadcast(broadcast)
                Result.CREATED
            }
        }.getOrDefault(Result.FAILED)
    }

    /** 清掉桌面图标名里常见的非法/控制字符，保留可读字符。 */
    private fun sanitizeLabel(raw: String): String {
        return raw.replace("[\\n\\r\\t/\\\\:*?\"<>|]".toRegex(), " ").trim()
    }

    private const val ACTION_INSTALL_SHORTCUT = "com.android.launcher.action.INSTALL_SHORTCUT"
}
