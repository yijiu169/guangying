package com.github.tvbox.osc.update;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次「最新版本」的描述。
 */
public class ReleaseInfo implements Serializable {

    /** 发布标签，例如 v1.0.9 */
    public String tag = "";

    /** 展示用版本名 */
    public String versionName = "";

    /** 版本序号（用于和当前安装版本比较，取不到时为 -1） */
    public int versionCode = -1;

    /** 更新说明 */
    public String notes = "";

    /** APK 的原始 GitHub 下载地址（未加镜像前缀） */
    public String apkUrl = "";

    /** 文件大小（字节），0 表示未知 */
    public long size = 0L;

    /** APK 的 SHA-256（小写），空表示未提供 */
    public String sha256 = "";

    /** 是否强制更新 */
    public boolean force = false;

    /** 发布时间 */
    public String publishedAt = "";

    /** 元数据是从哪条通道拿到的（direct / mirror:<prefix> / api），便于日志排查 */
    public String source = "";

    /**
     * 服务端在 version.json 里额外提供的 GitHub 代理前缀（如 "https://gh-proxy.com/"）。
     * 用于包裹 apkUrl 形成镜像地址。即使 APK 里内置的镜像全失效，
     * 只要改 version.json 重发 Release 即可换镜像，无需重编 APK。
     */
    public List<String> extraMirrors = new ArrayList<>();

    /**
     * 服务端直接给的完整 APK 下载地址（如自建 CDN）。优先级最高，
     * 可完全脱离 GitHub 体系——这是「GitHub 直连不上」时最稳的兜底。
     */
    public List<String> apkMirrors = new ArrayList<>();

    @Override
    public String toString() {
        return "ReleaseInfo{tag=" + tag + ", name=" + versionName + ", code=" + versionCode
                + ", size=" + size + ", source=" + source + '}';
    }
}
