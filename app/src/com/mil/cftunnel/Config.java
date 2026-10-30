package com.mil.cftunnel;

/** 用户可调配置 */
public final class Config {

    public int localPort = 8888;
    public String targetHost = "127.0.0.1";
    public int targetPort = 8080;
    /** http2 / quic */
    public String protocol = "http2";
    /** 0=auto 4=仅IPv4 6=仅IPv6 */
    public int edgeIpVersion = 0;
    public boolean keepAwake = true;
    /** 访问令牌，空字符串表示不启用 */
    public String token = "";
    /** 无限攻击测试模式：关闭防爆破锁定 */
    public boolean unlimitedAttack = false;
    /** 仅 Header 鉴权：拒绝 ?token= 查询参数，避免令牌出现在 URL/日志/Referer 里 */
    public boolean headerOnly = false;

    public String localUrl() {
        return "http://127.0.0.1:" + localPort;
    }

    public String targetDesc() {
        return targetHost + ":" + targetPort;
    }
}

