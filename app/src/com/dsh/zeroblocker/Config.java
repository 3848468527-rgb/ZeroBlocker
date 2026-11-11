package com.dsh.zeroblocker;

/**
 * 品牌与文案配置。
 * 接不同客户的定制单时：改这个文件 + AndroidManifest.xml 的 label + res/drawable/ic_launcher.png，
 * 然后重新编译一次即可，不用改业务代码。
 */
public final class Config {

    /** 应用名（桌面显示的名字在 AndroidManifest 里，也要一起改） */
    public static final String APP_TITLE = "零号拦截";

    /** 首页副标题 */
    public static final String APP_SUBTITLE = "0 开头来电自动拦截 · 运营商营销号拦截";

    /** 主题色（ARGB），用于标题和按钮点缀 */
    public static final int ACCENT_COLOR = 0xFF0B7A5A;

    /** 首页底部说明文字 */
    public static final String FOOTER =
            "· 拦截在响铃前完成：手机不响，对方听到忙音 / 已拒接；\n"
                    + "· 通讯录联系人和你最近 30 天拨出过的号码回拨会自动放行；\n"
                    + "· 支持自定义「开头号码」规则、黑名单、白名单；\n"
                    + "· 所有判断都在本机完成，App 不联网、不上传任何数据。";

    private Config() {
    }
}
