package com.mil.cftunnel;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 全局日志：内存环形缓冲 + 追加写文件 + 监听器回调。
 * 线程安全；单行写入，方便排查与复制。
 */
public final class Logger {

    public interface Listener {
        void onLog(String line);
    }

    private static final int MAX_LINES = 4000;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> BUF = new ArrayDeque<String>();
    private static final List<Listener> LISTENERS = new ArrayList<Listener>();

    private static BufferedWriter fileWriter;
    private static File logFile;

    private Logger() {
    }

    public static void init(File f) {
        synchronized (LOCK) {
            if (logFile != null && logFile.equals(f) && fileWriter != null) {
                return;
            }
            closeQuietly();
            try {
                // 超过 5MB 自动轮转，避免长期运行把存储写满
                if (f.exists() && f.length() > 5L * 1024 * 1024) {
                    File old = new File(f.getAbsolutePath() + ".1");
                    if (old.exists()) {
                        old.delete();
                    }
                    f.renameTo(old);
                }
                logFile = f;
                fileWriter = new BufferedWriter(new FileWriter(f, true), 16 * 1024);
            } catch (Throwable t) {
                fileWriter = null;
            }
        }
    }

    public static void log(String tag, String msg) {
        String line = stamp() + " [" + tag + "] " + (msg == null ? "null" : msg);
        List<Listener> snapshot;
        synchronized (LOCK) {
            BUF.addLast(line);
            while (BUF.size() > MAX_LINES) {
                BUF.removeFirst();
            }
            if (fileWriter != null) {
                try {
                    fileWriter.write(line);
                    fileWriter.write('\n');
                    fileWriter.flush();
                } catch (Throwable ignored) {
                    // 磁盘满等情况不影响主流程
                }
            }
            snapshot = new ArrayList<Listener>(LISTENERS);
        }
        for (int i = 0; i < snapshot.size(); i++) {
            try {
                snapshot.get(i).onLog(line);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void addListener(Listener l) {
        synchronized (LOCK) {
            if (!LISTENERS.contains(l)) {
                LISTENERS.add(l);
            }
        }
    }

    public static void removeListener(Listener l) {
        synchronized (LOCK) {
            LISTENERS.remove(l);
        }
    }

    /** 全部日志（复制/导出用） */
    public static String dump() {
        synchronized (LOCK) {
            StringBuilder sb = new StringBuilder(BUF.size() * 80);
            for (String s : BUF) {
                sb.append(s).append('\n');
            }
            return sb.toString();
        }
    }

    /** 最近 n 行（界面渲染用，避免一次渲染上万行卡顿） */
    public static String tail(int n) {
        synchronized (LOCK) {
            int size = BUF.size();
            int skip = size > n ? size - n : 0;
            StringBuilder sb = new StringBuilder(n * 80);
            int i = 0;
            for (String s : BUF) {
                if (i++ < skip) {
                    continue;
                }
                sb.append(s).append('\n');
            }
            return sb.toString();
        }
    }

    public static int lineCount() {
        synchronized (LOCK) {
            return BUF.size();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            BUF.clear();
            closeQuietly();
            if (logFile != null) {
                try {
                    logFile.delete();
                } catch (Throwable ignored) {
                }
                try {
                    fileWriter = new BufferedWriter(new FileWriter(logFile, true), 16 * 1024);
                } catch (Throwable ignored) {
                    fileWriter = null;
                }
            }
        }
    }

    public static File file() {
        synchronized (LOCK) {
            return logFile;
        }
    }

    private static void closeQuietly() {
        if (fileWriter != null) {
            try {
                fileWriter.flush();
                fileWriter.close();
            } catch (Throwable ignored) {
            }
            fileWriter = null;
        }
    }

    /** 自己格式化时间，避免 SimpleDateFormat 的线程安全问题 */
    private static String stamp() {
        long t = System.currentTimeMillis();
        long ms = t % 1000;
        long sec = t / 1000;
        long s = sec % 60;
        long m = (sec / 60) % 60;
        long h = (sec / 3600) % 24;
        return two(h) + ":" + two(m) + ":" + two(s) + "." + three(ms);
    }

    private static String two(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private static String three(long v) {
        if (v < 10) {
            return "00" + v;
        }
        if (v < 100) {
            return "0" + v;
        }
        return String.valueOf(v);
    }
}
