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

package com.kcl.pcl.ui.screens.game.multiplayer

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kcl.pcl.R
import com.kcl.pcl.terracotta.Terracotta
import com.kcl.pcl.terracotta.TerracottaVPNService
import com.kcl.pcl.terracotta.fetchNodes
import com.kcl.pcl.ui.AndroidStringText
import com.kcl.pcl.ui.androidText
import com.kcl.pcl.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.burningtnt.terracotta.TerracottaAndroidAPI

private const val TAG = "TerracottaOperation"

/**
 * 陶瓦联机状态操作
 */
sealed interface TerracottaOperation {
    data object None: TerracottaOperation
    /** 打开陶瓦联机菜单 */
    data object ShowMenu: TerracottaOperation
}

/**
 * 陶瓦联机状态操作
 */
@Composable
fun TerracottaOperation(
    viewModel: TerracottaViewModel,
    onShowToast: (AndroidStringText, Int) -> Unit = { _, _ -> }
) {
    val scope = rememberCoroutineScope()

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val context = viewModel.gameHandler.activity
        if (result.resultCode == Activity.RESULT_OK) {
            val vpnIntent = Intent(context, TerracottaVPNService::class.java).apply {
                action = TerracottaVPNService.ACTION_START
            }
            ContextCompat.startForegroundService(context, vpnIntent)
        } else {
            TerracottaAndroidAPI.getPendingVpnServiceRequest().reject()
            Terracotta.setWaiting(true)
            onShowToast(androidText(R.string.terracotta_permission_vpn), Toast.LENGTH_SHORT)
        }
    }

    DisposableEffect(Unit) {
        viewModel.vpnLauncher = vpnLauncher
        onDispose {
            viewModel.vpnLauncher = null
        }
    }

    when (viewModel.operation) {
        is TerracottaOperation.None -> {}
        is TerracottaOperation.ShowMenu -> {
            val anonymousString = stringResource(R.string.terracotta_player_anonymous)

            val userName: String = remember(viewModel) {
                viewModel.getUserName()
            } ?: anonymousString //未设置，使用“匿名玩家”

            //支持任何房间，实时展示所有玩家配置
            val profiles by viewModel.profiles.collectAsStateWithLifecycle()

            MultiplayerDialog(
                onClose = { viewModel.operation = TerracottaOperation.None },
                dialogState = viewModel.dialogState,
                logOperation = viewModel.dialogLogOperation,
                onShowLog = { viewModel.showLog() },
                onHideLog = { viewModel.hideLog() },
                isWaitingInteractive = viewModel.isWaitingInteractive,
                terracottaVer = viewModel.terracottaVer,
                easyTierVer = viewModel.easyTierVer,
                profiles = profiles,
                onHostRoleClick = {
                    scope.launch(Dispatchers.Main) {
                        viewModel.isWaitingInteractive = false

                        //fetchNodes() 默认分支内部已吞异常返回空列表；但用户在
                        //「设置→联机」里填了非法自定义节点 URL 时，URI(url) 会抛
                        // URISyntaxException，不接住就是协程未捕获异常直接闪退。
                        val nodesResult = runCatching { fetchNodes() }
                        if (nodesResult.isFailure) {
                            Logger.warning(TAG, "Error occurred at \"Terracotta.fetchNodes()\"", nodesResult.exceptionOrNull())
                            onShowToast(androidText(R.string.terracotta_status_exception), Toast.LENGTH_SHORT)
                            viewModel.isWaitingInteractive = true
                            return@launch
                        }
                        val nodeList = withContext(Dispatchers.Default) {
                            nodesResult.getOrThrow().map { node ->
                                node.toString()
                            }
                        }

                        runCatching {
                            Terracotta.setScanning(null, userName, nodeList)
                            viewModel.isWaitingInteractive = false
                        }.onFailure { e ->
                            Logger.warning(TAG, "Error occurred at \"Terracotta.setScanning(null, userName)\", message = ${e.message}")
                            viewModel.isWaitingInteractive = true
                        }
                    }
                },
                onHostCopyCode = { state ->
                    viewModel.copyInviteCode(state)
                },
                onGuestPositive = { roomCode ->
                    scope.launch(Dispatchers.Main) {
                        viewModel.isWaitingInteractive = false

                        //同 onHostRoleClick：自定义节点 URL 非法时 fetchNodes 会抛异常，
                        //这里接住并给可读提示，避免加入房间时整页闪退。
                        val nodesResult = runCatching { fetchNodes() }
                        if (nodesResult.isFailure) {
                            Logger.warning(TAG, "Error occurred at \"Terracotta.fetchNodes()\"", nodesResult.exceptionOrNull())
                            onShowToast(androidText(R.string.terracotta_status_exception), Toast.LENGTH_SHORT)
                            viewModel.isWaitingInteractive = true
                            return@launch
                        }
                        val nodeList = withContext(Dispatchers.Default) {
                            nodesResult.getOrThrow().map { node ->
                                node.toString()
                            }
                        }

                        runCatching {
                            val success = Terracotta.setGuesting(roomCode, userName, nodeList)
                            viewModel.isWaitingInteractive = false
                            if (!success) {
                                onShowToast(androidText(R.string.terracotta_status_waiting_guest_prompt_invalid), Toast.LENGTH_SHORT)
                            }
                        }.onFailure { e ->
                            Logger.warning(TAG, "Error occurred at \"Terracotta.setGuesting(roomCode, userName)\", message = ${e.message}")
                            viewModel.isWaitingInteractive = true
                        }
                    }
                },
                onGuestCopyUrl = { state ->
                    viewModel.copyServerAddress(state)
                },
                onBack = {
                    Terracotta.setWaiting(true)
                },
                onShowToast = { text -> onShowToast(androidText(text), Toast.LENGTH_SHORT) }
            )
        }
    }
}