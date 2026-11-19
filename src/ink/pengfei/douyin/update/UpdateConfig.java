package ink.pengfei.douyin.update;

/**
 * 检查更新的唯一配置点。应用 ID 取自运行时包名，版本号取自安装包自身。
 */
final class UpdateConfig {
    private UpdateConfig() {}

    /** 更新登记服务。 */
    static final String BASE = "https://update.pengfei.ink";
    /** 渠道：stable = 正式版。 */
    static final String CHANNEL = "stable";
    /** 自动检查的最短间隔。 */
    static final long CHECK_INTERVAL_MS = 6 * 3600_000L;
    /** 「稍后」之后多久内不再自动弹窗。 */
    static final long SNOOZE_MS = 24 * 3600_000L;
    static final int CONNECT_TIMEOUT_MS = 6_000, READ_TIMEOUT_MS = 10_000;
}
