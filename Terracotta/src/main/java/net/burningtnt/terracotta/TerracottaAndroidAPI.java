package net.burningtnt.terracotta;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <p>An API to handle Terracotta Android.</p>
 *
 * <p>Unlike normal JNI bindings, relocating this class to another package is supported.</p>
 *
 * <h1>State Definition</h1>
 *
 * <p>For Android platform, developers must invoke {@link #initialize} with a {@link VpnServiceCallback} to initialize the rust backend.
 * Then, {@link #getState()}, {@link #setWaiting()}, {@link #setGuesting}, {@link #setScanning} are available to hook states from Terracotta.</p>
 *
 * <p>All methods here are thread-safe and can be invoked concurrently from multiple threads.</p>
 *
 * <p>For each state, self-increased {@code index} and {@code state} fields are provided.
 * A state with a greater {@code index} should be considered as a new state, while {@code state} reveals the type of the specific state.</p>
 *
 * <p>For state definitions, view <a href="https://github.com/HMCL-dev/HMCL/blob/main/HMCL/src/main/java/org/jackhuang/hmcl/terracotta/TerracottaState.java#L108-L120">all subclasses of Ready</a></p>
 *
 * <h1>VpnService</h1>
 *
 * <p>Terracotta will submit VpnService Requests when EasyTier is acquiring one.
 * To configure the callback for receiving requests, see {@link #initialize}</p>
 *
 * <p>When receiving one, {@link #getPendingVpnServiceRequest()} is available to get the pending request.
 * Developer must make sure either {@link VpnServiceRequest#startVpnService} or {@link VpnServiceRequest#reject()} is invoked,
 * or Terracotta would stuck and EasyTier cannot submit a new VpnService Request.</p>
 *
 * <p>The VpnServiceRequest must be fulfilled in 30 seconds, or it will be considered as timeout.</p>
 *
 * <h1>Panic</h1>
 *
 * <p>A RuntimeException will be thrown if an error occured in native level.</p>
 */
@Keep
public final class TerracottaAndroidAPI {
    /**
     * <p>Callback for receiving VpnService Requests</p>
     *
     * <p>When receiving one, {@link #getPendingVpnServiceRequest()} is available to get the pending request.
     * Developer must make sure either {@link VpnServiceRequest#startVpnService} or {@link VpnServiceRequest#reject()} is invoked,
     * or Terracotta would stuck and EasyTier cannot submit a new VpnService Request.</p>
     *
     * @implNote The VpnServiceRequest must be fulfilled in 30 seconds, or it will be considered as timeout.
     */
    public interface VpnServiceCallback {
        void onStartVpnService();
    }

    /**
     * <p>A VpnService Request submitted by Terracotta. See {@link VpnServiceCallback}</p>
     */
    public interface VpnServiceRequest {
        /**
         * Create a Vpn Connection and fulfill the VpnService Request.
         *
         * @param builder A pre-configured VpnService builder.
         * @return The established Vpn Connection. Developers must close this file descriptor after EasyTier exits.
         * @throws RuntimeException if {@link VpnService.Builder#establish()} returns null.
         * @implNote Developers is able to configure the builder before passing it into Terracotta
         * to fully-custom the connection.
         */
        ParcelFileDescriptor startVpnService(VpnService.Builder builder);

        /**
         * <p>Reject the VpnServiceRequest.</p>
         */
        void reject();
    }

    /**
     * <p>Metadata of Terracotta Android</p>
     */
    public static final class Metadata {
        private final String terracottaVersion;

        private final long terracottaCompileTime;

        private final String easyTierVersion;

        public Metadata(String terracottaVersion, long terracottaCompileTime, String easyTierVersion) {
            this.terracottaVersion = terracottaVersion;
            this.terracottaCompileTime = terracottaCompileTime;
            this.easyTierVersion = easyTierVersion;
        }

        /**
         * @return Terracotta Android version.
         */
        public String getTerracottaVersion() {
            return terracottaVersion;
        }

        /**
         * @return Terracotta Android compile timestamp. The format is identical with {@link System#currentTimeMillis()}.
         */
        public long getTerracottaCompileTime() {
            return terracottaCompileTime;
        }

        /**
         * @return EasyTier version.
         */
        public String getEasyTierVersion() {
            return easyTierVersion;
        }
    }

    static {
        System.setProperty("net.burningtnt.terracotta.native_location", TerracottaAndroidAPI.class.getName().replace('.', '/'));
        System.loadLibrary("terracotta");
    }

    private static volatile VpnServiceRequest pendingRequest = null;

    private static final class RuntimeContext {
        private final VpnServiceCallback vpnServiceCallback;

        private final RandomAccessFile logging;

        public RuntimeContext(VpnServiceCallback vpnServiceCallback, RandomAccessFile logging) {
            this.vpnServiceCallback = vpnServiceCallback;
            this.logging = logging;
        }
    }

    private static volatile RuntimeContext runtimeContext = null;

    /**
     * <p>Get current pending VpnService Request.</p>
     *
     * @return The pending VpnService Request.
     * @throws IllegalStateException if no pending VpnService Request exists.
     */
    public static VpnServiceRequest getPendingVpnServiceRequest() {
        VpnServiceRequest handle = pendingRequest;
        if (handle == null) {
            throw new IllegalStateException("There's no pending VpnService request.");
        }
        return handle;
    }

    /**
     * <p>Initialize the Terracotta Android.</p>
     *
     * @param root The root directory where Terracotta runs.
     * @param logFile The log file written by Terracotta.
     * @param callback A callback to handle VpnService for EasyTier. See {@link VpnServiceCallback} for more information.
     */
    public static synchronized Metadata initialize(String root, String logFile, VpnServiceCallback callback) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(logFile, "logFile");
        Objects.requireNonNull(callback, "callback");

        if (root.isEmpty()) {
            throw new IllegalArgumentException("The path {root} cannot be an empty string.");
        }

        if (logFile.isEmpty()) {
            throw new IllegalArgumentException("The path {logFile} cannot be an empty string.");
        }

        if (runtimeContext != null) {
            throw new IllegalStateException("Terracotta Android has already started.");
        }

        File base = new File(root, "rs");
        base.mkdirs();
        if (!base.isDirectory()) {
            throw new RuntimeException("Cannot create net.burningtnt.terracotta/rs directory.");
        }

        RandomAccessFile logging;
        int fd;
        try {
            logging = new RandomAccessFile(new File(logFile), "rw");
            fd = ParcelFileDescriptor.dup(logging.getFD()).detachFd();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        int code = start0(base.getAbsolutePath(), fd);
        if (code != 0) {
            throw new RuntimeException("Cannot start Terracotta Android: " + code);
        }

        runtimeContext = new RuntimeContext(callback, logging);

        String[] parts = getMetadata0().split("\0", 4);
        if (parts.length != 3) {
            throw new AssertionError("Should NOT be here.");
        }
        return new Metadata(parts[0], Long.parseLong(parts[1]), parts[2]);
    }

    /**
     * <p>Fetch current state from Terracotta Android.</p>
     *
     * @return A json representing the current state. See {@link TerracottaAndroidAPI} for state definitions.
     * @throws IllegalStateException if Terracotta Android hasn't been initialized.
     * @implNote Usually, this method doesn't take a long time to fetch states.
     * However, when initializing the EasyTier, state fetching may block for ~1 seconds.
     */
    public static String getState() {
        assertStarted();
        return getState0();
    }

    /**
     * <p>Set Terracotta Android into 'waiting' state.</p>
     *
     * @throws IllegalStateException if Terracotta Android hasn't been initialized.
     * @implNote Usually, this method doesn't take a long time to fetch states.
     * However, when initializing the EasyTier, state fetching may block for ~1 seconds.
     */
    public static void setWaiting() {
        assertStarted();
        setWaiting0();
    }

    /**
     * <p>Set Terracotta Android into 'host-scanning' state.</p>
     *
     * @param room       the room code, which may be used if it's valid.
     * @param player     the player's name. A default value will be taken if it's null.
     * @param extraNodes extra public server nodes for EasyTier.
     * @throws IllegalStateException if Terracotta Android hasn't been initialized.
     * @implNote Usually, this method doesn't take a long time to fetch states.
     * However, when initializing the EasyTier, state fetching may block for ~1 seconds.
     */
    public static void setScanning(@Nullable String room, @Nullable String player, @Nullable List<String> extraNodes) {
        assertStarted();
        setScanning0(room, player, extraNodes == null ? null : String.join("\0", extraNodes));
    }

    /**
     * <p>Set Terracotta Android into 'guest-connecting' state.</p>
     *
     * @param room       the room code. False will be returned if it's invalid.
     * @param player     the player's name. A default value will be taken if it's null.
     * @param extraNodes extra public server nodes for EasyTier.
     * @return True if room code is valid, false otherwise.
     * @throws IllegalStateException if Terracotta Android hasn't been initialized.
     * @throws NullPointerException  if room is null.
     * @implNote Usually, this method doesn't take a long time to fetch states.
     * However, when initializing the EasyTier, state fetching may block for ~1 seconds.
     */
    public static boolean setGuesting(String room, @Nullable String player, @Nullable List<String> extraNodes) {
        Objects.requireNonNull(room, "room");

        assertStarted();
        return setGuesting0(room, player, extraNodes == null ? null : String.join("\0", extraNodes));
    }

    /**
     * Room types supported by Terracotta Android
     */
    public enum RoomType {
        TERRACOTTA_LEGACY, PCL2CE, SCAFFOLDING
    }

    /**
     * <p>Parse the given room code.</p>
     *
     * @param room A room code.
     * @return The type of the room, or null if invalid.
     * @throws NullPointerException if room is null
     * @implNote Though this method is fast enough to be invoked in UI thread,
     * dispatch all invocation to a separated worker is better in order to
     * prevent potential UI freezing issues.
     */
    public static RoomType parseRoomCode(String room) {
        Objects.requireNonNull(room, "room");

        assertStarted();
        switch (verifyRoomCode0(room)) {
            case -1:
                return null;
            case 1:
                return RoomType.TERRACOTTA_LEGACY;
            case 2:
                return RoomType.PCL2CE;
            case 3:
                return RoomType.SCAFFOLDING;
            default:
                throw new AssertionError("Should NOT be here.");
        }
    }

    /**
     * <p>Collect logs of Terracotta Android.</p>
     *
     * <p>Developers must immediately copy all data out of the returned reader and close it.
     * Otherwise all methods, except 'parseRoomCode', will be blocked.</p>
     *
     * @return A reader containing logs.
     * @throws RuntimeException if logging export is unsupported.
     */
    public static Reader collectLogs() throws IOException {
        assertStarted();

        long ptr = prepareExportLogs0();
        if (ptr == 0) {
            throw new RuntimeException("Cannot export logs from Terracotta Android.");
        }

        RandomAccessFile file = runtimeContext.logging;
        file.seek(0);

        return new BufferedReader(new InputStreamReader(new InputStream() {
            private final AtomicBoolean closed = new AtomicBoolean(false);

            @Override
            public int read() throws IOException {
                assertOpen();
                return file.read();
            }

            @Override
            public int read(byte[] b) throws IOException {
                assertOpen();
                return file.read(b);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                assertOpen();
                return file.read(b, off, len);
            }

            @Override
            public int available() throws IOException {
                assertOpen();
                return Math.toIntExact(file.length() - file.getFilePointer());
            }

            @Override
            public void close() throws IOException {
                super.close();
                cleanup();
            }

            @Override
            protected void finalize() throws Throwable {
                super.finalize();
                cleanup();
            }

            private void assertOpen() throws IOException {
                if (closed.get()) {
                    throw new IOException("Stream has already been closed");
                }
            }

            private void cleanup() {
                if (closed.compareAndSet(false, true)) {
                    finishExportLogs0(ptr);
                }
            }
        }, StandardCharsets.UTF_8));
    }

    /**
     * @deprecated This API is exposed for debug purpose.
     */
    @Deprecated
    public static void panic() {
        panic0();
        throw new AssertionError("Should NOT be here: A RuntimeException should be thrown in panic0");
    }

    private static final long FD_PENDING = ((long) Integer.MAX_VALUE) + 1;
    private static final long FD_REJECT = FD_PENDING + 1;

    /**
     * {@link VpnService.Builder#establish()} 返回 null / 抛异常时的最大重试次数与间隔。
     * 给系统级 VPN 切换的瞬时抖动留几拍，避免一次空返回就 reject 成 tun_fd=-1。
     */
    private static final int VPN_ESTABLISH_MAX_ATTEMPTS = 3;
    private static final long VPN_ESTABLISH_RETRY_INTERVAL_MS = 400L;

    @Keep
    @SuppressWarnings("unused") // Native callback
    private static int onVpnServiceStateChanged(byte ip1, byte ip2, byte ip3, byte ip4, short network_length, String cidr) throws UnknownHostException {
        if (pendingRequest != null) {
            // 修复：上游在此处 throw AssertionError，同样会冒回 JNI 闪退。
            // 并发/重复请求时拒绝新请求即可，不崩溃。
            Log.w("TerracottaAndroidAPI", "Duplicate VPN request while one is pending, returning -1.");
            return -1;
        }

        AtomicLong fd = new AtomicLong(FD_PENDING);
        InetAddress address = InetAddress.getByAddress(new byte[]{ip1, ip2, ip3, ip4});

        pendingRequest = new VpnServiceRequest() {
            @Override
            public ParcelFileDescriptor startVpnService(VpnService.Builder builder) {
                // 修复：整个隧道建立过程包住所有异常（低内存设备上 establish() 可能抛
                // IllegalStateException、CIDR 解析失败等），统一走 reject 失败路径，
                // 绝不让任何 Throwable 冒回 JNI 把启动器带崩。
                try {
                    builder.addAddress(address, network_length)
                            .addDnsServer("223.5.5.5")
                            .addDnsServer("114.114.114.114");

                    if (!cidr.isEmpty()) {
                        for (String part : cidr.split("\0")) {
                            String[] parts = part.split("/", 3);
                            if (parts.length != 2) {
                                throw new IllegalArgumentException("Illegal CIDR: " + Arrays.toString(parts));
                            }
                            builder.addRoute(parts[0], Integer.parseInt(parts[1]));
                        }
                    }

                    // establish() 返回 null 通常意味着「VPN 授权被撤销 / 另一个 VPN 抢占」，
                    // 但系统在 VPN 切换瞬间也会偶发瞬时失败。实测日志里 tun_fd=-1 就是
                    // 一次空返回直接 reject 的结果：原生层干等 30 秒超时、用户毫无反馈。
                    // 这里有限次、短间隔重试，仍失败才走 reject 失败路径（启动器不崩）。
                    ParcelFileDescriptor connection = null;
                    RuntimeException lastError = null;
                    for (int attempt = 1; attempt <= VPN_ESTABLISH_MAX_ATTEMPTS; attempt++) {
                        try {
                            connection = builder.establish();
                        } catch (RuntimeException e) {
                            lastError = e;
                        }
                        if (connection != null || attempt == VPN_ESTABLISH_MAX_ATTEMPTS) {
                            break;
                        }
                        Log.w("TerracottaAndroidAPI", "establish() returned null (attempt " + attempt + "/"
                                + VPN_ESTABLISH_MAX_ATTEMPTS + "), retrying in " + VPN_ESTABLISH_RETRY_INTERVAL_MS + "ms.");
                        try {
                            Thread.sleep(VPN_ESTABLISH_RETRY_INTERVAL_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    if (connection == null) {
                        throw lastError != null
                                ? lastError
                                : new RuntimeException("Cannot establish a VPN connection after "
                                        + VPN_ESTABLISH_MAX_ATTEMPTS + " attempts.");
                    }

                    fd.set(connection.getFd());
                    return connection;
                } catch (Throwable t) {
                    Log.e("TerracottaAndroidAPI", "Failed to establish VPN tunnel after retries, rejecting request.", t);
                    fd.set(FD_REJECT);
                    return null;
                }
            }

            @Override
            public void reject() {
                fd.set(FD_REJECT);
            }
        };

        TerracottaAndroidAPI.runtimeContext.vpnServiceCallback.onStartVpnService();

        long timestamp = System.currentTimeMillis();
        while (true) {
            long value = fd.get();
            if (value == FD_PENDING) {
                if (System.currentTimeMillis() - timestamp >= 30000) {
                    // 30 秒内没人建立隧道（界面已销毁、事件丢了、授权框被晾在一边）。
                    //
                    // 上游的做法是这里直接 `throw new IllegalStateException()`，异常冒回 JNI
                    // 后会变成启动器里的「应用程序意外崩溃了」并杀掉整个进程 —— 用户什么都没干
                    // 就闪退了。改成返回 -1：EasyTier 走「拿不到 tun fd」的普通失败路径，
                    // 最坏结果只是这次联机失败，启动器还在。
                    Log.e("TerracottaAndroidAPI", "VpnService Request hasn't been fulfilled in 30s, giving up.");
                    pendingRequest = null;
                    return -1;
                }
                Thread.yield();
            } else if (value == FD_REJECT) {
                pendingRequest = null;
                // 修复：上游在此处直接 throw IllegalStateException，异常冒回 JNI 后会变成
                // 「应用程序意外崩溃了」并把整个启动器杀掉（崩溃栈顶永远是 onVpnServiceStateChanged）。
                // 低内存设备上用户拒绝 VPN 授权、系统未弹出授权框即自动拒绝时必崩。
                // 现在与 30 秒超时分支一样走普通失败路径：本次联机失败，启动器不崩溃。
                Log.w("TerracottaAndroidAPI", "VpnService Request rejected by the application, returning -1 (no crash).");
                return -1;
            } else {
                pendingRequest = null;

                if ((int) value != value) {
                    throw new AssertionError("Should NOT be here.");
                }
                return (int) value;
            }
        }
    }

    private static void assertStarted() {
        if (runtimeContext == null) {
            throw new IllegalStateException("Terracotta Android hasn't started yet.");
        }
    }

    @Keep
    private static native int start0(String baseDir, int loggingFD);

    @Keep
    private static native String getState0();

    @Keep
    private static native void setWaiting0();

    @Keep
    private static native void setScanning0(String room, String player, String extraNodes);

    @Keep
    private static native boolean setGuesting0(String room, String player, String extraNodes);

    @Keep
    private static native int verifyRoomCode0(String room);

    @Keep
    private static native String getMetadata0();

    @Keep
    private static native long prepareExportLogs0();

    @Keep
    private static native void finishExportLogs0(long pointer);

    @Keep
    private static native void panic0();
}