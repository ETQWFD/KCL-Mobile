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

package com.kcl.pcl.game.renderer

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.kcl.pcl.path.PathManager
import com.kcl.pcl.setting.AllSettings
import com.kcl.pcl.utils.logging.Logger
import java.io.File

/**
 * KCL Engine —— 渲染调度层（Java 侧，不改动翻译层本身）。
 *
 * 解决的问题：桌面 GL 调用在手机上要经 JNI 进 C 层翻译（gl4es / Mesa），
 * 逐条调用 + 逐条 `glGetError` + 逐条扩展测试是卡顿的主要来源，而不是 Java 逻辑本身。
 * 这一层不去重写翻译器，只做三件事，把「进 C 层的次数」和「每帧像素量」压下来：
 *
 * 1. **批处理开关**：`LIBGL_BATCH` / `LIBGL_BEGINEND` 让 gl4es 把连续的绘制合并成一次提交，
 *    而不是每次 glBegin/glEnd 都进一次驱动；
 * 2. **去掉逐调用校验**：`LIBGL_NOERROR` / `LIBGL_NOTEST` / `LIBGL_NOBANNER`，
 *    省掉每次调用后的 `glGetError` 往返与启动期扩展探测；
 * 3. **档位化调度**：低配自动把渲染分辨率降到 80%，并用 Serial GC（2 核设备上比 G1 停顿更短）；
 *    同时给渲染器加一道「库缺失自动回退」，避免选到一个本机没打包的渲染器直接黑屏。
 *
 * 风格上保持可回退：任何一项被判定为「本次启动失败」两次，就自动进安全模式，
 * 只保留 NOERROR 这类已验证的安全项，其余批处理开关不再启用。
 */
object KclEngine {
    private const val TAG = "KclEngine"

    private const val PREF = "kcl_engine"
    private const val KEY_RES_APPLIED = "resolution_applied"
    private const val KEY_SAFE_MODE = "safe_mode"
    private const val KEY_FAIL_COUNT = "fail_count"

    enum class Tier(val label: String) {
        LOW("低配"),
        MID("中配"),
        HIGH("高配"),
    }

    data class Profile(
        val tier: Tier,
        val cores: Int,
        val totalRamMb: Int,
        val heapMb: Int,
        val lowRamDevice: Boolean,
        val soc: String,
        val gpuFamily: String,
    )

    @Volatile
    private var cachedProfile: Profile? = null

    @Volatile
    private var safeMode: Boolean = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun profile(context: Context): Profile {
        cachedProfile?.let { return it }
        val p = detect(context)
        cachedProfile = p
        safeMode = prefs(context).getBoolean(KEY_SAFE_MODE, false)
        Logger.info(
            TAG,
            "设备档位=${p.tier.label} 核心=${p.cores} 内存=${p.totalRamMb}MB 堆=${p.heapMb}MB " +
                "lowRam=${p.lowRamDevice} SoC=${p.soc} GPU家族=${p.gpuFamily} 安全模式=$safeMode"
        )
        return p
    }

    private fun detect(context: Context): Profile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo().also { info -> runCatching { am?.getMemoryInfo(info) } }
        val totalRamMb = (mi.totalMem / (1024L * 1024L)).toInt()
        val heapMb = am?.memoryClass ?: 256
        val lowRam = runCatching { am?.isLowRamDevice == true }.getOrDefault(false)
        val soc = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "${Build.SOC_MODEL} ${Build.SOC_MANUFACTURER}"
            } else {
                "${Build.HARDWARE} ${Build.BOARD} ${Build.DEVICE}"
            }
        }.getOrDefault("unknown")
        val family = guessGpuFamily(soc)
        val cores = Runtime.getRuntime().availableProcessors()

        val tier = when {
            lowRam -> Tier.LOW
            totalRamMb in 1..3072 -> Tier.LOW
            cores <= 4 && totalRamMb <= 4096 -> Tier.LOW
            family == "PowerVR" && totalRamMb <= 4096 -> Tier.LOW
            totalRamMb <= 6144 -> Tier.MID
            cores <= 6 -> Tier.MID
            else -> Tier.HIGH
        }
        return Profile(tier, cores, totalRamMb, heapMb, lowRam, soc.trim(), family)
    }

    /**
     * 只按 SoC 名字做「家族」判断，目的是选默认参数，不做任何硬性能力判定。
     */
    private fun guessGpuFamily(soc: String): String {
        val s = soc.lowercase()
        return when {
            // 联发科入门/中端：MT6765(P35/GE8320)、MT6762(P22)、MT6739、MT6761
            s.contains("mt6765") || s.contains("mt6762") || s.contains("mt6739") ||
                s.contains("mt6761") || s.contains("mt6737") || s.contains("mt6580") -> "PowerVR"
            // 高通入门：425/429/430/439/450 与老 8x16/8x39
            s.contains("msm8916") || s.contains("msm8939") || s.contains("msm8953") ||
                s.contains("sdm429") || s.contains("sdm439") || s.contains("sdm450") ||
                s.contains("sdm425") || s.contains("sdm430") -> "Adreno低端"
            s.contains("mali") || s.contains("exynos") -> "Mali"
            s.contains("kirin") || s.contains("hi6") || s.contains("hi8") -> "Mali"
            else -> "未知"
        }
    }

    /**
     * 启动前准备：低配只在第一次进入时把分辨率降到 80%（用户之后自己改过就不再动）。
     */
    fun prepare(context: Context) {
        val p = profile(context)
        val sp = prefs(context)
        if (p.tier != Tier.LOW) {
            Logger.info(TAG, "档位非低配，保持用户当前分辨率为 ${AllSettings.resolutionRatio.getValue()}%")
            return
        }
        if (sp.getBoolean(KEY_RES_APPLIED, false)) {
            Logger.info(TAG, "低配分辨率预设已应用过，本次不动用户设置（当前 ${AllSettings.resolutionRatio.getValue()}%）")
            return
        }
        val current = AllSettings.resolutionRatio.getValue()
        if (current >= 100) {
            AllSettings.resolutionRatio.save(80)
            AllSettings.resolutionRatio.updateState(80)
            Logger.info(TAG, "低配设备首次启动：渲染分辨率 100% -> 80%（可在游戏内菜单随时改回）")
        }
        sp.edit().putBoolean(KEY_RES_APPLIED, true).apply()
    }

    /**
     * 把「少进 C 层」的翻译层参数塞进环境变量。
     * 只在 gl4es 家族生效；用户/渲染器已经写过的键不覆盖。
     */
    fun applyGl4esEnv(env: MutableMap<String, String>) {
        val renderer = runCatching { Renderers.getCurrentRenderer() }.getOrNull() ?: return
        val lib = runCatching { renderer.getRendererLibrary() }.getOrNull().orEmpty()
        val id = runCatching { renderer.getRendererId() }.getOrNull().orEmpty()
        val isGl4es = lib.contains("gl4es", ignoreCase = true) ||
            id.startsWith("opengles", ignoreCase = true)
        if (!isGl4es) {
            Logger.info(TAG, "渲染器 $id 不是 gl4es 家族，不注入翻译层参数")
            return
        }

        fun put(key: String, value: String) {
            if (!env.containsKey(key)) env[key] = value
        }

        // 已验证的安全项：跳过逐调用错误检查/扩展测试/横幅
        put("LIBGL_NOERROR", "1")
        put("LIBGL_NOTEST", "1")
        put("LIBGL_NOBANNER", "1")

        val p = cachedProfile ?: return
        if (safeMode) {
            Logger.warning(TAG, "上次启动失败过两次，已进安全模式：只保留 NOERROR/NOTEST，不启用批处理")
            return
        }

        when (p.tier) {
            Tier.LOW -> {
                // 批处理：连续绘制合并提交，减少进 native 的次数（这也是「逐调用卡顿」的正解）
                put("LIBGL_BATCH", "1")
                put("LIBGL_BEGINEND", "1")
                put("LIBGL_FASTMATH", "1")
                put("LIBGL_SILENTSTUB", "1")
                Logger.info(TAG, "低配：已启用 gl4es 批处理(BATCH/BEGINEND)+FASTMATH")
            }
            Tier.MID -> {
                put("LIBGL_BATCH", "1")
                put("LIBGL_BEGINEND", "1")
                Logger.info(TAG, "中配：已启用 gl4es 批处理(BATCH/BEGINEND)")
            }
            Tier.HIGH -> Logger.info(TAG, "高配：不注入额外翻译层参数")
        }
    }

    /**
     * 低配 / 2 核设备用 Serial GC：堆更小、停顿更短，比 G1 更适合老手机。
     * 中高配保持 JVM 默认（G1），只由 GameLauncher 继续压 MaxGCPauseMillis。
     */
    fun jvmArgs(context: Context): List<String> {
        val p = profile(context)
        return when {
            p.tier == Tier.LOW -> listOf("-XX:+UseSerialGC")
            p.cores <= 4 -> listOf("-XX:+UseSerialGC")
            else -> emptyList()
        }
    }

    /**
     * 渲染器自动回退：选中的渲染器如果本机没有打包对应 .so，直接切到列表里第一个
     * 本机确实存在的渲染器，避免「选了个不存在的后端 → 黑屏/闪退」。
     */
    fun resolveRendererWithFallback(context: Context): RendererInterface? {
        val current = runCatching { Renderers.getCurrentRenderer() }.getOrNull() ?: return null
        if (nativeLibExists(current)) return null

        val currentLib = runCatching { current.getRendererLibrary() }.getOrNull()
        Logger.warning(
            TAG,
            "渲染器「${current.getRendererName()}」需要的库 ${currentLib ?: "(无)"} 不在本机，开始自动回退"
        )

        val candidates = Renderers.getRenderers()
            .filter { it.getUniqueIdentifier() != current.getUniqueIdentifier() }
            .filter { nativeLibExists(it) }

        val ordered = candidates.sortedBy { r ->
            // 同家族优先（gl4es 换 gl4es 不会引入新的桌面 GL 能力差异）
            val sameFamily = r.getRendererLibrary().contains("gl4es", true) ==
                currentLib.orEmpty().contains("gl4es", true)
            if (sameFamily) 0 else 1
        }
        val pick = ordered.firstOrNull()
        if (pick == null) {
            Logger.error(TAG, "所有渲染器的库都不在本机，保持原选择并在日志里报错")
            return null
        }
        Logger.info(TAG, "自动回退到「${pick.getRendererName()}」（${pick.getRendererLibrary()}）")
        return pick
    }

    private fun nativeLibExists(renderer: RendererInterface): Boolean {
        val lib = runCatching { renderer.getRendererLibrary() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return false
        val dir = PathManager.DIR_NATIVE_LIB
        return File(dir, lib).isFile || runCatching {
            File(dir, "lib${lib.removePrefix("lib")}").isFile
        }.getOrDefault(false)
    }

    /**
     * 每次启动结束后调用：短时间失败两次 → 进安全模式，自动把批处理关掉。
     * 「成功」的判定是进程存活超过 2 分钟，短命退出视为失败。
     */
    fun onLaunchFinished(context: Context, exitCode: Int, elapsedMs: Long) {
        val p = profile(context)
        val sp = prefs(context)
        if (elapsedMs >= 120_000L) {
            if (sp.getInt(KEY_FAIL_COUNT, 0) != 0) sp.edit().putInt(KEY_FAIL_COUNT, 0).apply()
            return
        }
        if (exitCode == 0) return
        if (p.tier == Tier.HIGH) return

        val fails = sp.getInt(KEY_FAIL_COUNT, 0) + 1
        sp.edit().putInt(KEY_FAIL_COUNT, fails).apply()
        Logger.warning(TAG, "启动在 ${elapsedMs}ms 内退出（退出码 $exitCode），累计失败 $fails 次")
        if (fails >= 2 && !safeMode) {
            sp.edit().putBoolean(KEY_SAFE_MODE, true).apply()
            safeMode = true
            Logger.warning(TAG, "连续两次短命退出，已自动进入安全模式（下次启动关闭 BATCH/BEGINEND）")
        }
    }

    fun summary(context: Context): String {
        val p = profile(context)
        val renderer = runCatching { Renderers.getCurrentRenderer().getRendererName() }.getOrNull() ?: "未设置"
        return "KCL Engine：${p.tier.label} · ${p.cores} 核 · ${p.totalRamMb}MB · ${p.gpuFamily} · " +
            "分辨率 ${AllSettings.resolutionRatio.getValue()}% · 渲染器 $renderer" +
            if (safeMode) " · 安全模式" else ""
    }
}
