package com.mil.cftunnel;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * cloudflared 子进程管理：启动、逐行读取输出、提取 trycloudflare 地址、停止。
 * 注意：所有对进程的引用都在启动时捕获为局部变量，避免 stop() 置空后读线程 NPE。
 */
public final class CfProcess {

    public static final Pattern URL_PATTERN =
            Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");

    public interface Callback {
        void onUrl(String url);

        void onExit(int code);
    }

    private final File workDir;
    private final File binary;
    private volatile Process process;
    private volatile boolean stopping;
    private volatile String lastUrl;

    public CfProcess(File workDir, File binary) {
        this.workDir = workDir;
        this.binary = binary;
    }

    public boolean isAlive() {
        Process p = process;
        if (p == null) {
            return false;
        }
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    public String lastUrl() {
        return lastUrl;
    }

    public void start(final String localUrl, final String protocol, final int edgeIpVersion,
                      final File caBundle, final Callback cb) throws Exception {
        List<String> cmd = new ArrayList<String>();
        cmd.add(binary.getAbsolutePath());
        cmd.add("tunnel");
        cmd.add("--url");
        cmd.add(localUrl);
        cmd.add("--no-autoupdate");
        cmd.add("--protocol");
        cmd.add(protocol);
        cmd.add("--loglevel");
        cmd.add("info");
        // 禁用到源站的 keep-alive 连接池，保证每个请求都重新走一遍令牌校验
        cmd.add("--proxy-keepalive-connections");
        cmd.add("0");
        if (edgeIpVersion == 4 || edgeIpVersion == 6) {
            cmd.add("--edge-ip-version");
            cmd.add(String.valueOf(edgeIpVersion));
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        pb.environment().put("HOME", workDir.getAbsolutePath());
        pb.environment().put("TMPDIR", workDir.getAbsolutePath());
        if (caBundle != null && caBundle.isFile()) {
            // Android 上没有 /etc/ssl/certs，Go 会找不到根证书，必须显式指定
            pb.environment().put("SSL_CERT_FILE", caBundle.getAbsolutePath());
            Logger.log("cf", "根证书: " + caBundle.getAbsolutePath() + " (" + caBundle.length() + " 字节)");
        }

        Logger.log("cf", "启动: " + join(cmd));
        final Process proc = pb.start();
        process = proc;
        stopping = false;

        Thread reader = new Thread(new Runnable() {
            public void run() {
                BufferedReader br = null;
                try {
                    br = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) {
                        Logger.log("cf", line);
                        if (lastUrl == null) {
                            Matcher m = URL_PATTERN.matcher(line);
                            if (m.find()) {
                                lastUrl = m.group();
                                Logger.log("sys", "隧道地址: " + lastUrl);
                                try {
                                    cb.onUrl(lastUrl);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                } catch (Throwable e) {
                    if (!stopping) {
                        Logger.log("cf", "读取输出异常: " + e);
                    }
                } finally {
                    if (br != null) {
                        try {
                            br.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }

                int code = -1;
                try {
                    code = proc.waitFor();
                } catch (Throwable ignored) {
                }
                Logger.log("cf", "cloudflared 进程退出, code=" + code);
                try {
                    cb.onExit(code);
                } catch (Throwable ignored) {
                }
            }
        }, "cf-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public void stop() {
        stopping = true;
        final Process p = process;
        process = null;
        if (p == null) {
            return;
        }
        Logger.log("cf", "正在停止 cloudflared ...");
        try {
            p.destroy();
        } catch (Throwable ignored) {
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(2000);
                    if (p.isAlive()) {
                        Logger.log("cf", "2 秒内未退出，强制结束");
                        p.destroy();
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "cf-killer");
        t.setDaemon(true);
        t.start();
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
