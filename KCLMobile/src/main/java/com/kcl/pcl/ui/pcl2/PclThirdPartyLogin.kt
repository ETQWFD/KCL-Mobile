/*
 * KCL Mobile — PCL2 UI port
 *
 * 第三方登录（authlib-injector / 统一通行证）的全局询问框。
 *
 * 版本设置里选了「第三方登录」之后，启动该版本时如果本地还没有对应服务器的账户，
 * 就直接在这里问账号密码、当场登录并切成当前账户 —— 与 PCL 在登录页里放一块
 * 「第三方登录」面板是同一个意思，只是把它挪到了真正需要它的那一刻。
 *
 * 为什么做成全局挂起询问（而不是挂在版本设置页里）：启动可以从首页、下载页、
 * 版本列表、服务器列表四个地方发起，而账户可能在启动前一刻才需要 —— 挂进页面
 * 会漏掉大部分链路（这个坑在「整合包装之前先问版本名」那轮已经踩过一次）。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.kcl.pcl.ui.pcl2

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kcl.pcl.game.account.Account
import com.kcl.pcl.game.account.AccountsManager
import com.kcl.pcl.game.account.auth_server.AuthServerHelper
import com.kcl.pcl.game.account.auth_server.data.AuthServer
import com.kcl.pcl.game.account.auth_server.models.AuthResult
import com.kcl.pcl.ui.androidText
import com.kcl.pcl.ui.pcl2.components.PclDualInputDialog
import com.kcl.pcl.ui.pcl2.components.PclNoticeDialog
import com.kcl.pcl.ui.pcl2.components.PclSelectDialog
import com.kcl.pcl.ui.pcl2.data.PclAuthLoginSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 一条待完成的第三方登录。
 *
 * [answer] 是给启动链路等的：登录成功给出账户，取消 / 失败给出 null。
 */
class PclThirdPartyLoginRequest(
    val serverName: String,
    val baseUrl: String,
    val registerUrl: String,
) {
    internal val answer = CompletableDeferred<Account?>()

    /** 正在跟服务器要凭据（此时输入框已经收起，改成一条「正在登录」提示）。 */
    var busy by mutableStateOf(false)

    /** 上一次尝试的失败原因（非空时先显示错误，关掉再回到输入框）。 */
    var error by mutableStateOf<String?>(null)

    /** 用户已经放弃这一条：后续回调不要再改动界面。 */
    internal var cancelled = false
}

/** 一次登录的结果：成功给账户，失败给原因（两者必有一个）。 */
private class LoginOutcome(
    val account: Account?,
    val error: String?,
    /** 用户在角色选择框上放弃了：调用方别再弹输入框追问一遍。 */
    val aborted: Boolean = false,
)

/**
 * 一条待选择的角色 —— 同一个账号在该认证服务器上挂了不止一个角色。
 *
 * [selectProfile] 必须被调用一次，否则 [PclThirdPartyLogin.performLogin] 会一直停在那里
 * （那个 Task 的 `hasMultipleRoles` 分支就是把「选谁」这件事交出来的）。
 * 用户放弃时走 [cancel]，由它自己把这一次登录收掉。
 */
class PclRolePickRequest(
    val serverName: String,
    val profiles: List<AuthResult.AvailableProfiles>,
    internal val selectProfile: (AuthResult.AvailableProfiles) -> Unit,
    internal val cancel: () -> Unit,
)

object PclThirdPartyLogin {
    /** 当前没被处理完的那一条（null = 没有）。 */
    var pending by mutableStateOf<PclThirdPartyLoginRequest?>(null)
        private set

    /** 当前正在等用户挑角色的那一条（null = 没有）。 */
    var pendingRoles by mutableStateOf<PclRolePickRequest?>(null)
        private set

    /** 用户挑了某个角色（[profileId] 来自 [PclRolePickRequest.profiles]）。 */
    fun chooseRole(profileId: String) {
        val request = pendingRoles ?: return
        val profile = request.profiles.firstOrNull { it.id == profileId }
        pendingRoles = null
        if (profile != null) request.selectProfile(profile)
    }

    /** 用户关掉了角色选择框：整条登录作废。 */
    fun cancelRolePick() {
        val request = pendingRoles ?: return
        pendingRoles = null
        request.cancel()
    }

    /**
     * 挂起询问一次第三方登录。
     * @return 登录成功返回账户（`suspendSaveAccount` 已经把它设成当前账户）；取消返回 null
     */
    suspend fun ask(
        context: Context,
        serverName: String,
        baseUrl: String,
        registerUrl: String,
    ): Account? {
        // 1）主页面板里已经填过邮箱密码（本次输入或勾了「记住密码」）：
        //    直接拿去换凭据，别再弹一遍框 —— 用户刚在上面填完就要他再填一次是最恼人的。
        val saved = PclAuthLoginSettings.current(context, baseUrl)
        if (saved.usable) {
            val quiet = performLogin(context, serverName, baseUrl, registerUrl, saved.email, saved.password)
            if (quiet.account != null) return quiet.account
            // 用户在角色选择框上按了取消：那就是这一次不要登录了，别再弹输入框追问
            if (quiet.aborted) return null
            // 静默登录失败（密码改了 / 服务器抽风）：落到输入框，并把报错原因带上
        }

        // 2）上一条还没答完（极端情况）：放它过去，别把协程挂死
        pending?.let { old ->
            pending = null
            old.cancelled = true
            old.answer.complete(null)
        }
        val request = PclThirdPartyLoginRequest(serverName, baseUrl, registerUrl)
        pending = request
        return request.answer.await()
    }

    /**
     * 真正干活的登录：调认证服务器 → 服务器与账户入库 → 设为当前账户。
     *
     * 不碰界面（没有 busy / error 状态），输入框那条路和静默登录那条路共用它。
     */
    private suspend fun performLogin(
        context: Context,
        serverName: String,
        baseUrl: String,
        registerUrl: String,
        email: String,
        password: String,
    ): LoginOutcome {
        val outcome = CompletableDeferred<LoginOutcome>()
        val helper = AuthServerHelper(
            baseUrl = baseUrl,
            serverName = serverName,
            email = email,
            password = password,
            onSuccess = { account, _ ->
                // 认证服务器先入库：账号管理页要靠它显示「服务器名称 / 注册链接」，
                // 少了这一行，账户在原生界面里会变成一条查不到服务器的孤魂。
                runCatching {
                    AccountsManager.saveAuthServer(
                        AuthServer(
                            baseUrl = baseUrl,
                            serverName = serverName,
                            register = registerUrl.ifBlank { null },
                        )
                    )
                }
                account.downloadYggdrasil()
                // 保存的同时会把当前账户切到它
                AccountsManager.suspendSaveAccount(account)
                if (!outcome.isCompleted) outcome.complete(LoginOutcome(account, null))
            },
            onFailed = { th ->
                if (!outcome.isCompleted) {
                    outcome.complete(LoginOutcome(null, th.message ?: th.javaClass.simpleName))
                }
            },
        )
        // 一个账号可能有多个角色（LittleSkin 之类允许一个邮箱建多个角色）。
        // 只有一个就照旧直接进；有多个就把这一层交给 [PclRolePickRequest] ——
        // 用户选完，`select` 才会被调用，Task 里的 `refresh` 才会继续跑完，
        // 最终还是会回到上面那个 `onSuccess`。
        helper.createNewAccount(context) { profiles, select ->
            if (profiles.isEmpty()) {
                // 服务器返回了空角色表（账号还没建角色）：收掉，别让协程一直挂着
                if (!outcome.isCompleted) {
                    outcome.complete(LoginOutcome(null, "该账号在「$serverName」上还没有角色，请先到服务器网站创建角色。"))
                }
            } else if (profiles.size == 1) {
                select(profiles.first())
            } else {
                pendingRoles = PclRolePickRequest(
                    serverName = serverName,
                    profiles = profiles,
                    selectProfile = { profile -> select(profile) },
                    cancel = {
                        if (!outcome.isCompleted) {
                            outcome.complete(
                                LoginOutcome(null, "没有选择角色，本次登录已取消。", aborted = true)
                            )
                        }
                    },
                )
            }
        }
        return outcome.await()
    }

    /** 提交账号密码，开始登录（输入框那条路）。 */
    fun submit(
        scope: CoroutineScope,
        context: Context,
        email: String,
        password: String,
    ) {
        val request = pending ?: return
        if (email.isBlank() || password.isBlank()) {
            request.error = "请输入邮箱 / 用户名与密码。"
            return
        }
        request.error = null
        request.busy = true

        scope.launch {
            val outcome = performLogin(
                context = context,
                serverName = request.serverName,
                baseUrl = request.baseUrl,
                registerUrl = request.registerUrl,
                email = email,
                password = password,
            )
            // 用户中途点过取消：结果回来也不再落库 / 切账户
            if (request.cancelled) return@launch
            request.busy = false
            when {
                outcome.account != null -> {
                    pending = null
                    request.answer.complete(outcome.account)
                }
                // 角色选择框上自己按了取消：那就是不要登录了，别再把「登录失败」糊人一脸
                outcome.aborted -> {
                    pending = null
                    request.answer.complete(null)
                }
                else -> request.error = outcome.error ?: "登录失败"
            }
        }
    }

    /**
     * 关掉输入框（用户点了取消 / 点了外面）。
     *
     * **正在登录时不能取消** —— `PclDualInputDialog` 的「登录」按钮点完也会走一次
     * `onDismiss`，如果这里无条件取消，刚提交的登录就会被自己打断。
     */
    fun cancel() {
        val request = pending ?: return
        // 正在登录、或者刚报过错（错误提示还挂着）时都不算「用户放弃」：
        // 前者不能自己打断自己，后者要在关掉错误后回到输入框重试。
        if (request.busy || request.error != null) return
        request.cancelled = true
        pending = null
        request.answer.complete(null)
    }

    /** 「正在登录」提示上的「取消」：这次是真的放弃（结果回来也不再用它）。 */
    fun abort() {
        val request = pending ?: return
        request.cancelled = true
        pending = null
        request.answer.complete(null)
    }

    /** 关掉错误提示，回到输入框重试。 */
    fun clearError() {
        pending?.error = null
    }
}

/**
 * 挂在窗体最外层的第三方登录询问框（见 [PclThirdPartyLogin]）。
 *
 * 四种状态互斥：角色选择 → 错误 → 正在登录 → 输入框，不会叠两层弹窗。
 */
@Composable
fun PclThirdPartyLoginHost() {
    // 角色选择放在最前面：它出现时这一趟登录的成败就取决于用户点哪一行，
    // 底下的「正在登录」遮罩和输入框都得让开（那个 Task 正卡在 `hasMultipleRoles` 里等）。
    PclThirdPartyLogin.pendingRoles?.let { roles ->
        PclSelectDialog(
            title = "「${roles.serverName}」上有多个角色，请选择要用的那一个",
            options = roles.profiles.map { it.id to it.name },
            current = null,
            onSelect = { PclThirdPartyLogin.chooseRole(it) },
            onDismiss = { PclThirdPartyLogin.cancelRolePick() },
        )
        return
    }

    val request = PclThirdPartyLogin.pending ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    when {
        request.error != null -> PclNoticeDialog(
            title = "第三方登录失败",
            content = request.error.orEmpty(),
            onDismiss = { PclThirdPartyLogin.clearError() },
        )

        request.busy -> PclNoticeDialog(
            title = "第三方登录",
            content = "正在登录「${request.serverName}」……\n\n" +
                    "服务器：${request.baseUrl}",
            confirmText = "取消",
            onDismiss = { PclThirdPartyLogin.abort() },
        )

        else -> PclDualInputDialog(
            title = "第三方登录",
            label1 = "「${request.serverName}」的邮箱 / 用户名",
            label2 = "密码",
            hint2 = "密码只用于向该认证服务器换取凭据",
            confirmText = "登录",
            onConfirm = { email, password ->
                PclThirdPartyLogin.submit(scope, context, email, password)
            },
            onDismiss = { PclThirdPartyLogin.cancel() },
        )
    }
}
