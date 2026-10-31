# CF-FAR

**Cloudflare 隧道 · 安卓远程访问**

把手机本地或局域网里的任意服务，通过 Cloudflare 临时隧道暴露到公网 —— 不需要账号、不需要域名、不需要服务器。内置访问令牌网关、防爆破锁定和实时访问日志。

[English](README.md) · [简体中文](README.zh-CN.md)

## ⬇️ 下载

[![下载 APK](https://img.shields.io/badge/⬇_下载-CfTunnel--v1.18.apk-2ea44f?style=for-the-badge&logo=android&logoColor=white)](https://github.com/MiraEve/CF-FAR/releases/latest/download/CfTunnel-v1.18.apk)
[![最新版本](https://img.shields.io/github/v/release/MiraEve/CF-FAR?style=for-the-badge&label=%E7%89%88%E6%9C%AC&color=blue)](https://github.com/MiraEve/CF-FAR/releases/latest)
[![开源协议](https://img.shields.io/github/license/MiraEve/CF-FAR?style=for-the-badge&label=%E5%8D%8F%E8%AE%AE&color=orange)](LICENSE)

**下载地址：** [`CfTunnel-v1.18.apk`](https://github.com/MiraEve/CF-FAR/releases/latest/download/CfTunnel-v1.18.apk) · 18 MB · arm64-v8a · Android 9+

> 源码树里**故意不放 APK**，二进制统一放在 **[Releases](https://github.com/MiraEve/CF-FAR/releases/latest)** 页面。打开后在 *Assets* 里点 `CfTunnel-v1.18.apk` 即可。

**安装：** 下载 → 允许「安装未知来源应用」→ 打开 → 设置本地端口 → **启动隧道** → **复制地址 + 令牌**。

校验下载文件：
```bash
sha256sum CfTunnel-v1.18.apk
# f65ae1df7290e54f9ac97548a68e73938450b2ee40933530f36286dd4b4eb7ed
```

---


---

## 这个项目解决什么问题

`cloudflared` 是个 Go 写的二进制，直接丢到 Android 上跑不起来，原因有三个，而且都不明显：

| 问题 | 为什么会坏 | CF-FAR 怎么解决 |
|---|---|---|
| **没有 DNS** | Android 没有 `/etc/resolv.conf`，Go 会退回 `127.0.0.1:53`。但普通应用**无法绑定 1024 以下端口**（内核 `PROT_SOCK=1024`），非 root 设备上根本起不了本地 DNS 中继。 | 内嵌的二进制已经打过补丁：把写死的 `/etc/resolv.conf`（16 字节）原地换成相对路径 `resolv.conf.data`。App 以自身私有目录作为工作目录启动它，并把实时的系统 DNS 写进该文件。 |
| **没有根证书** | Go 的默认证书路径列表**不含** Android 的 `/system/etc/security/cacerts`（已在实际二进制中核实），TLS 握手直接失败。 | 启动时把系统证书合并成一个 PEM（读不到就用内置的 150 张 CA），通过 `SSL_CERT_FILE` 环境变量注入。 |
| **不能执行** | Android 10 起禁止应用从可写目录执行二进制。 | `targetSdk = 28`，运行在 `untrusted_app_27` 域，Android 9 及以下完全允许 `exec`。 |

在此之上，CF-FAR 补齐了 Quick Tunnel 最缺的一环：**访问控制**。

---

## 功能

- **一键起隧道** —— 填个端口，拿到 `https://*.trycloudflare.com` 地址
- **本地端口随便改** —— 监听端口完全可配置（`127.0.0.1:<你定的端口>`）
- **支持局域网目标** —— 转发到 `127.0.0.1`，也可以转发到手机能访问的 `192.168.x.x`
- **访问令牌网关** —— 未通过鉴权的请求**在到达你的服务之前**就被拒掉
- **三种令牌携带方式，各有按钮** —— `?token=`（点开即用的短链，适合临时分享）、`Authorization: Bearer`（URL 保持干净，适合长期使用）、可直接执行的 `curl` 命令
- **仅 Header 模式** —— 一个开关让网关**彻底拒绝 `?token=`**，令牌再也不可能通过 URL、访问日志、浏览器历史或 `Referer` 泄露
- **防爆破** —— 连续 5 次失败 → 锁定 60 秒（返回 `429` + `Retry-After`）
- **审计模式** —— 一个开关关掉锁定，方便跑无限次渗透测试
- **实时访问提醒** —— 有人连入时立刻弹红色横幅，并显示**真实来源 IP**（取自 `CF-Connecting-IP`）
- **完整日志** —— 4000 行环形缓冲 + 落盘文件，一键复制 / 分享
- **稳定运行** —— 崩溃指数退避重连、前台服务、唤醒锁、通知栏状态

---

## 快速开始

1. 安装 `CfTunnel-arm64.apk`（Android 5.0+，**仅 arm64-v8a**）
2. 填 **本地端口**（默认 `8888`）和 **目标**（如 `127.0.0.1:8080`）
3. 点 **随机** 生成一个 32 位令牌（`SecureRandom`，约 190 bit 熵）
4. 点 **启动**，等到绿色地址出现，点 **复制地址** —— 剪贴板里是
   `https://xxx.trycloudflare.com?token=<你的令牌>`
5. 点 **自检** 确认网关真的生效了

> **令牌框是锁，URL 里的 `?token=` 是钥匙。** 只设一个不设另一个，等于没设。

---

## 鉴权方式

三种任选其一：

```http
GET /?token=<令牌>
X-Auth-Token: <令牌>
Authorization: Bearer <令牌>
```

- 比较是**精确匹配**（不做子串、不做前缀），参数名也精确匹配，`?xtoken=` 之类无效
- URL 编码过的令牌会先解码再比较
- 失败只返回极简的 `{"error":"unauthorized"}`，你的服务完全接触不到
- 连续失败 5 次 → `429 Too Many Requests`，锁 60 秒

---

## 安全模型

CF-FAR 是**传输网关**，不是完整 WAF。它保证这些：

| 保证 | 状态 |
|---|---|
| 未鉴权请求永远到不了后端 | ✅ |
| 一个 TCP 连接只处理一个请求（杜绝 keep-alive 复用绕过） | ✅ |
| HTTP 流水线 / 请求走私被阻断 | ✅ |
| 令牌精确比较，无前后缀绕过 | ✅ |
| 防爆破锁定 | ✅（审计模式下可关） |
| 界面与日志显示真实客户端 IP | ✅ |

**已知边界 —— 暴露敏感服务前请务必阅读：**

- 锁定是全局的，攻击者可以故意连错 5 次把你锁 60 秒（小规模 DoS）
- 令牌放在 URL 里会进入 Cloudflare 日志、浏览器历史和 `Referer`，长期使用请走 Header
- 没有 IP 白名单，也没有按 IP 限流
- Quick Tunnel **没有可用性保证**，每次重启地址都会变
- CF-FAR **保护不了**同时能从局域网直连的服务 —— 请把后端绑定到 `127.0.0.1` 而不是 `0.0.0.0`

漏洞披露政策见 [SECURITY.md](SECURITY.md)，完整的对抗性测试记录见
[docs/SECURITY-AUDIT.md](docs/SECURITY-AUDIT.md)（5 轮、60+ 攻击向量、发现并修复 5 个真实漏洞）。

---

## 架构

```
远程客户端
     │  HTTPS
     ▼
Cloudflare 边缘  ──隧道 (HTTP/2)──►  cloudflared（内嵌，已打补丁）
                                          │
                                          ▼
                               127.0.0.1:<本地端口>   ← 令牌网关
                                          │
                                          ▼
                               目标（127.0.0.1 / 局域网）
```

细节见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

---

## 构建

项目刻意不用 Gradle，方便在极简环境里构建，步骤见 [docs/BUILD.md](docs/BUILD.md)。

```bash
./scripts/fetch-cloudflared.sh   # 下载并打补丁 arm64 二进制
./scripts/build.sh               # aapt2 → javac → d8 → zipalign → apksigner
```

本仓库**不附带预编译 APK** —— 自己构建，仓库更小，二进制也可审计。

---

## 协议

[MIT](LICENSE) © MiraEve

内嵌的第三方组件（完整声明见 [NOTICE](NOTICE)）：

- **cloudflared** —— Apache-2.0，© Cloudflare, Inc.，构建时下载并原地打补丁
- **Mozilla CA bundle** —— MPL-2.0，作为兜底证书集

---

## 免责声明

CF-FAR 用于把**你自己的**服务临时暴露出去做远程访问。
你对暴露的内容负责，并需遵守
[Cloudflare 服务条款](https://www.cloudflare.com/website-terms/)。
请勿用它绕过任何你不拥有的系统的访问控制。
