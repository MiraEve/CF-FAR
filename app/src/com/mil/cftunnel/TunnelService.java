package com.mil.cftunnel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 隧道前台服务：编排 DNS 中继 -> 本地端口转发 -> cloudflared 子进程，
 * 负责崩溃重连、通知栏状态与唤醒锁。
 */
public class TunnelService extends Service {

    public static final String ACTION_START = "com.mil.cftunnel.action.START";
    public static final String ACTION_STOP = "com.mil.cftunnel.action.STOP";

    public static final int STATE_IDLE = 0;
    public static final int STATE_STARTING = 1;
    public static final int STATE_RUNNING = 2;
    public static final int STATE_STOPPING = 3;

    /** 供界面读取的全局状态 */
    public static volatile int state = STATE_IDLE;
    public static volatile String publicUrl = null;

    /** 外部访问统计（界面红色提醒用） */
    public static volatile int accessCount = 0;
    public static volatile long lastAccessAt = 0L;
    public static volatile String lastAccessDesc = "";
    public static volatile boolean lastAccessAllowed = true;
    public static volatile String lastAccessIp = "--";
    /** 实际生效的令牌状态 */
    public static volatile boolean tokenActive = false;
    public static volatile int tokenLen = 0;
    /** 网关当前真正生效的开关（不是界面上勾选的值）—— 界面靠它区分"已改"和"已生效" */
    public static volatile boolean headerOnlyActive = false;
    public static volatile boolean unlimitedAttackActive = false;
    /** 当前真正生效的令牌指纹（SHA-256 前 8 字节 hex），用于判断"改了令牌但没重启" */
    public static volatile String activeTokenSig = "";

    private static final String CHANNEL_ID = "cf_tunnel";
    private static final int NOTIF_ID = 1001;
    private static final int MAX_RESTART = 8;

    private final PortForwarder forwarder = new PortForwarder();
    private final DnsBridge dnsBridge = new DnsBridge();
    private volatile CfProcess cf;
    private PowerManager.WakeLock wakeLock;

    private volatile boolean wantRun;
    private volatile int generation;
    private int restartCount;
    private File binFile;
    private File caBundle;

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.init(new File(getFilesDir(), "tunnel.log"));
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            wantRun = false;
            stopTunnel();
            stopSelf();
            return START_NOT_STICKY;
        }

        final Config cfg = loadConfig(intent);
        if (!wantRun) {
            wantRun = true;
            restartCount = 0;
            generation++;
            accessCount = 0;
            lastAccessAt = 0L;
            lastAccessDesc = "";
            publicUrl = null;
            accessCount = 0;
            lastAccessAt = 0L;
            lastAccessDesc = "";
            state = STATE_STARTING;
            startForeground(NOTIF_ID, buildNotification("正在启动", "初始化"));
            new Thread(new Runnable() {
                public void run() {
                    startTunnel(cfg);
                }
            }, "tunnel-start").start();
        } else {
            Logger.log("sys", "服务已在运行，忽略重复启动请求");
        }
        return START_STICKY;
    }

    /** intent 为空（服务被系统重启）时回落到本地保存的配置 */
    private Config loadConfig(Intent i) {
        Config c = new Config();
        SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        c.localPort = sp.getInt("localPort", 8888);
        c.targetHost = sp.getString("targetHost", "127.0.0.1");
        c.targetPort = sp.getInt("targetPort", 8080);
        c.protocol = sp.getString("protocol", "http2");
        c.edgeIpVersion = sp.getInt("edgeIpVersion", 0);
        c.keepAwake = sp.getBoolean("keepAwake", true);
        c.token = sp.getString("token", "");
        c.unlimitedAttack = sp.getBoolean("unlimitedAttack", false);
        c.headerOnly = sp.getBoolean("headerOnly", false);
        c.token = sp.getString("token", "");

        if (i != null && i.hasExtra("localPort")) {
            c.localPort = i.getIntExtra("localPort", c.localPort);
            c.targetHost = i.getStringExtra("targetHost");
            c.targetPort = i.getIntExtra("targetPort", c.targetPort);
            c.protocol = i.getStringExtra("protocol");
            c.edgeIpVersion = i.getIntExtra("edgeIpVersion", 0);
            c.keepAwake = i.getBooleanExtra("keepAwake", true);
            String tk = i.getStringExtra("token");
            if (tk != null) {
                c.token = tk;
            }
        }
        if (c.targetHost == null || c.targetHost.trim().length() == 0) {
            c.targetHost = "127.0.0.1";
        }
        if (c.protocol == null || c.protocol.trim().length() == 0) {
            c.protocol = "http2";
        }
        return c;
    }

    /** 令牌指纹：只用于本地比对，不落盘、不打印，无法逆推原文 */
    public static String tokenSig(String t) {
        if (t == null) {
            return "";
        }
        t = t.trim();
        if (t.length() == 0) {
            return "";
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(t.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Throwable e) {
            return "len" + t.length();
        }
    }

    private void startTunnel(Config c) {
        try {
            headerOnlyActive = c.headerOnly;
            unlimitedAttackActive = c.unlimitedAttack;
            Logger.log("sys", "===== 启动隧道 =====");
            Logger.log("sys", "参数: 本地端口=" + c.localPort + "  目标=" + c.targetDesc()
                    + "  协议=" + c.protocol + "  edge-ip=" + (c.edgeIpVersion == 0 ? "auto" : c.edgeIpVersion)
                    + "  无限攻击=" + (c.unlimitedAttack ? "开" : "关")
                    + "  鉴权=" + (c.headerOnly ? "仅Header（URL令牌禁用）" : "URL+Header"));

            if (!isSupportedAbi()) {
                fail("当前设备不是 arm64 架构，内置的 cloudflared 无法运行（ABI: " + abiList() + "）");
                return;
            }

            if (isLoopback(c.targetHost) && c.targetPort == c.localPort) {
                fail("本地端口和目标端口相同会造成转发死循环，请把本地端口改成别的（如 8888）");
                return;
            }

            String tkRaw = c.token == null ? "" : c.token.trim();
            tokenActive = tkRaw.length() > 0;
            tokenLen = tkRaw.length();
            activeTokenSig = tokenSig(tkRaw);
            if (tkRaw.length() == 0) {
                Logger.log("sys", "访问令牌: 未启用 —— 任何拿到地址的人都可以访问");
            } else {
                String mask = tkRaw.length() > 6
                        ? tkRaw.substring(0, 4) + "****" + tkRaw.substring(tkRaw.length() - 2) : "****";
                Logger.log("sys", "访问令牌: 已启用  长度=" + tkRaw.length() + "  令牌=" + mask
                        + "  连续 5 次错误将锁定 60 秒"
                        + (c.unlimitedAttack ? "（无限攻击模式下不锁定）" : ""));
            }

            acquireWakeLock(c.keepAwake);

            binFile = ensureBinary();
            if (binFile == null) {
                fail("cloudflared 二进制释放失败");
                return;
            }

            caBundle = ensureCaBundle();

            prepareDns();

            try {
                forwarder.start(c.localPort, c.targetHost, c.targetPort, c.token, c.unlimitedAttack,
                        c.headerOnly,
                        new PortForwarder.AccessListener() {
                            public void onAccess(String method, String path, boolean allowed, String clientIp) {
                                accessCount++;
                                lastAccessAt = System.currentTimeMillis();
                                lastAccessAllowed = allowed;
                                lastAccessIp = clientIp == null ? "--" : clientIp;
                                String pp = path == null ? "-" : path;
                                if (pp.length() > 48) {
                                    pp = pp.substring(0, 48) + "...";
                                }
                                lastAccessDesc = method + " " + pp;
                            }
                        });
            } catch (Throwable e) {
                fail("本地端口 " + c.localPort + " 监听失败（可能被占用）: " + e);
                return;
            }

            launchCf(c);
            watchFirstUrl();
        } catch (Throwable e) {
            fail("启动异常: " + e);
        }
    }

    private static boolean isSupportedAbi() {
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            if (abis == null) {
                return false;
            }
            for (int i = 0; i < abis.length; i++) {
                if ("arm64-v8a".equals(abis[i])) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String abiList() {
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            if (abis == null || abis.length == 0) {
                return "unknown";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < abis.length; i++) {
                if (i > 0) {
                    sb.append('/');
                }
                sb.append(abis[i]);
            }
            return sb.toString();
        } catch (Throwable e) {
            return "unknown";
        }
    }

    private static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase();
        return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("::1") || h.equals("0.0.0.0");
    }

    /** 60 秒内没拿到地址就提醒一次，方便排查 */
    private void watchFirstUrl() {
        final int myGen = generation;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(60000);
                } catch (InterruptedException ignored) {
                }
                if (myGen == generation && publicUrl == null && wantRun) {
                    Logger.log("sys", "60 秒仍未取得隧道地址，请检查日志中的 cloudflared 报错"
                            + "（常见：DNS 不可用 / 网络不通 / 需要切换协议）");
                }
            }
        }, "url-watch");
        t.setDaemon(true);
        t.start();
    }

    private void launchCf(final Config c) {
        if (!wantRun) {
            return;
        }
        final int myGen = ++generation;
        state = STATE_STARTING;
        try {
            CfProcess p = new CfProcess(getFilesDir(), binFile);
            cf = p;
            p.start(c.localUrl(), c.protocol, c.edgeIpVersion, caBundle, new CfProcess.Callback() {
                public void onUrl(String url) {
                    if (myGen != generation) {
                        return;
                    }
                    publicUrl = url;
                    state = STATE_RUNNING;
                    restartCount = 0;
                    updateNotification("已连接", url);
                    Logger.log("sys", "远程地址就绪，可直接复制下发");
                }

                public void onExit(int code) {
                    if (myGen != generation) {
                        return;
                    }
                    if (!wantRun) {
                        Logger.log("sys", "cloudflared 已停止");
                        state = STATE_IDLE;
                        return;
                    }
                    restartCount++;
                    if (restartCount > MAX_RESTART) {
                        fail("cloudflared 连续异常退出 " + MAX_RESTART + " 次，已放弃重连");
                        return;
                    }
                    int delay = Math.min(30, 2 * restartCount);
                    Logger.log("sys", "cloudflared 异常退出(code=" + code + ")，" + delay
                            + " 秒后第 " + restartCount + " 次重连");
                    state = STATE_STARTING;
                    updateNotification("重连中", "第 " + restartCount + " 次");
                    new Thread(new Runnable() {
                        public void run() {
                            try {
                                Thread.sleep(delay * 1000L);
                            } catch (InterruptedException ignored) {
                            }
                            if (wantRun && myGen == generation) {
                                launchCf(c);
                            }
                        }
                    }, "cf-restart").start();
                }
            });
            updateNotification("正在连接 Cloudflare", c.targetDesc());
            Logger.log("sys", "cloudflared 已拉起，等待分配 trycloudflare 地址 ...");
        } catch (Throwable e) {
            fail("cloudflared 启动失败: " + e);
        }
    }

    /**
     * Android 没有 /etc/resolv.conf，Go 的 resolver 会 fallback 到 127.0.0.1:53，
     * 而普通应用无权限绑定 53 端口。所以内嵌的 cloudflared 已打过补丁：
     * 其 DNS 配置路径改为相对路径 resolv.conf.data，读取进程工作目录下的文件。
     * 这里负责把系统 DNS + 公共 DNS 写进该文件。
     */
    private void prepareDns() {
        writeResolvData();
        tryStartDnsBridge();
    }

    private void writeResolvData() {
        List<InetAddress> dns = systemDnsServers();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = 0; i < dns.size(); i++) {
            InetAddress a = dns.get(i);
            if (a == null || a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress()) {
                continue;
            }
            sb.append("nameserver ").append(a.getHostAddress()).append('\n');
            count++;
        }
        // 公共 DNS 兜底，保证一定能解析 argotunnel.com
        sb.append("nameserver 223.5.5.5\n");
        sb.append("nameserver 1.1.1.1\n");

        File f = new File(getFilesDir(), "resolv.conf.data");
        try {
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
            fos.close();
            Logger.log("dns", "DNS 配置已写入 " + f.getName() + "（系统 " + count + " 个 + 公共 2 个）");
        } catch (Throwable e) {
            Logger.log("dns", "写入 DNS 配置失败: " + e);
        }
    }

    /** 兜底：若设备允许绑定 53 端口，再起一个中继，双保险 */
    private void tryStartDnsBridge() {
        List<InetAddress> servers = systemDnsServers();
        boolean ok = dnsBridge.start(53, servers);
        if (ok) {
            Logger.log("dns", "额外启动了 53 端口 DNS 中继（兜底）");
        } else {
            Logger.log("dns", "53 端口中继不可用（Android 常规限制，不影响使用，已由 resolv.conf.data 接管）");
        }
    }

    private List<InetAddress> systemDnsServers() {
        List<InetAddress> list = new ArrayList<InetAddress>();
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return list;
            }
            if (Build.VERSION.SDK_INT >= 23) {
                Network n = cm.getActiveNetwork();
                if (n != null) {
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (lp != null) {
                        List<InetAddress> dns = lp.getDnsServers();
                        if (dns != null) {
                            list.addAll(dns);
                        }
                    }
                }
            } else {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni == null || !ni.isConnected()) {
                    Logger.log("dns", "当前无可用网络连接");
                } else {
                    Logger.log("dns", "Android 5.x 无法读取系统 DNS，使用公共 DNS 兜底");
                }
            }
        } catch (Throwable e) {
            Logger.log("dns", "读取系统 DNS 失败: " + e);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(list.get(i).getHostAddress());
        }
        Logger.log("dns", "系统 DNS: " + (list.isEmpty() ? "(无，将用公共 DNS)" : sb.toString()));
        return list;
    }

    /**
     * Android 的系统根证书在 /system/etc/security/cacerts，而 Go 的默认路径里没有它，
     * 所以这里把系统证书合并成一个 PEM，供 cloudflared 通过 SSL_CERT_FILE 使用；
     * 系统目录读不到时回落到 APK 内置证书包。
     */
    private File ensureCaBundle() {
        File out = new File(getFilesDir(), "ca-bundle.pem");
        try {
            if (out.isFile() && out.length() > 4096
                    && (System.currentTimeMillis() - out.lastModified()) < 24L * 3600 * 1000) {
                Logger.log("sys", "根证书包复用: " + out.length() + " 字节");
                return out;
            }

            StringBuilder sb = new StringBuilder(512 * 1024);
            File dir = new File("/system/etc/security/cacerts");
            File[] files = dir.listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    try {
                        String pem = readAll(files[i]);
                        if (pem != null && pem.contains("BEGIN CERTIFICATE")) {
                            sb.append(pem);
                            if (!pem.endsWith("\n")) {
                                sb.append('\n');
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            Logger.log("sys", "从系统目录收集到 " + sb.length() + " 字节证书");

            if (sb.length() < 1024) {
                Logger.log("sys", "系统证书不可读，改用内置证书包");
                FileOutputStream fos = new FileOutputStream(out);
                InputStream is = getAssets().open("cacerts.pem");
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
                fos.flush();
                fos.close();
                is.close();
            } else {
                FileOutputStream fos = new FileOutputStream(out);
                fos.write(sb.toString().getBytes("UTF-8"));
                fos.flush();
                fos.close();
            }
            out.setReadable(true, true);
            Logger.log("sys", "根证书包就绪: " + out.length() + " 字节");
            return out;
        } catch (Throwable e) {
            Logger.log("sys", "准备根证书包失败: " + e + "（将依赖系统默认路径）");
            return null;
        }
    }

    private String readAll(File f) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16 * 1024];
            int n;
            while ((n = fis.read(b)) > 0) {
                bo.write(b, 0, n);
            }
            fis.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable e) {
            return null;
        }
    }

    private File ensureBinary() {
        File bin = new File(getFilesDir(), "cloudflared");
        File marker = new File(getFilesDir(), "cloudflared.size");
        try {
            // 快路径：标记文件与二进制大小一致就直接复用，避免每次启动都读一遍 36MB assets
            long marked = -1;
            if (marker.isFile()) {
                try {
                    marked = Long.parseLong(readSmall(marker).trim());
                } catch (Throwable ignored) {
                }
            }
            if (bin.exists() && marked > 0 && bin.length() == marked) {
                if (!bin.canExecute()) {
                    bin.setExecutable(true, false);
                }
                Logger.log("sys", "cloudflared 已就绪 (" + marked + " 字节，复用本地副本)");
                return bin;
            }

            long assetSize = assetLength("cloudflared");
            if (bin.exists() && bin.length() == assetSize) {
                if (!bin.canExecute()) {
                    bin.setExecutable(true, false);
                }
                try {
                    FileOutputStream mk = new FileOutputStream(marker);
                    mk.write(String.valueOf(assetSize).getBytes("UTF-8"));
                    mk.close();
                } catch (Throwable ignored) {
                }
                Logger.log("sys", "cloudflared 已就绪 (" + assetSize + " 字节)");
                return bin;
            }

            Logger.log("sys", "释放 cloudflared (" + assetSize + " 字节) 到私有目录，请稍候 ...");
            File tmp = new File(getFilesDir(), "cloudflared.tmp");
            if (tmp.exists()) {
                tmp.delete();
            }
            InputStream is = getAssets().open("cloudflared");
            FileOutputStream fos = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
                total += n;
            }
            fos.flush();
            fos.close();
            is.close();

            if (total != assetSize) {
                Logger.log("sys", "释放字节数不匹配: " + total + " != " + assetSize);
            }

            if (bin.exists() && !bin.delete()) {
                Logger.log("sys", "旧文件删除失败，继续尝试覆盖");
            }
            if (!tmp.renameTo(bin)) {
                Logger.log("sys", "rename 失败，回退为复制");
                java.io.FileInputStream fis = new java.io.FileInputStream(tmp);
                FileOutputStream fos2 = new FileOutputStream(bin);
                byte[] b2 = new byte[64 * 1024];
                int m;
                while ((m = fis.read(b2)) > 0) {
                    fos2.write(b2, 0, m);
                }
                fos2.flush();
                fos2.close();
                fis.close();
                tmp.delete();
            }
            bin.setReadable(true, true);
            bin.setExecutable(true, false);

            FileOutputStream mk = new FileOutputStream(marker);
            mk.write(String.valueOf(total).getBytes("UTF-8"));
            mk.close();

            Logger.log("sys", "释放完成 " + total + " 字节，可执行=" + bin.canExecute());
            return bin;
        } catch (Throwable e) {
            Logger.log("sys", "释放 cloudflared 失败: " + e);
            return null;
        }
    }

    private String readSmall(File f) throws Exception {
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        byte[] b = new byte[64];
        int n = fis.read(b);
        fis.close();
        if (n <= 0) {
            return "";
        }
        return new String(b, 0, n, "UTF-8");
    }

    private long assetLength(String name) throws Exception {
        InputStream is = getAssets().open(name);
        long len = 0;
        byte[] b = new byte[64 * 1024];
        int n;
        while ((n = is.read(b)) > 0) {
            len += n;
        }
        is.close();
        return len;
    }

    /** 启动失败：清理已占用的资源后退出，避免端口/进程泄漏 */
    private void fail(String msg) {
        Logger.log("sys", "启动失败: " + msg);
        wantRun = false;
        generation++;
        CfProcess p = cf;
        cf = null;
        if (p != null) {
            try {
                p.stop();
            } catch (Throwable ignored) {
            }
        }
        try {
            forwarder.stop();
        } catch (Throwable ignored) {
        }
        try {
            dnsBridge.stop();
        } catch (Throwable ignored) {
        }
        releaseWakeLock();
        publicUrl = null;
        state = STATE_IDLE;
        updateNotification("启动失败", msg);
        stopForeground(true);
        stopSelf();
    }

    /** 用户主动停止（幂等） */
    private void stopTunnel() {
        state = STATE_STOPPING;
        Logger.log("sys", "===== 停止隧道 =====");
        wantRun = false;
        generation++;

        CfProcess p = cf;
        cf = null;
        if (p != null) {
            try {
                p.stop();
            } catch (Throwable ignored) {
            }
        }
        try {
            forwarder.stop();
        } catch (Throwable ignored) {
        }
        try {
            dnsBridge.stop();
        } catch (Throwable ignored) {
        }
        releaseWakeLock();
        publicUrl = null;
        state = STATE_IDLE;
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        Logger.log("sys", "全部已停止");
    }

    private void acquireWakeLock(boolean enable) {
        if (!enable || wakeLock != null) {
            return;
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cftunnel:tunnel");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            Logger.log("sys", "已获取唤醒锁，防止息屏断连");
        } catch (Throwable e) {
            Logger.log("sys", "唤醒锁获取失败: " + e);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            try {
                wakeLock.release();
            } catch (Throwable ignored) {
            }
            wakeLock = null;
            Logger.log("sys", "唤醒锁已释放");
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "隧道服务",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Cloudflare 隧道运行状态");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("Cloudflare 隧道 · " + title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }

    private void updateNotification(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification(title, text));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        Logger.log("sys", "服务销毁");
        if (wantRun) {
            Logger.log("sys", "注意：服务被系统回收，若未自动恢复请回应用重新启动");
        }
        wantRun = false;
        CfProcess p = cf;
        cf = null;
        if (p != null) {
            try {
                p.stop();
            } catch (Throwable ignored) {
            }
        }
        try {
            forwarder.stop();
        } catch (Throwable ignored) {
        }
        try {
            dnsBridge.stop();
        } catch (Throwable ignored) {
        }
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

