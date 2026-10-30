package com.mil.cftunnel;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Android 没有 /etc/resolv.conf，Go 写的 cloudflared 会 fallback 到 127.0.0.1:53。
 * 这里在回环 53 端口起一个极简 DNS 中继，把查询转发给系统 DNS / 公共 DNS，
 * 从而让 cloudflared 能正常解析 argotunnel.com。
 */
public final class DnsBridge {

    private volatile DatagramSocket socket;
    private volatile boolean running;
    private final List<InetAddress> upstreams = new ArrayList<InetAddress>();
    private ExecutorService pool = newPool();

    private static ExecutorService newPool() {
        return Executors.newFixedThreadPool(8, new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "dns-worker");
                t.setDaemon(true);
                return t;
            }
        });
    }

    public boolean isRunning() {
        return running;
    }

    /** 绑定 127.0.0.1:port，失败返回 false（例如低端口无权限或端口被占） */
    public boolean start(int port, List<InetAddress> servers) {
        stop();
        if (pool == null || pool.isShutdown()) {
            pool = newPool();
        }
        upstreams.clear();
        if (servers != null) {
            for (int i = 0; i < servers.size(); i++) {
                InetAddress a = servers.get(i);
                if (a != null && !a.isAnyLocalAddress()) {
                    upstreams.add(a);
                }
            }
        }
        if (upstreams.isEmpty()) {
            try {
                upstreams.add(InetAddress.getByName("223.5.5.5"));
                upstreams.add(InetAddress.getByName("1.1.1.1"));
            } catch (Throwable ignored) {
            }
        }

        try {
            DatagramSocket s = new DatagramSocket((SocketAddress) null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            socket = s;
            running = true;

            Thread t = new Thread(new Runnable() {
                public void run() {
                    loop();
                }
            }, "dns-bridge");
            t.setDaemon(true);
            t.start();

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < upstreams.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(upstreams.get(i).getHostAddress());
            }
            Logger.log("dns", "DNS 中继已启动 127.0.0.1:" + port + "  上游: " + sb);
            return true;
        } catch (Throwable e) {
            Logger.log("dns", "DNS 中继启动失败(127.0.0.1:" + port + "): " + e
                    + "  —— 若设备不支持非特权绑定 53 端口，需 root 或改用系统 resolv.conf");
            return false;
        }
    }

    private void loop() {
        byte[] buf = new byte[4096];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                final byte[] query = Arrays.copyOf(p.getData(), p.getLength());
                final SocketAddress client = p.getSocketAddress();
                pool.execute(new Runnable() {
                    public void run() {
                        forward(query, client);
                    }
                });
            } catch (Throwable e) {
                if (running) {
                    Logger.log("dns", "receive 异常: " + e);
                }
                break;
            }
        }
    }

    /** 逐个上游尝试，2 秒超时，成功即回写客户端 */
    private void forward(byte[] query, SocketAddress client) {
        for (int i = 0; i < upstreams.size(); i++) {
            DatagramSocket s = null;
            try {
                s = new DatagramSocket();
                s.setSoTimeout(2000);
                s.send(new DatagramPacket(query, query.length, upstreams.get(i), 53));

                byte[] rbuf = new byte[4096];
                DatagramPacket rp = new DatagramPacket(rbuf, rbuf.length);
                s.receive(rp);

                byte[] resp = Arrays.copyOf(rp.getData(), rp.getLength());
                DatagramSocket out = socket;
                if (out != null && !out.isClosed()) {
                    out.send(new DatagramPacket(resp, resp.length, client));
                }
                return;
            } catch (Throwable ignored) {
                // 换下一个上游
            } finally {
                if (s != null) {
                    try {
                        s.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        Logger.log("dns", "上游 DNS 全部无响应，查询被丢弃");
    }

    public void stop() {
        running = false;
        DatagramSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
            Logger.log("dns", "DNS 中继已停止");
        }
        try {
            if (pool != null && !pool.isShutdown()) {
                pool.shutdownNow();
            }
        } catch (Throwable ignored) {
        }
    }
}
