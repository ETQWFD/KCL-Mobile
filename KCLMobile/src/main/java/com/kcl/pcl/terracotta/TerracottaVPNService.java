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

package com.kcl.pcl.terracotta;

import static com.kcl.pcl.notification._NotificationIDKt.NOTIFICATION_ID_VPN_REQUEST_CODE;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;

import com.kcl.pcl.R;
import com.kcl.pcl.notification.NotificationChannelData;

import net.burningtnt.terracotta.TerracottaAndroidAPI;

import java.io.IOException;

/**
 * [Modified from FCL](https://github.com/FCL-Team/FoldCraftLauncher/blob/5926006/FCL/src/main/java/com/tungsten/fcl/terracotta/TerracottaVPNService.java)
 */
@SuppressLint("VpnServicePolicy")
public class TerracottaVPNService extends VpnService {

    private static final String TAG                  = "TerracottaVPNService";

    private static final int VPN_NOTIFICATION_ID     = 1;

    /**
     * 「服务先于 JNI 回调起来」的瞬时竞态留几拍重试（间隔很短，避免死循环）。
     * 重试完仍然拿不到 pending 请求，说明 EasyTier 根本不在本进程，不能再静默 stopSelf。
     */
    private static final int PENDING_REQUEST_RETRIES = 3;
    private static final long PENDING_REQUEST_RETRY_INTERVAL_MS = 300L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static final String ACTION_START          = "net.burningtnt.terracotta.action.START";
    public static final String ACTION_STOP           = "net.burningtnt.terracotta.action.STOP";
    public static final String ACTION_REPOST         = "net.burningtnt.terracotta.action.REPOST";
    public static final String ACTION_UPDATE_STATE   = "net.burningtnt.terracotta.action.UPDATE_STATE";

    private static final String EXTRA_FROM_DELETE    = "from_delete";
    public static final String EXTRA_STATE_TEXT      = "terracotta_state_text";

    private NotificationManager notificationManager;
    private int currentStateStringRes = -1;
    private volatile boolean isStopping = false;

    private ParcelFileDescriptor vpnInterface;

    private static boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;

        String action = intent != null ? intent.getAction() : null;
        Log.d(TAG, "onStartCommand, action = " + action);

        if (notificationManager == null) {
            notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        }

        if (ACTION_STOP.equals(action)) {
            isStopping = true;
            cleanup();
            stopForeground(true);
            stopSelf();
            return Service.START_NOT_STICKY;
        }

        if (ACTION_UPDATE_STATE.equals(action) || ACTION_REPOST.equals(action)) {
            currentStateStringRes = getStateTextRes(intent);
        }

        if (!isStopping) {
            //经 startForegroundService 启动后必须及时进入前台，否则系统将抛出
            //ForegroundServiceDidNotStartInTimeException 导致进程崩溃
            try {
                startForeground0(buildVpnNotification());
            } catch (Exception e) {
                Log.w(TAG, "Cannot start VPN foreground service.", e);
                try {
                    TerracottaAndroidAPI.getPendingVpnServiceRequest().reject();
                } catch (IllegalStateException ignored) {
                }
                stopSelf();
                return Service.START_NOT_STICKY;
            }
        }

        if (intent == null) {
            //系统重建服务时不会重新下发联机请求，没有可建立的隧道
            stopSelf();
            return Service.START_NOT_STICKY;
        }

        if (ACTION_UPDATE_STATE.equals(action) || ACTION_REPOST.equals(action)) {
            return Service.START_STICKY;
        }

        isStopping = false;

        Builder vpnBuilder = new Builder().setSession("Terracotta Connection");

        try {
            vpnBuilder.addDisallowedApplication(getPackageName());
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        TerracottaAndroidAPI.VpnServiceRequest request = obtainPendingVpnServiceRequest();
        if (request == null) {
            // 本进程里没有挂起的 VPN 请求。正常路径上只可能是「服务先于 JNI 回调起来」的
            // 瞬时竞态（已在 obtainPendingVpnServiceRequest 里重试）；重试完仍没有 =
            // EasyTier 跑在另一个进程（例如服务被 Manifest 钉在 :game、而 EasyTier 跑在
            // 启动器主进程）。之前这里直接静默 stopSelf()，原生层还会再干等 30 秒超时
            // 返回 tun_fd=-1，用户全程毫无反馈、联机进不去 —— 实测日志的实锤根因。
            Log.e(TAG, "No pending VpnService request in process " + getProcessNameSafe()
                    + " after " + PENDING_REQUEST_RETRIES + " retries; aborting the VPN setup.");
            showVpnFailureToast();
            Terracotta.INSTANCE.setWaiting(false);
            cleanup();
            stopForeground(true);
            stopSelf();
            return Service.START_NOT_STICKY;
        }

        try {
            vpnInterface = request.startVpnService(vpnBuilder);
        } catch (Throwable t) {
            //establish() 失败（VPN 授权被撤销、另一个 VPN 抢了、被系统限流）会抛 RuntimeException。
            //这条路径上抛出去就是未捕获异常 → 启动器闪退，所以这里必须自己收住。
            Log.e(TAG, "Cannot establish the VPN tunnel.", t);
            showVpnFailureToast();
            Terracotta.INSTANCE.setWaiting(false);
            cleanup();
            stopForeground(true);
            stopSelf();
            return Service.START_NOT_STICKY;
        }

        if (vpnInterface == null) {
            // startVpnService 内部 establish() 多次重试仍失败时返回 null（请求已被 reject）。
            // 之前这里不判空直接 START_STICKY：通知显示「已连接」，实际 tun 根本不存在。
            Log.e(TAG, "startVpnService returned null; no tunnel was established.");
            showVpnFailureToast();
            Terracotta.INSTANCE.setWaiting(false);
            cleanup();
            stopForeground(true);
            stopSelf();
            return Service.START_NOT_STICKY;
        }

        return Service.START_STICKY;
    }

    /**
     * 取原生层挂起的 VPN 请求。正常路径上它在服务被拉起之前就由 JNI 回调设好了；
     * 这里只给「服务先于回调起来」的瞬时竞态留几拍短间隔重试，重试完仍没有返回 null。
     */
    private TerracottaAndroidAPI.VpnServiceRequest obtainPendingVpnServiceRequest() {
        for (int attempt = 0; attempt <= PENDING_REQUEST_RETRIES; attempt++) {
            try {
                return TerracottaAndroidAPI.getPendingVpnServiceRequest();
            } catch (IllegalStateException e) {
                if (attempt == PENDING_REQUEST_RETRIES) {
                    return null;
                }
                try {
                    Thread.sleep(PENDING_REQUEST_RETRY_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private String getProcessNameSafe() {
        try {
            return Application.getProcessName();
        } catch (Throwable t) {
            return "(unknown)";
        }
    }

    /**
     * VPN 建立失败时给用户一个明确提示，不再静默吞掉让用户干等。
     * Toast 必须切回主线程发；服务回调本身可能不在主线程。
     */
    private void showVpnFailureToast() {
        mainHandler.post(() -> {
            try {
                Toast.makeText(getApplicationContext(),
                        R.string.terracotta_vpn_establish_failed,
                        Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        });
    }

    @Override
    public void onRevoke() {
        Log.w(TAG, "onRevoke(): preempted by another VPN or revoked by user; tearing down.");
        isStopping = true;
        resetTerracottaState();
        cleanup();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy(): vpn service finished");
        isStopping = true;
        resetTerracottaState();
        cleanup();
        super.onDestroy();
    }

    /**
     * 通知原生层回到「等待大厅」。
     *
     * 这一步会踩进 native 层，native 出错时是直接抛 RuntimeException 的；
     * 服务销毁时抛出去就是没人接的未捕获异常 → 启动器闪退，所以吞掉并留日志。
     */
    private void resetTerracottaState() {
        try {
            Terracotta.INSTANCE.setWaiting(false);
        } catch (Throwable t) {
            Log.w(TAG, "Cannot reset the Terracotta state.", t);
        }
    }

    private int getStateTextRes(Intent intent) {
        int res = -1;
        if (intent.hasExtra(EXTRA_STATE_TEXT)) {
            res = intent.getIntExtra(EXTRA_STATE_TEXT, -1);
        }
        return res;
    }

    private Notification buildVpnNotification() {
        String title = getString(R.string.terracotta_notification_title);

        if (currentStateStringRes == -1) {
            TerracottaState.Ready state = Terracotta.INSTANCE.getState().getValue();
            if (state != null && !(state instanceof TerracottaState.Waiting)) {
                currentStateStringRes = state.localStringRes();
            }
        }

        String contentText;
        Terracotta.Mode mode = Terracotta.INSTANCE.getMode();
        if (mode == null) {
            //联机模式尚未确定，此时通知不展示身份信息
            contentText = getString(R.string.terracotta_notification_state_preparing);
        } else {
            String modeText = mode == Terracotta.Mode.Host ? getString(R.string.terracotta_player_kind_host) : getString(R.string.terracotta_player_kind_guest);
            String stateString;
            if (currentStateStringRes == -1) {
                stateString = "Unknown";
            } else {
                stateString = getString(currentStateStringRes);
            }
            contentText = String.format(getString(R.string.terracotta_notification_desc), modeText, stateString);
        }

        Notification.Builder builder;
        builder = new Notification.Builder(this, NotificationChannelData.TERRACOTTA_VPN_CHANNEL.getChannelId());

        Intent deleteIntent = new Intent(this, TerracottaVPNService.class)
                .setAction(ACTION_REPOST)
                .putExtra(EXTRA_FROM_DELETE, true)
                .putExtra(EXTRA_STATE_TEXT, currentStateStringRes);
        PendingIntent deletePendingIntent = PendingIntent.getService(
                this,
                NOTIFICATION_ID_VPN_REQUEST_CODE,
                deleteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(contentText)
                .setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setDeleteIntent(deletePendingIntent);

        return builder.build();
    }

    private void startForeground0(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(TerracottaVPNService.VPN_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(TerracottaVPNService.VPN_NOTIFICATION_ID, notification);
        }
    }

    private void cleanup() {
        Log.d(TAG, "cleanup(): close tun & cancel notification");

        if (notificationManager != null) {
            notificationManager.cancel(VPN_NOTIFICATION_ID);
        }

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException ignored) {
            }
            vpnInterface = null;
        }

        running = false;
    }

}
