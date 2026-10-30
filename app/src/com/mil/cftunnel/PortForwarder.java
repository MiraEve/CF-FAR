package com.mil.cftunnel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 本地 TCP 端口转发 + 访问令牌网关。
 *
 * <p>监听 127.0.0.1:localPort，转发到 targetHost:targetPort。启用令牌后先解析 HTTP
 * 请求头做鉴权，不通过直接 401，请求不会到达真正的服务。</p>
 *
 * <p>安全要点：</p>
 * <ul>
 *   <li>一次连接只处理一个请求，转发完请求体即 shutdownOutput，防止 keep-alive
 *       复用与 HTTP 流水线走私绕过鉴权；</li>
 *   <li>header 值与 query 参数均做精确比较，禁止子串/前缀匹配；</li>
 *   <li>连续鉴权失败触发锁定，可被"无限攻击测试"模式关闭。</li>
 * </ul>
 */
public final class PortForwarder {

    public interface AccessListener {
        void onAccess(String method, String path, boolean allowed, String clientIp);
    }

    private static final int MAX_HEAD = 32 * 1024;
    /** 最大并发连接数：防止慢速连接把线程和内存吃光（cachedThreadPool 本身无上限） */
    private static final int MAX_CONN = 120;
    /** 从建连到读完请求头的总时限，防 Slowloris 一点一点滴头无限占用 */
    private static final int HEAD_DEADLINE_MS = 5000;
    /** 防爆破：连续失败 5 次锁定 60 秒 */
    private static final int MAX_FAIL = 5;
    private static final long LOCK_MS = 60L * 1000L;

    private final java.util.concurrent.atomic.AtomicInteger failCount =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile long lockUntil = 0L;

    private ExecutorService pool = newPool();
    private final java.util.concurrent.atomic.AtomicInteger liveConns =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger droppedConns =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile ServerSocket server;
    private volatile boolean running;
    private volatile int listenPort;
    private volatile String targetHost;
    private volatile int targetPort;
    private volatile String token = "";
    /** 仅 Header 鉴权：true 时忽略 ?token=，只认 Authorization / X-Auth-Token */
    private volatile boolean headerOnly = false;
    private volatile boolean unlimitedAttack = false;
    private volatile AccessListener listener;

    private static ExecutorService newPool() {
        return Executors.newCachedThreadPool(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "fwd-worker");
                t.setDaemon(true);
                return t;
            }
        });
    }

    public int listenPort() {
        return listenPort;
    }

    public boolean isRunning() {
        return running;
    }

    public void start(int listenPort, String host, int port, String token, boolean unlimitedAttack,
                      boolean headerOnly, AccessListener listener) throws Exception {
        stop();
        if (pool == null || pool.isShutdown()) {
            pool = newPool();
        }
        this.listenPort = listenPort;
        this.targetHost = host;
        this.targetPort = port;
        this.token = token == null ? "" : token.trim();
        this.unlimitedAttack = unlimitedAttack;
        this.headerOnly = headerOnly;
        this.failCount.set(0);
        this.lockUntil = 0L;
        this.listener = listener;

        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort), 128);
        server = ss;
        running = true;

        Thread t = new Thread(new Runnable() {
            public void run() {
                Logger.log("fwd", "本地转发就绪  127.0.0.1:" + PortForwarder.this.listenPort
                        + "  ->  " + PortForwarder.this.targetHost + ":" + PortForwarder.this.targetPort);
                if (PortForwarder.this.token.length() > 0) {
                    if (PortForwarder.this.headerOnly) {
                        Logger.log("fwd", "访问令牌已启用（仅 Header 模式）：只放行 Authorization: Bearer / X-Auth-Token"
                                + "  —— ?token= 已禁用，防止令牌经 URL / 日志 / Referer 泄露");
                    } else {
                        Logger.log("fwd", "访问令牌已启用：放行 ?token= / Authorization: Bearer / X-Auth-Token");
                    }
                } else {
                    Logger.log("fwd", "访问令牌未启用：任何人拿到地址即可访问");
                }
                if (PortForwarder.this.unlimitedAttack) {
                    Logger.log("fwd", "⚠ 无限攻击测试模式：防爆破锁定已关闭，可无限次尝试");
                }
                final ServerSocket ss = server;
                while (running && ss != null) {
                    try {
                        final Socket in = ss.accept();
                        if (liveConns.incrementAndGet() > MAX_CONN) {
                            liveConns.decrementAndGet();
                            try {
                                in.close();
                            } catch (Throwable ignored) {
                            }
                            int d = droppedConns.incrementAndGet();
                            if (d == 1 || d % 50 == 0) {
                                Logger.log("fwd", "并发连接已达上限 " + MAX_CONN
                                        + "，已丢弃新连接（累计 " + d + " 次）");
                            }
                            continue;
                        }
                        pool.execute(new Runnable() {
                            public void run() {
                                try {
                                    handle(in);
                                } finally {
                                    liveConns.decrementAndGet();
                                }
                            }
                        });
                    } catch (Throwable e) {
                        if (running) {
                            Logger.log("fwd", "accept 异常: " + e);
                        }
                        break;
                    }
                }
            }
        }, "fwd-accept");
        t.setDaemon(true);
        t.start();
    }

    private void handle(Socket in) {
        Socket out = null;
        try {
            in.setTcpNoDelay(true);
            in.setKeepAlive(true);
            in.setSoTimeout(0);

            byte[] head = null;
            String method = "TCP";
            String path = "-";
            String clientIp = null;
            boolean selfTest = false;
            String tk = token;

            if (tk != null && tk.length() > 0) {
                in.setSoTimeout(HEAD_DEADLINE_MS);
                head = readHttpHead(in);
                in.setSoTimeout(0);

                if (head == null || head.length == 0) {
                    Logger.log("fwd", "读取请求头失败，已拒绝");
                    notifyAccess("?", "-", false, "--");
                    reject(in, 401, "{\"error\":\"unauthorized\"}");
                    return;
                }

                String txt = new String(head, "ISO-8859-1");
                clientIp = clientIpFrom(head);
                selfTest = txt.toLowerCase(Locale.US).indexOf("\r\nx-self-test: 1") >= 0;

                String[] lines = txt.split("\r\n");
                if (lines.length > 0) {
                    String[] parts = lines[0].split(" ");
                    if (parts.length >= 1) {
                        method = parts[0];
                    }
                    if (parts.length >= 2) {
                        path = parts[1];
                    }
                }

                long now = System.currentTimeMillis();
                if (!unlimitedAttack && now < lockUntil) {
                    long left = (lockUntil - now) / 1000 + 1;
                    Logger.log("fwd", "锁定中，拒绝请求（剩余 " + left + " 秒）  " + method + " " + path);
                    if (!selfTest) {
                        notifyAccess(method, path + "  锁定中", false,
                                clientIp == null ? "--" : clientIp);
                    }
                    reject(in, 429, "{\"error\":\"locked\"}");
                    return;
                }

                if (!authorized(head, path)) {
                    if (unlimitedAttack) {
                        Logger.log("fwd", "拦截(无限模式)  " + method + " " + path
                                + (clientIp != null ? "  来源 " + clientIp : ""));
                        if (!selfTest) {
                            notifyAccess(method, path + "  无限模式", false,
                                    clientIp == null ? "--" : clientIp);
                        }
                        reject(in, 401, "{\"error\":\"unauthorized\"}");
                        return;
                    }
                    int n = selfTest ? 1 : failCount.incrementAndGet();
                    if (n >= MAX_FAIL) {
                        lockUntil = System.currentTimeMillis() + LOCK_MS;
                        failCount.set(0);
                        Logger.log("fwd", "连续 " + MAX_FAIL + " 次鉴权失败，已锁定 " + (LOCK_MS / 1000) + " 秒");
                        if (!selfTest) {
                            notifyAccess(method, path + "  触发锁定", false,
                                    clientIp == null ? "--" : clientIp);
                        }
                        reject(in, 429, "{\"error\":\"locked\"}");
                        return;
                    }
                    Logger.log("fwd", "拦截未授权访问  " + method + " " + path
                            + (clientIp != null ? "  来源 " + clientIp : "") + "  (" + n + "/" + MAX_FAIL + ")");
                    if (!selfTest) {
                        notifyAccess(method, path + "  " + n + "/" + MAX_FAIL, false,
                                clientIp == null ? "--" : clientIp);
                    }
                    reject(in, 401, "{\"error\":\"unauthorized\"}");
                    return;
                }
                failCount.set(0);
                Logger.log("fwd", "放行访问  " + method + " " + path
                        + (clientIp != null ? "  来源 " + clientIp : ""));
            } else {
                Logger.log("fwd", "外部连接进入 " + in.getRemoteSocketAddress());
            }

            if (!selfTest) {
                notifyAccess(method, path, true, clientIp == null ? "--" : clientIp);
            }

            out = new Socket();
            out.setTcpNoDelay(true);
            out.setKeepAlive(true);
            out.setSoTimeout(0);
            out.connect(new InetSocketAddress(InetAddress.getByName(targetHost), targetPort), 8000);

            if (head != null && head.length > 0) {
                OutputStream os = out.getOutputStream();
                String hs = new String(head, "ISO-8859-1");
                os.write(forceClose(head));

                if (!isChunked(hs)) {
                    long cl = contentLength(hs);
                    // readHttpHead 可能已把 body 的一部分读进 head，forceClose 已原样带过去，
                    // 这里只补读剩下的，避免重复读取导致后端收到的长度对不上。
                    int hend = indexOfHeadEnd(head);
                    long already = hend >= 0 ? head.length - (hend + 4) : 0L;
                    long left = cl - already;
                    if (left > 0) {
                        byte[] buf = new byte[8192];
                        while (left > 0) {
                            int n = in.getInputStream().read(buf, 0,
                                    (int) Math.min(buf.length, left));
                            if (n < 0) {
                                break;
                            }
                            os.write(buf, 0, n);
                            left -= n;
                        }
                    }
                    os.flush();
                    // 一个请求转发完就关掉上行方向，客户端再塞的第二个请求（流水线/走私）无处可去
                    try {
                        out.shutdownOutput();
                    } catch (Throwable ignored) {
                    }
                    downOnly(out, in);
                    return;
                }
                os.flush();
            }

            final Socket a = in;
            final Socket b = out;
            Thread up = pump(a, b);
            Thread down = pump(b, a);
            up.start();
            down.start();
        } catch (Throwable e) {
            Logger.log("fwd", "转发失败 -> " + targetHost + ":" + targetPort + " : " + e);
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /** 读取 HTTP 请求头（直到 \r\n\r\n），失败返回 null */
    private byte[] readHttpHead(Socket s) {
        try {
            long start = System.currentTimeMillis();
            InputStream is = s.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(2048);
            byte[] buf = new byte[2048];
            while (bo.size() < MAX_HEAD) {
                if (System.currentTimeMillis() - start > HEAD_DEADLINE_MS) {
                    return null;    // 滴得太慢，放弃，别占着线程
                }
                int n = is.read(buf);
                if (n < 0) {
                    break;
                }
                bo.write(buf, 0, n);
                byte[] data = bo.toByteArray();
                if (indexOfHeadEnd(data) >= 0) {
                    return data;
                }
            }
            if (bo.size() > 0) {
                return bo.toByteArray();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int indexOfHeadEnd(byte[] d) {
        for (int i = 0; i + 3 < d.length; i++) {
            if (d[i] == '\r' && d[i + 1] == '\n' && d[i + 2] == '\r' && d[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static long contentLength(String head) {
        try {
            String[] lines = head.split("\r\n");
            for (int i = 1; i < lines.length; i++) {
                String ln = lines[i];
                int c = ln.indexOf(':');
                if (c <= 0) {
                    continue;
                }
                if (ln.substring(0, c).trim().equalsIgnoreCase("content-length")) {
                    return Long.parseLong(ln.substring(c + 1).trim());
                }
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private static boolean isChunked(String head) {
        try {
            String[] lines = head.split("\r\n");
            for (int i = 1; i < lines.length; i++) {
                String ln = lines[i];
                int c = ln.indexOf(':');
                if (c <= 0) {
                    continue;
                }
                if (ln.substring(0, c).trim().equalsIgnoreCase("transfer-encoding")
                        && ln.substring(c + 1).toLowerCase(Locale.US).contains("chunked")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 经 Cloudflare 后本地看到的都是 127.0.0.1，真实来源在 CF-Connecting-IP / X-Forwarded-For */
    private static String clientIpFrom(byte[] head) {
        try {
            String h = new String(head, "ISO-8859-1");
            String[] lines = h.split("\r\n");
            String xff = null;
            for (int i = 1; i < lines.length; i++) {
                String ln = lines[i];
                int c = ln.indexOf(':');
                if (c <= 0) {
                    continue;
                }
                String k = ln.substring(0, c).trim().toLowerCase(Locale.US);
                String v = ln.substring(c + 1).trim();
                if (k.equals("cf-connecting-ip")) {
                    return v;
                }
                if (k.equals("x-forwarded-for") && xff == null) {
                    int comma = v.indexOf(',');
                    xff = comma > 0 ? v.substring(0, comma).trim() : v;
                }
                if (k.equals("x-real-ip") && xff == null) {
                    xff = v;
                }
            }
            return xff;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 严格鉴权：header 精确取值比较 + query 参数名精确匹配，不做任何子串匹配。
     */
    private boolean authorized(byte[] head, String path) {
        String tk = token;
        if (tk == null || tk.length() == 0) {
            return true;
        }
        try {
            String h = new String(head, "ISO-8859-1");
            String[] lines = h.split("\r\n");
            for (int i = 1; i < lines.length; i++) {
                String ln = lines[i];
                int c = ln.indexOf(':');
                if (c <= 0) {
                    continue;
                }
                String k = ln.substring(0, c).trim().toLowerCase(Locale.US);
                String v = ln.substring(c + 1).trim();
                if (k.equals("x-auth-token")) {
                    if (v.equals(tk)) {
                        return true;
                    }
                } else if (k.equals("authorization")) {
                    int sp = v.indexOf(' ');
                    if (sp > 0) {
                        String scheme = v.substring(0, sp);
                        String val = v.substring(sp + 1).trim();
                        if (scheme.equalsIgnoreCase("bearer") && val.equals(tk)) {
                            return true;
                        }
                    }
                }
            }
            if (headerOnly) {
                return false;   // 仅 Header 模式：URL 里的令牌一律不认
            }
            String qv = queryParam(path, "token");
            if (qv != null) {
                if (qv.equals(tk)) {
                    return true;
                }
                String dv = urlDecode(qv);
                if (!dv.equals(qv) && dv.equals(tk)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 从路径里取指定 query 参数（参数名必须完全相等，避免 xtoken= 之类误匹配） */
    private static String queryParam(String path, String key) {
        if (path == null) {
            return null;
        }
        int qi = path.indexOf('?');
        if (qi < 0) {
            return null;
        }
        String qs = path.substring(qi + 1);
        int hash = qs.indexOf('#');
        if (hash >= 0) {
            qs = qs.substring(0, hash);
        }
        String[] parts = qs.split("&");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.length() == 0) {
                continue;
            }
            int eq = p.indexOf('=');
            String k = eq >= 0 ? p.substring(0, eq) : p;
            String v = eq >= 0 ? p.substring(eq + 1) : "";
            if (k.equals(key)) {
                return v;
            }
        }
        return null;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    /**
     * 把请求头强制改成 Connection: close。
     * 否则客户端会在同一 keep-alive 连接上继续发请求，而连接级鉴权只在建连时做一次，
     * 后续请求会绕过令牌校验。
     */
    private static byte[] forceClose(byte[] head) {
        try {
            String h = new String(head, "ISO-8859-1");
            int end = h.indexOf("\r\n\r\n");
            if (end < 0) {
                return head;
            }
            String headPart = h.substring(0, end);
            String tail = h.substring(end + 4);
            StringBuilder sb = new StringBuilder(headPart.length() + 32);
            String[] lines = headPart.split("\r\n");
            for (int i = 0; i < lines.length; i++) {
                String ln = lines[i];
                if (ln.toLowerCase(Locale.US).startsWith("connection:")) {
                    continue;
                }
                sb.append(ln).append("\r\n");
            }
            sb.append("Connection: close\r\n\r\n");
            byte[] nb = sb.toString().getBytes("ISO-8859-1");
            byte[] tb = tail.getBytes("ISO-8859-1");
            byte[] out = new byte[nb.length + tb.length];
            System.arraycopy(nb, 0, out, 0, nb.length);
            System.arraycopy(tb, 0, out, nb.length, tb.length);
            return out;
        } catch (Throwable t) {
            return head;
        }
    }

    private void reject(Socket in, int code, String body) {
        try {
            String status = code == 429 ? "429 Too Many Requests" : "401 Unauthorized";
            String resp = "HTTP/1.1 " + status + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + (code == 429 ? "Retry-After: 60\r\n" : "")
                    + "Connection: close\r\n"
                    + "\r\n"
                    + body;
            OutputStream os = in.getOutputStream();
            os.write(resp.getBytes("UTF-8"));
            os.flush();
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(in);
        }
    }

    private void notifyAccess(String method, String path, boolean allowed, String ip) {
        AccessListener l = listener;
        if (l != null) {
            try {
                l.onAccess(method, path, allowed, ip);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 只做下行（后端 -> 客户端），上行已被 shutdown */
    private void downOnly(final Socket from, final Socket to) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                byte[] buf = new byte[16 * 1024];
                try {
                    InputStream is = from.getInputStream();
                    OutputStream os = to.getOutputStream();
                    while (true) {
                        int n = is.read(buf);
                        if (n < 0) {
                            break;
                        }
                        os.write(buf, 0, n);
                        os.flush();
                    }
                } catch (Throwable ignored) {
                } finally {
                    closeQuietly(from);
                    closeQuietly(to);
                }
            }
        }, "fwd-down");
        t.setDaemon(true);
        t.start();
    }

    private Thread pump(final Socket from, final Socket to) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                byte[] buf = new byte[16 * 1024];
                try {
                    InputStream is = from.getInputStream();
                    OutputStream os = to.getOutputStream();
                    while (true) {
                        int n = is.read(buf);
                        if (n < 0) {
                            break;
                        }
                        os.write(buf, 0, n);
                        os.flush();
                    }
                } catch (Throwable ignored) {
                } finally {
                    closeQuietly(from);
                    closeQuietly(to);
                }
            }
        });
        t.setDaemon(true);
        return t;
    }

    public void stop() {
        running = false;
        ServerSocket s = server;
        server = null;
        if (s != null) {
            closeQuietly(s);
            Logger.log("fwd", "本地转发已停止");
        }
        try {
            if (pool != null && !pool.isShutdown()) {
                pool.shutdownNow();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void closeQuietly(ServerSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }
}

