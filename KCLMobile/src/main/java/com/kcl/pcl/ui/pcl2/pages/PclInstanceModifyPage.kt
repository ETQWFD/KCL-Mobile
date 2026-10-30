/*
 * KCL Mobile — PCL2 UI port
 *
 * 版本管理 →「修改实例」页。
 *
 * 对应 PCL CE 的 `PageInstanceSetup`（修改模式）：
 *   ① 顶部一张实例卡：合并后的版本名 + 「Minecraft | 加载器」；
 *   ② 一张列表卡：Minecraft（右侧铅笔「修改」）+ 各模组加载器（右侧 ✕ 清除 / › 选择）；
 *   ③ 底部居中的「开始重置」胶囊 → 确认弹窗 → 原地重建该实例。
 *
 * 与下载页的关系：加载器清单、互斥规则（[pclLoaderConflict]）、版本列表全部复用
 * 「下载 → 原版游戏」那一页的同一套实现，所以两处的行为天然一致。
 *
 * 「开始重置」走的是 `GameInstaller.modifyVersion()`（`checkVersionExists = false`）：
 * 允许目标版本已经存在，重新下载该实例所需的文件、重装加载器，
 * 最终只替换 `versions/<名字>/<名字>.json` 与 `.jar`，存档 / Mod / 资源包都不动。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.kcl.pcl.ui.pcl2.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kcl.pcl.R
import com.kcl.pcl.game.addons.modloader.ModLoader
import com.kcl.pcl.game.addons.modloader.cleanroom.CleanroomVersion
import com.kcl.pcl.game.addons.modloader.cleanroom.CleanroomVersions
import com.kcl.pcl.game.addons.modloader.fabriclike.fabric.FabricAPIVersions
import com.kcl.pcl.game.addons.modloader.fabriclike.fabric.FabricVersion
import com.kcl.pcl.game.addons.modloader.fabriclike.fabric.FabricVersions
import com.kcl.pcl.game.addons.modloader.fabriclike.quilt.QuiltVersion
import com.kcl.pcl.game.addons.modloader.fabriclike.quilt.QuiltVersions
import com.kcl.pcl.game.addons.modloader.forgelike.forge.ForgeVersion
import com.kcl.pcl.game.addons.modloader.forgelike.forge.ForgeVersions
import com.kcl.pcl.game.addons.modloader.forgelike.neoforge.NeoForgeVersion
import com.kcl.pcl.game.addons.modloader.forgelike.neoforge.NeoForgeVersions
import com.kcl.pcl.game.addons.modloader.modlike.ModVersion
import com.kcl.pcl.game.addons.modloader.optifine.OptiFineVersion
import com.kcl.pcl.game.addons.modloader.optifine.OptiFineVersions
import com.kcl.pcl.game.download.game.GameDownloadInfo
import com.kcl.pcl.game.version.installed.Version
import com.kcl.pcl.game.versioninfo.MinecraftVersion
import com.kcl.pcl.game.versioninfo.MinecraftVersions
import com.kcl.pcl.ui.pcl2.LocalPclNav
// ↓ 这几个都住在 `ui.pcl2`（不是 `ui.pcl2.pages`）：下载页那个文件把包名写成了父包，
//   加载器互斥表与版本分组也都在那里，跨文件复用时必须显式 import。
import com.kcl.pcl.ui.pcl2.PclLoaderKind
import com.kcl.pcl.ui.pcl2.PclMcGroup
import com.kcl.pcl.ui.pcl2.PclMcVersionListView
import com.kcl.pcl.ui.pcl2.pclGroupOf
import com.kcl.pcl.ui.pcl2.pclLoaderConflict
import com.kcl.pcl.ui.pcl2.pclReleaseTime
import com.kcl.pcl.ui.pcl2.PclContent
import com.kcl.pcl.ui.pcl2.PclHintHost
import com.kcl.pcl.ui.pcl2.PclStrings
import com.kcl.pcl.ui.pcl2.PclSubPage
import com.kcl.pcl.ui.pcl2.components.PclCard
import com.kcl.pcl.ui.pcl2.components.PclConfirmDialog
import com.kcl.pcl.ui.pcl2.components.PclExtraTextButton
import com.kcl.pcl.ui.pcl2.components.PclHint
import com.kcl.pcl.ui.pcl2.components.PclHintTheme
import com.kcl.pcl.ui.pcl2.components.PclIcons
import com.kcl.pcl.ui.pcl2.components.PclListItem
import com.kcl.pcl.ui.pcl2.components.PclListItemButton
import com.kcl.pcl.ui.pcl2.components.PclSelectDialog
import com.kcl.pcl.ui.pcl2.components.PclText
import com.kcl.pcl.ui.pcl2.data.LocalPclRuntime
import com.kcl.pcl.ui.pcl2.theme.PclDimens
import com.kcl.pcl.ui.pcl2.theme.pclPalette
import kotlinx.coroutines.launch

/**
 * 「修改实例」二级页面。
 *
 * 只是把 [PclInstanceModifyContent] 包进 [PclSubPage] —— 页面自带返回按钮，
 * 返回后回到「版本管理 → 设置」那一页。
 */
fun pclInstanceModifyPage(version: Version): PclSubPage = PclSubPage(
    title = "修改实例",
    content = { PclInstanceModifyContent(version) },
)

/**
 * 一个加载器在「修改」页里的状态。
 *
 * 用 `Any` 存列表项而不是泛型：七个加载器的类型各不相同（ForgeVersion / FabricVersion
 * / ModVersion …），但它们只需要「显示名」与「交给 GameDownloadInfo 时再转型」两件事，
 * 泛型化反而要在每个调用点上多写一层星投影。
 */
private class PclModifySlot(
    val kind: PclLoaderKind,
    val title: String,
    val logoRes: Int,
    /** 与 `VersionInfo.loaderInfos` 对齐用的枚举值；Fabric API 是 `FABRIC_API`。 */
    val modLoader: ModLoader,
    /** 列表项 → 显示名。 */
    val label: (Any) -> String,
) {
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var items by mutableStateOf<List<Any>>(emptyList())
    var selected by mutableStateOf<Any?>(null)

    /** 是否已经按实例现状预选过（预选只做一次，之后用户的改动不能被覆盖）。 */
    private var settled by mutableStateOf(false)

    /** 实例正在用这个加载器，但列表里找不到同一个版本号（只能退到最新版）。 */
    var unmatched by mutableStateOf(false)

    fun reset() {
        loading = false
        error = null
        items = emptyList()
        selected = null
        settled = false
        unmatched = false
    }

    fun clear() {
        selected = null
        unmatched = false
    }

    /**
     * 拉取版本列表，并把实例当前正在用的那个版本预选上。
     *
     * 预选很关键：用户什么都不改直接点「开始重置」时，重装出来的必须还是原来那套加载器。
     * 万一列表里没有同一个版本号（源上撤包了之类），退到列表第一项并标记 [unmatched]，
     * 界面上会明确写出「当前版本不在列表中，将改用 xxx」—— 总不能因为对不上就把加载器丢掉。
     */
    suspend fun <T : Any> load(currentVersion: String?, fetch: suspend () -> List<T>?) {
        loading = true
        error = null
        runCatching { fetch() }
            .onSuccess { list ->
                val all: List<Any> = list.orEmpty()
                items = all
                if (!settled) {
                    settled = true
                    if (!currentVersion.isNullOrBlank() && all.isNotEmpty()) {
                        val hit = all.firstOrNull { pclVersionMatches(label(it), currentVersion) }
                        selected = hit ?: all.first()
                        unmatched = hit == null
                    }
                }
            }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
        loading = false
    }
}

/**
 * 版本号「大致相等」判定。
 *
 * 同一份信息在各处的写法并不统一：
 *   · Forge 的 `versionName` 是 `1.20.1-47.2.0`，而版本 Json 里只记 `47.2.0`；
 *   · OptiFine 的 `displayName` 是 `1.20.1_HD_U_I6`，`LoaderInfo` 里可能只记 `HD_U_I6`；
 *   · Fabric API 的 `displayName` 是 `0.92.2+1.20.1`，有时只记 `0.92.2`。
 * 所以除了完全相同，还允许「以 - / _ 结尾匹配」与「以 + / - 开头匹配」。
 */
private fun pclVersionMatches(candidate: String, current: String): Boolean {
    val c = candidate.trim().lowercase()
    val want = current.trim().lowercase()
    if (want.isEmpty()) return false
    if (c == want) return true
    if (c.endsWith("-$want") || c.endsWith("_$want")) return true
    if (c.startsWith("$want+") || c.startsWith("$want-")) return true
    return false
}

/**
 * 拉取某一个加载器的版本列表。
 *
 * 初次进页面（七个一起拉）和「点了空行重试」（只拉这一个）共用这一份，
 * 免得两处的 `when` 分支慢慢跑偏 —— 加载器清单与下载页必须完全一致。
 */
private suspend fun pclModifyLoadSlot(
    slot: PclModifySlot,
    mcId: String,
    currentVersion: String?,
) {
    when (slot.modLoader) {
        ModLoader.FORGE -> slot.load(currentVersion) { ForgeVersions.fetchForgeList(mcId) }
        ModLoader.NEOFORGE -> slot.load(currentVersion) {
            NeoForgeVersions.fetchNeoForgeList(gameVersion = mcId)
        }
        ModLoader.FABRIC -> slot.load(currentVersion) { FabricVersions.fetchFabricLoaderList(mcId) }
        ModLoader.FABRIC_API -> slot.load(currentVersion) {
            // 与下载页同序：按发布时间倒序，默认值取第 0 项
            FabricAPIVersions.fetchVersionList(mcId)
                ?.sortedByDescending { it.version.platformDatePublished() }
        }
        ModLoader.QUILT -> slot.load(currentVersion) { QuiltVersions.fetchQuiltLoaderList(mcId) }
        ModLoader.OPTIFINE -> slot.load(currentVersion) {
            OptiFineVersions.fetchOptiFineList(gameVersion = mcId)
        }
        ModLoader.CLEANROOM -> slot.load(currentVersion) { CleanroomVersions.fetchLoaderList(mcId) }
        else -> Unit
    }
}

@Composable
private fun PclInstanceModifyContent(version: Version) {
    val p = pclPalette
    val nav = LocalPclNav.current
    val runtime = LocalPclRuntime.current
    val scope = rememberCoroutineScope()

    val versionInfo = remember(version) { version.getVersionInfo() }
    var mcId by remember { mutableStateOf(versionInfo?.minecraftVersion.orEmpty()) }
    /** 是否正在版本列表里挑 Minecraft 版本（对应 PCL 里点铅笔后切到的版本列表页）。 */
    var pickingMc by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var pickSlot by remember { mutableStateOf<PclModifySlot?>(null) }
    /** 点了「与已选加载器互斥」的行时弹出的确认框（问要不要换过去）。 */
    var conflictSlot by remember { mutableStateOf<PclModifySlot?>(null) }
    var mcRefresh by remember { mutableIntStateOf(0) }

    // ---- Minecraft 版本列表（与下载页同一份缓存）
    var mcVersions by remember { mutableStateOf<List<MinecraftVersion>>(emptyList()) }
    var mcLoading by remember { mutableStateOf(true) }
    var mcError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mcRefresh) {
        mcLoading = true
        mcError = null
        runCatching { MinecraftVersions.refreshVersions(force = mcRefresh > 0) }
            .onFailure { mcError = it.message ?: it.javaClass.simpleName }
        mcVersions = MinecraftVersions.allVersions.value.sortedByDescending { it.pclReleaseTime() }
        mcLoading = false
    }
    val grouped = remember(mcVersions) {
        PclMcGroup.entries
            .map { group -> group to mcVersions.filter { it.pclGroupOf() == group } }
            .filter { it.second.isNotEmpty() }
    }
    val pinned = remember(grouped) {
        val release = grouped.firstOrNull { it.first == PclMcGroup.Release }?.second?.firstOrNull()
        val preview = grouped.firstOrNull { it.first == PclMcGroup.Preview }?.second?.firstOrNull()
        buildList {
            release?.let { add(it to PclStrings.LangDownloadClientReleaseReleaseOn) }
            if (preview != null && (release == null || preview.pclReleaseTime() > release.pclReleaseTime())) {
                add(preview to PclStrings.LangDownloadClientBetaReleaseOn)
            }
        }
    }

    // ---- 七个加载器（顺序与下载页一致；LiteLoader / OptiFabric 没有安装实现，故不列出）
    val slots = remember {
        listOf(
            PclModifySlot(PclLoaderKind.Forge, "Forge", R.drawable.pcl_block_anvil, ModLoader.FORGE) {
                (it as ForgeVersion).versionName
            },
            PclModifySlot(PclLoaderKind.NeoForge, "NeoForge", R.drawable.pcl_block_neoforge, ModLoader.NEOFORGE) {
                (it as NeoForgeVersion).versionName
            },
            PclModifySlot(PclLoaderKind.Fabric, "Fabric", R.drawable.pcl_block_fabric, ModLoader.FABRIC) {
                (it as FabricVersion).version
            },
            PclModifySlot(PclLoaderKind.Fabric, "Fabric API", R.drawable.pcl_block_fabric, ModLoader.FABRIC_API) {
                (it as ModVersion).displayName
            },
            PclModifySlot(PclLoaderKind.Quilt, "Quilt", R.drawable.pcl_block_redstone, ModLoader.QUILT) {
                (it as QuiltVersion).version
            },
            PclModifySlot(PclLoaderKind.OptiFine, "OptiFine", R.drawable.pcl_block_grass_path, ModLoader.OPTIFINE) {
                (it as OptiFineVersion).displayName
            },
            PclModifySlot(PclLoaderKind.Cleanroom, "Cleanroom (1.12.2)", R.drawable.pcl_block_redstone_lamp, ModLoader.CLEANROOM) {
                (it as CleanroomVersion).version
            },
        )
    }
    val forge = slots[0]
    val neoForge = slots[1]
    val fabric = slots[2]
    val fabricApi = slots[3]
    val quilt = slots[4]
    val optifine = slots[5]
    val cleanroom = slots[6]

    // ---- 换 Minecraft 版本就整批重拉加载器列表，并按实例现状重新预选
    LaunchedEffect(mcId, mcRefresh) {
        val id = mcId
        if (id.isBlank()) return@LaunchedEffect
        val info = version.getVersionInfo()
        slots.forEach { it.reset() }
        slots.forEach { slot ->
            launch { pclModifyLoadSlot(slot, id, info?.getLoader(slot.modLoader)?.version) }
        }
    }

    // 选了 Fabric 才会有 Fabric API 这一行（与下载页 `PageDownloadInstall.xaml.vb:266` 一致）；
    // 但如果实例本来装着 Fabric API、用户又刚把 Fabric 清掉，则仍然显示，免得它被"看不见地"保留下来。
    val showFabricApi = fabric.selected != null || fabricApi.selected != null

    // ---- 本页能重装的加载器只有下面这几个。
    //
    // 实例上如果还挂着 Legacy Fabric / Babric / Quilted Fabric API 之类，`GameDownloadInfo`
    // 里根本没有对应字段 —— 一旦「开始重置」，它们会被**悄悄丢掉**，游戏下次就起不来了。
    // 与其静默毁掉，不如把按钮禁掉并说清楚原因。
    val unsupportedLoaders = remember(version) {
        val supported = setOf(
            ModLoader.FORGE, ModLoader.NEOFORGE, ModLoader.CLEANROOM,
            ModLoader.FABRIC, ModLoader.FABRIC_API,
            ModLoader.QUILT, ModLoader.OPTIFINE,
        )
        version.getVersionInfo()?.loaderInfos
            ?.map { it.loader }
            ?.filter { it != ModLoader.UNKNOWN && it !in supported }
            ?.distinct()
            .orEmpty()
    }
    val canReset = unsupportedLoaders.isEmpty()

    val selectedKinds = remember(
        forge.selected, neoForge.selected, fabric.selected,
        quilt.selected, optifine.selected, cleanroom.selected,
    ) {
        buildSet {
            if (forge.selected != null) add(PclLoaderKind.Forge)
            if (neoForge.selected != null) add(PclLoaderKind.NeoForge)
            if (fabric.selected != null) add(PclLoaderKind.Fabric)
            if (quilt.selected != null) add(PclLoaderKind.Quilt)
            if (optifine.selected != null) add(PclLoaderKind.OptiFine)
            if (cleanroom.selected != null) add(PclLoaderKind.Cleanroom)
        }
    }

    /**
     * 打开某个加载器的版本列表。
     *
     * 列表还没拉下来时这一下不是「什么都不发生」，而是重新拉一次并明确告诉用户 ——
     * 以前这里点了没反馈，看起来就是「点加载器没反应」。
     */
    fun openPicker(slot: PclModifySlot) {
        when {
            slot.items.isNotEmpty() -> pickSlot = slot
            slot.loading -> PclHintHost.push(
                "正在获取 ${slot.title} 的版本列表，请稍候…",
                PclHintTheme.Blue,
            )
            else -> {
                PclHintHost.push("正在重新获取 ${slot.title} 的版本列表…", PclHintTheme.Blue)
                slot.reset()
                scope.launch {
                    pclModifyLoadSlot(
                        slot,
                        mcId,
                        version.getVersionInfo()?.getLoader(slot.modLoader)?.version,
                    )
                }
            }
        }
    }

    /**
     * 清掉所有与 [target] 互斥的已选加载器。
     * 只在用户点了确认框里的「清除并选择」之后调用 —— 绝不自动清。
     */
    fun dropConflictsOf(target: PclModifySlot) {
        slots.forEach { other ->
            if (other === target || other.selected == null) return@forEach
            if (pclLoaderConflict(other.kind, mcId, setOf(target.kind)) != null) other.clear()
        }
    }

    fun targetInfo(): GameDownloadInfo = GameDownloadInfo(        gameVersion = mcId,
        // 修改不换名字：目标目录就是原实例目录，`modifyVersion` 也按这个名字找目标
        customVersionName = version.getVersionName(),
        optifine = optifine.selected as? OptiFineVersion,
        forge = forge.selected as? ForgeVersion,
        neoforge = neoForge.selected as? NeoForgeVersion,
        fabric = fabric.selected as? FabricVersion,
        fabricAPI = fabricApi.selected as? ModVersion,
        quilt = quilt.selected as? QuiltVersion,
        cleanroom = cleanroom.selected as? CleanroomVersion,
    )

    fun startReset() {
        if (mcId.isBlank()) {
            PclHintHost.push("还没有确定 Minecraft 版本，无法重置。", PclHintTheme.Red)
            return
        }
        if (runtime.isInstalling) {
            // 不静默返回：直接把用户带到那条任务的进度页，让他自己看见在跑什么
            PclHintHost.push("已有安装任务正在进行，已切到下载管理。", PclHintTheme.Yellow)
            nav.push(pclDownloadManagerPage())
            return
        }
        runtime.modifyGame(version, targetInfo())
        // 与下载页一致：任务进全局任务列表，并切到「下载管理」看进度
        nav.push(pclDownloadManagerPage())
    }

    // 合并后的「Minecraft | 加载器」摘要
    val loaderSummary = buildList {
        forge.selected?.let { add("Forge ${forge.label(it)}") }
        neoForge.selected?.let { add("NeoForge ${neoForge.label(it)}") }
        fabric.selected?.let { add("Fabric ${fabric.label(it)}") }
        fabricApi.selected?.takeIf { showFabricApi }?.let { add("Fabric API ${fabricApi.label(it)}") }
        quilt.selected?.let { add("Quilt ${quilt.label(it)}") }
        optifine.selected?.let { add("OptiFine ${optifine.label(it)}") }
        cleanroom.selected?.let { add("Cleanroom ${cleanroom.label(it)}") }
    }.joinToString(" | ").ifEmpty { "未安装模组加载器" }

    val headerLogo = when {
        fabric.selected != null -> R.drawable.pcl_block_fabric
        forge.selected != null -> R.drawable.pcl_block_anvil
        neoForge.selected != null -> R.drawable.pcl_block_neoforge
        quilt.selected != null -> R.drawable.pcl_block_redstone
        optifine.selected != null -> R.drawable.pcl_block_grass_path
        cleanroom.selected != null -> R.drawable.pcl_block_redstone_lamp
        else -> R.drawable.pcl_block_grass
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PclContent(
            contentPadding = PaddingValues(
                start = 25.dp,
                top = 10.dp,
                end = 25.dp,
                // 底部「开始重置」是浮在内容上的（BottomCenter），给它留出 90 的空档
                bottom = if (pickingMc) 25.dp else 90.dp,
            ),
        ) {
            if (pickingMc) {
                // ---- 选 Minecraft 版本：与下载页同一个列表组件
                PclCard(
                    modifier = Modifier.fillMaxWidth().padding(top = 15.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    PclListItem(
                        title = "返回",
                        info = "当前：$mcId",
                        logo = PclIcons.back,
                        logoScale = 0.87f,
                        height = 42.dp,
                        onClick = { pickingMc = false },
                    )
                }
                Spacer(Modifier.height(15.dp))
                PclMcVersionListView(
                    loading = mcLoading,
                    error = mcError,
                    pinned = pinned,
                    grouped = grouped,
                    onPick = { picked ->
                        mcId = picked.version.id
                        pickingMc = false
                    },
                    onRetry = { mcRefresh++ },
                )
            } else {
                // ---- 实例卡
                PclCard(
                    modifier = Modifier.fillMaxWidth().padding(top = 15.dp),
                    contentPadding = PaddingValues(horizontal = 15.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(headerLogo),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            PclText(text = version.getVersionName(), fontSize = 15, bold = true)
                            Spacer(Modifier.height(2.dp))
                            PclText(
                                text = "$mcId | $loaderSummary",
                                fontSize = 12,
                                color = p.gray2,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(PclDimens.cardGap))

                // ---- 提示（PCL 的 PageInstanceSetup 顶部那几条 Hint）
                if (fabric.selected != null && fabricApi.selected == null) {
                    PclHint(text = PclStrings.LangDownloadInstallFabricApi, theme = PclHintTheme.Red)
                    Spacer(Modifier.height(10.dp))
                }
                if (runtime.isInstalling) {
                    PclHint(
                        text = "正在重置，可在右下角「下载管理」查看进度。",
                        theme = PclHintTheme.Blue,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (mcId.isBlank()) {
                    PclHint(
                        text = "读不到这个实例的 Minecraft 版本号，请点右侧铅笔重新选择一个版本。",
                        theme = PclHintTheme.Red,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (unsupportedLoaders.isNotEmpty()) {
                    PclHint(
                        text = "这个实例还装着 " + unsupportedLoaders.joinToString("、") { it.displayName } +
                            "，当前的重置流程不会重装它（会被丢掉），因此已禁用重置。",
                        theme = PclHintTheme.Red,
                    )
                    Spacer(Modifier.height(10.dp))
                }

                // ---- 版本组合
                PclCard(
                    modifier = Modifier.fillMaxWidth(),
                    title = "版本组合",
                    contentPadding = PaddingValues(start = 10.dp, top = 38.dp, end = 10.dp, bottom = 10.dp),
                ) {
                    // Minecraft：右侧铅笔 = 打开版本列表
                    PclListItem(
                        title = "Minecraft",
                        info = mcId.ifBlank { "未知" },
                        logo = PclIcons.dlGame,
                        height = 44.dp,
                        buttons = listOf(
                            PclListItemButton(
                                logo = PclIcons.loginMsEdit,
                                tooltip = "修改 Minecraft 版本",
                                logoScale = 0.92f,
                            ) { pickingMc = true },
                        ),
                        onClick = { pickingMc = true },
                    )
                    slots.forEachIndexed { index, slot ->
                        // Fabric API 依附于 Fabric，没选 Fabric（且原本也没装）时不显示
                        if (slot === fabricApi && !showFabricApi) return@forEachIndexed
                        // Fabric API 依附于 Fabric，本身不参与互斥判定
                        val reason = if (slot === fabricApi) null else pclLoaderConflict(slot.kind, mcId, selectedKinds)
                        PclModifyLoaderRow(
                            slot = slot,
                            conflictReason = reason,
                            onPick = { openPicker(slot) },
                            // 互斥行以前只弹一条左下角提示，正在看行内文字的人根本收不到
                            // 这个反馈（「点加载器没反应」就是这么来的）。改成一句明确的问答：
                            // 要不要把已选的 X 换成它。
                            onBlocked = { conflictSlot = slot },
                            onClear = { slot.clear() },
                        )
                        if (index != slots.lastIndex) Spacer(Modifier.height(2.dp))
                    }
                }

                Spacer(Modifier.height(12.dp))
                PclText(
                    text = "重置只会重新下载该实例所需的文件并重装加载器，" +
                        "存档、Mod、资源包和版本设置都会保留。",
                    fontSize = 12,
                    color = p.gray2,
                )
            }
        }

        // ---- 底部「开始重置」（MyExtraTextButton，Center/Bottom，Margin=20）
        //
        // 已经有安装/重置任务在跑时，这颗按钮变成「查看安装进度」并直接跳到下载管理：
        // 以前它虽然还能点，但点下去只在左下角闪一句「已有安装任务正在进行」，看上去
        // 就是「点按钮没反应」，而且用户也无从知道那条任务去哪了。
        if (!pickingMc) {
            val installing = runtime.isInstalling
            PclExtraTextButton(
                text = if (installing) "查看安装进度" else "开始重置",
                logo = PclIcons.restartAlt,
                logoScale = 0.95f,
                enabled = canReset,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
                onClick = {
                    if (runtime.isInstalling) {
                        nav.push(pclDownloadManagerPage())
                    } else {
                        showConfirm = true
                    }
                },
            )
        }
    }

    // ---- 加载器版本选择
    pickSlot?.let { slot ->
        val options = slot.items.map { slot.label(it) to slot.label(it) }
        PclSelectDialog(
            title = "选择 ${slot.title} 版本",
            options = options,
            current = slot.selected?.let { slot.label(it) },
            onSelect = { picked ->
                val chosen = slot.items.firstOrNull { slot.label(it) == picked }
                slot.selected = chosen
                slot.unmatched = false
                pickSlot = null
                // 选完必须马上有反馈：行上的副标题会变成所选版本，左下角再补一条提示，
                // 明确告诉用户「下一步点哪里」。光改副标题的话，视线还在弹窗位置的人
                // 会觉得「选完什么都没发生」（这就是「没反应」的由来）。
                if (chosen != null) {
                    PclHintHost.push(
                        "已选择 ${slot.title} ${slot.label(chosen)}。确认无误后点下方「开始重置」。",
                        PclHintTheme.Green,
                    )
                }
            },
            onDismiss = { pickSlot = null },
        )
    }

    // ---- 互斥确认：「要换成它的话，得先清掉已选的 X」
    conflictSlot?.let { slot ->
        val reason = pclLoaderConflict(slot.kind, mcId, selectedKinds) ?: "与已选加载器不兼容"
        val others = slots
            .filter { it !== slot && it.selected != null }
            .filter { pclLoaderConflict(it.kind, mcId, setOf(slot.kind)) != null }
            .joinToString("、") { it.title }
        PclConfirmDialog(
            title = "换成 ${slot.title}",
            content = "当前实例已选择 $others，$reason。\n" +
                "继续将清除 $others，然后再选择 ${slot.title} 的版本。",
            confirmText = "清除并选择",
            onConfirm = {
                dropConflictsOf(slot)
                openPicker(slot)
            },
            onDismiss = { conflictSlot = null },
        )
    }

    // ---- 重置确认（PCL 的 MyMsgText：标题 + 两段说明 + 继续 / 取消）
    if (showConfirm) {
        PclConfirmDialog(
            title = "重置此实例",
            content = "你正在重置当前实例。\n" +
                "KCL Mobile 将会重新联网下载该实例所需的文件，并重新安装模组加载器（如有）。\n" +
                "此操作不会丢失你的存档、模组、资源包等。",
            confirmText = "继续",
            onConfirm = { startReset() },
            onDismiss = { showConfirm = false },
        )
    }
}

/**
 * 一行加载器的副标题。
 *
 * 与下载页的卡片不同，这里的行永远是「已选 / 冲突 / 未选」三态里的一种，
 * 所以文案要能一眼看出「现在这个实例用的是哪个版本」，而不是「点一下去选」。
 */
private fun pclModifySlotInfo(slot: PclModifySlot, conflictReason: String?): String {
    slot.selected?.let { item ->
        val label = slot.label(item)
        return if (slot.unmatched) "$label（当前版本不在列表中）" else label
    }
    return when {
        conflictReason != null -> conflictReason
        slot.loading -> "获取中……"
        slot.error != null -> "获取失败：${slot.error}"
        slot.items.isEmpty() -> "没有可用版本"
        else -> "未安装"
    }
}

/**
 * 「版本组合」卡片里的一行加载器。
 *
 * 为什么不用裸 [PclListItem]：与已选加载器互斥的那几行是禁用态（PCL CE 同样把它们灰掉），
 * 而禁用态的列表项**点不动**，用户点上去完全没有反馈 —— 这就是「点模组加载器没反应」。
 * 所以在禁用行上面盖一层透明点击区，命中时把互斥原因说出来（顺带告诉他怎么换过去）。
 *
 * 已选中的行不受互斥影响：用户得看得见自己选了什么，也还能按 ✕ 清掉。
 */
@Composable
private fun PclModifyLoaderRow(
    slot: PclModifySlot,
    conflictReason: String?,
    onPick: () -> Unit,
    onBlocked: () -> Unit,
    onClear: () -> Unit,
) {
    val blocked = conflictReason != null && slot.selected == null
    val interaction = remember { MutableInteractionSource() }

    Box(modifier = Modifier.fillMaxWidth()) {
        PclListItem(
            title = slot.title,
            info = pclModifySlotInfo(slot, conflictReason),
            logoContent = {
                Image(
                    painter = painterResource(slot.logoRes),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            },
            height = 44.dp,
            enabled = !blocked,
            buttons = buildList {
                if (slot.selected != null) {
                    add(
                        PclListItemButton(
                            logo = PclIcons.close,
                            tooltip = "清除 ${slot.title}",
                            logoScale = 0.85f,
                            onClick = onClear,
                        ),
                    )
                }
                add(
                    PclListItemButton(
                        logo = PclIcons.arrowRight,
                        tooltip = "选择 ${slot.title} 版本",
                        logoScale = 0.9f,
                        onClick = onPick,
                    ),
                )
            },
            onClick = onPick,
        )
        if (blocked) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                    ) { onBlocked() },
            )
        }
    }
}
