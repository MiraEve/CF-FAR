package com.mil.cftunnel;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private EditText etLocalPort;
    private EditText etToken;
    private TextView tvAlert;
    private TextView tvTokenState;
    private TextView tvVersion;
    private android.widget.CheckBox cbUnlimited;
    private int lastSeenAccess = 0;
    private Intent pendingStart;
    private EditText etHost;
    private EditText etTargetPort;
    private RadioGroup rgProtocol;
    private TextView tvState;
    private TextView tvUrl;
    private TextView tvLog;
    private ScrollView svLog;
    private Button btnStart;
    private Button btnStop;

    private boolean autoScroll = true;
    private volatile boolean dirty;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Logger.Listener logListener = new Logger.Listener() {
        public void onLog(String line) {
            dirty = true;
        }
    };

    private final Runnable refresher = new Runnable() {
        public void run() {
            if (dirty) {
                dirty = false;
                tvLog.setText(Logger.tail(600));
                if (autoScroll) {
                    svLog.post(new Runnable() {
                        public void run() {
                            svLog.fullScroll(View.FOCUS_DOWN);
                        }
                    });
                }
            }
            refreshStatus();
            ui.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        Logger.init(new java.io.File(getFilesDir(), "tunnel.log"));

        etLocalPort = (EditText) findViewById(R.id.etLocalPort);
        etToken = (EditText) findViewById(R.id.etToken);
        tvAlert = (TextView) findViewById(R.id.tvAlert);
        tvTokenState = (TextView) findViewById(R.id.tvTokenState);
        tvVersion = (TextView) findViewById(R.id.tvVersion);
        tvVersion.setText("v" + appVersion() + " · " + android.os.Build.SUPPORTED_ABIS[0]);
        cbUnlimited = (android.widget.CheckBox) findViewById(R.id.cbUnlimited);
        tvAlert.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                tvAlert.setVisibility(View.GONE);
            }
        });
        findViewById(R.id.btnGenToken).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                etToken.setText(genToken());
                updateTokenState();
                toast("已生成 32 位随机令牌（约 190 bit 熵）");
            }
        });
        findViewById(R.id.btnSelfTest).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                selfTest();
            }
        });
        etToken.addTextChangedListener(new android.text.TextWatcher() {
            public void afterTextChanged(android.text.Editable s) {
                updateTokenState();
            }
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }
        });
        etHost = (EditText) findViewById(R.id.etHost);
        etTargetPort = (EditText) findViewById(R.id.etTargetPort);
        rgProtocol = (RadioGroup) findViewById(R.id.rgProtocol);
        tvState = (TextView) findViewById(R.id.tvState);
        tvUrl = (TextView) findViewById(R.id.tvUrl);
        tvLog = (TextView) findViewById(R.id.tvLog);
        svLog = (ScrollView) findViewById(R.id.svLog);
        btnStart = (Button) findViewById(R.id.btnStart);
        btnStop = (Button) findViewById(R.id.btnStop);

        etHost.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        loadPrefs();

        btnStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startTunnel();
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopTunnel();
            }
        });
        findViewById(R.id.btnCopyUrl).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copyUrlWithToken();
            }
        });
        findViewById(R.id.btnCopyLog).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copy(Logger.dump());
                toast("全部日志已复制（" + Logger.lineCount() + " 行）");
            }
        });
        findViewById(R.id.btnShareLog).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                shareLog();
            }
        });
        findViewById(R.id.btnClearLog).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Logger.clear();
                tvLog.setText("");
                toast("日志已清空");
            }
        });

        tvUrl.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copyUrlWithToken();
            }
        });

        lastSeenAccess = TunnelService.accessCount;
        Logger.addListener(logListener);
        dirty = true;
        ui.post(refresher);
    }

    private void loadPrefs() {
        SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        etLocalPort.setText(String.valueOf(sp.getInt("localPort", 8888)));
        etHost.setText(sp.getString("targetHost", "127.0.0.1"));
        etTargetPort.setText(String.valueOf(sp.getInt("targetPort", 8080)));
        etToken.setText(sp.getString("token", ""));
        cbUnlimited.setChecked(sp.getBoolean("unlimitedAttack", false));
        String p = sp.getString("protocol", "http2");
        if ("quic".equals(p)) {
            rgProtocol.check(R.id.rbQuic);
        } else {
            rgProtocol.check(R.id.rbHttp2);
        }
        tvLog.setText(Logger.tail(200));
    }

    private void savePrefs(Config c) {
        SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        sp.edit()
                .putInt("localPort", c.localPort)
                .putString("targetHost", c.targetHost)
                .putInt("targetPort", c.targetPort)
                .putString("protocol", c.protocol)
                .putString("token", c.token)
                .apply();
    }

    private int parseInt(EditText et, int def, String name) {
        try {
            String s = et.getText().toString().trim();
            if (s.length() == 0) {
                return def;
            }
            int v = Integer.parseInt(s);
            if (v < 1 || v > 65535) {
                toast(name + " 需在 1-65535 之间");
                return -1;
            }
            return v;
        } catch (Throwable e) {
            toast(name + " 不是合法数字");
            return -1;
        }
    }

    private void startTunnel() {
        Config c = new Config();
        int lp = parseInt(etLocalPort, 8888, "本地端口");
        if (lp < 0) {
            return;
        }
        int tp = parseInt(etTargetPort, 8080, "目标端口");
        if (tp < 0) {
            return;
        }
        String host = etHost.getText().toString().trim();
        if (host.length() == 0) {
            host = "127.0.0.1";
        }
        String hl = host.toLowerCase();
        boolean loopback = hl.equals("127.0.0.1") || hl.equals("localhost") || hl.equals("::1") || hl.equals("0.0.0.0");
        if (loopback && tp == lp) {
            toast("本地端口不能和目标端口相同（会自己转发给自己），请把本地端口改成如 8888");
            return;
        }
        c.localPort = lp;
        c.targetHost = host;
        c.targetPort = tp;
        c.protocol = rgProtocol.getCheckedRadioButtonId() == R.id.rbQuic ? "quic" : "http2";
        c.token = etToken.getText().toString().trim();
        c.unlimitedAttack = cbUnlimited.isChecked();
        c.keepAwake = true;
        savePrefs(c);

        Intent i = new Intent(this, TunnelService.class);
        i.setAction(TunnelService.ACTION_START);
        i.putExtra("localPort", c.localPort);
        i.putExtra("targetHost", c.targetHost);
        i.putExtra("targetPort", c.targetPort);
        i.putExtra("protocol", c.protocol);
        i.putExtra("keepAwake", c.keepAwake);
        i.putExtra("token", c.token);
        i.putExtra("unlimitedAttack", c.unlimitedAttack);
        pendingStart = i;

        boolean running = (TunnelService.state == TunnelService.STATE_STARTING
                || TunnelService.state == TunnelService.STATE_RUNNING);
        if (running) {
            // 运行中改了参数（尤其令牌）必须真正重启才生效
            Intent stop = new Intent(this, TunnelService.class);
            stop.setAction(TunnelService.ACTION_STOP);
            startService(stop);
            toast("配置已更新，正在重启隧道…");
            ui.postDelayed(new Runnable() {
                public void run() {
                    startService(pendingStart);
                }
            }, 1000);
        } else {
            startService(i);
            toast(c.unlimitedAttack ? "⚠ 无限攻击测试模式已开启，防爆破已关闭"
                    : "正在启动，首次运行需要释放二进制，请稍候");
        }

        tvState.setText("状态: 启动中 ...");
    }

    private void stopTunnel() {
        Intent i = new Intent(this, TunnelService.class);
        i.setAction(TunnelService.ACTION_STOP);
        startService(i);
        tvState.setText("状态: 停止中 ...");
    }

    private void refreshStatus() {
        updateTokenState();
        if (TunnelService.accessCount != lastSeenAccess) {
            lastSeenAccess = TunnelService.accessCount;
            showAccessAlert();
        }
        int s = TunnelService.state;
        String u = TunnelService.publicUrl;
        switch (s) {
            case TunnelService.STATE_STARTING:
                tvState.setText("状态: 连接中 ...");
                break;
            case TunnelService.STATE_RUNNING:
                tvState.setText("状态: 运行中");
                break;
            case TunnelService.STATE_STOPPING:
                tvState.setText("状态: 停止中 ...");
                break;
            default:
                tvState.setText("状态: 未运行");
                break;
        }
        if (u != null && u.length() > 0) {
            tvUrl.setText(u + "\n（点击复制）");
        } else if (s == TunnelService.STATE_IDLE) {
            tvUrl.setText("—");
        }
    }

    /**
     * 令牌状态：四态实时反馈。
     * A 空          -> 红色：未启用
     * B 有令牌/未跑 -> 绿色：已填写，启动后生效
     * C 运行中且一致-> 绿色：已启用（N 位）生效中
     * D 运行中但不一致 -> 橙色：改了没重启
     */
    private void updateTokenState() {
        if (tvTokenState == null || etToken == null) {
            return;
        }
        String t = etToken.getText().toString().trim();
        boolean running = (TunnelService.state == TunnelService.STATE_STARTING
                || TunnelService.state == TunnelService.STATE_RUNNING);
        boolean same = TunnelService.tokenSig(t).equals(TunnelService.activeTokenSig);
        if (t.length() == 0) {
            tvTokenState.setTextColor(0xFFD50000);
            tvTokenState.setText("⚠️ 令牌: 未启用 —— 任何人拿到地址即可访问");
        } else if (!running) {
            tvTokenState.setTextColor(0xFF2E7D32);
            tvTokenState.setText("🔒 令牌: 已填写（" + t.length() + " 位），点「启动隧道」后生效");
        } else if (same && TunnelService.tokenActive) {
            tvTokenState.setTextColor(0xFF2E7D32);
            tvTokenState.setText("🔒 令牌: 已启用（" + TunnelService.tokenLen + " 位）· 生效中");
        } else {
            tvTokenState.setTextColor(0xFFEF6C00);
            tvTokenState.setText("⚠️ 令牌已修改，当前运行的是旧令牌（" + TunnelService.tokenLen
                    + " 位）—— 点「启动隧道」重启后生效");
        }
    }

    /** 从系统读版本号，避免源码和实际安装包对不上 */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "?";
        }
    }

    private void showAccessAlert() {
        long ts = TunnelService.lastAccessAt;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(ts);
        String time = String.format("%02d:%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND));
        boolean ok = TunnelService.lastAccessAllowed;
        tvAlert.setVisibility(View.VISIBLE);
        tvAlert.setText("● 有人连接  第 " + TunnelService.accessCount + " 次   " + time
                + (ok ? "   已放行" : "   已拦截")
                + "\n   " + TunnelService.lastAccessDesc);
    }

    /** SecureRandom 走系统 CSPRNG（/dev/urandom），32 位 base62 约 190 bit 熵 */
    private String genToken() {
        final String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        java.security.SecureRandom sr = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(chars.charAt(sr.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private void copyUrlWithToken() {
        String u = TunnelService.publicUrl;
        if (u == null || u.length() == 0) {
            toast("还没有生成隧道地址");
            return;
        }
        String t = etToken.getText().toString().trim();
        if (t.length() == 0) {
            copy(u);
            toast("地址已复制（未启用令牌）");
            return;
        }
        String enc;
        try {
            enc = java.net.URLEncoder.encode(t, "UTF-8");
        } catch (Throwable e) {
            enc = t;
        }
        copy(u + (u.indexOf('?') >= 0 ? "&" : "?") + "token=" + enc);
        toast("地址 + 令牌 已一并复制");
    }

    /**
     * 自检：直接打本地网关端口。
     * 网关生效时，不带令牌的请求会被自家网关拦下返回 401，根本到不了后端服务。
     */
    private void selfTest() {
        final int port = parseInt(etLocalPort, 8888, "本地端口");
        if (port < 0) {
            return;
        }
        final String tk = etToken.getText().toString().trim();
        if (tk.length() == 0) {
            toast("令牌为空，网关未启用，自检无意义");
            return;
        }
        toast("自检中…");
        new Thread(new Runnable() {
            public void run() {
                String noTok = localGet(port, null);
                final String msg;
                if (noTok == null) {
                    msg = "自检失败：本地端口 " + port + " 无响应，请先点「启动」";
                } else if (noTok.contains(" 401 ")) {
                    msg = "自检通过 ✓ 网关已生效（无令牌被拦 401）";
                } else {
                    msg = "自检失败 ✗ 无令牌也能通过，网关没生效：请确认令牌已填 + 点过「启动」";
                }
                ui.post(new Runnable() {
                    public void run() {
                        toast(msg);
                        tvAlert.setVisibility(View.VISIBLE);
                        tvAlert.setText(msg);
                    }
                });
            }
        }, "selftest").start();
    }

    private String localGet(int port, String token) {
        java.net.Socket s = null;
        try {
            s = new java.net.Socket("127.0.0.1", port);
            s.setSoTimeout(5000);
            String req = "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Self-Test: 1\r\n"
                    + (token != null ? "X-Auth-Token: " + token + "\r\n" : "")
                    + "Connection: close\r\n\r\n";
            s.getOutputStream().write(req.getBytes("ISO-8859-1"));
            s.getOutputStream().flush();
            byte[] buf = new byte[512];
            int n = s.getInputStream().read(buf);
            return n > 0 ? new String(buf, 0, n, "ISO-8859-1") : "";
        } catch (Throwable e) {
            return null;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void copy(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("cftunnel", text));
        } catch (Throwable e) {
            toast("复制失败: " + e);
        }
    }

    private void shareLog() {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "Cloudflare 隧道日志");
            i.putExtra(Intent.EXTRA_TEXT, Logger.dump());
            startActivity(Intent.createChooser(i, "分享日志"));
        } catch (Throwable e) {
            toast("分享失败: " + e);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateTokenState();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        Logger.removeListener(logListener);
        ui.removeCallbacks(refresher);
        super.onDestroy();
    }
}

