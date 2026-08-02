package run.halo.weapp;

import java.time.Duration;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.weapp.identity.IdentityKeyService;
import run.halo.weapp.identity.WeAppUser;
import run.halo.weapp.security.SessionService;

/**
 * HaloWeApp 微信小程序配套插件主类。
 *
 * <p>能力：公开配置下发（/config）、匿名评论短会话（/session）、微信读者身份（/auth/*）、
 * 安全评论与回复写入（/comments、/comments/{name}/replies）。
 * 所有写能力默认关闭，依赖实时配置与微信内容安全检测，详见 docs/。</p>
 */
@Component
public class WeappPlugin extends BasePlugin {

    private final SchemeManager schemeManager;
    private final SessionService sessionService;
    private final IdentityKeyService identityKeyService;
    private Scheme weAppUserScheme;

    public WeappPlugin(PluginContext pluginContext, SchemeManager schemeManager,
                       SessionService sessionService, IdentityKeyService identityKeyService) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.sessionService = sessionService;
        this.identityKeyService = identityKeyService;
    }

    @Override
    public void start() {
        // 早期 v0.2.0 RC 的 ConfigMap 会被 Halo 删除日志序列化，必须先原值迁移并清除明文；
        // 没有旧 key 时该调用不会预生成 Secret。
        identityKeyService.migrateLegacyIfPresent().block(Duration.ofSeconds(30));
        schemeManager.register(WeAppUser.class);
        weAppUserScheme = schemeManager.get(WeAppUser.class);
    }

    @Override
    public void stop() {
        sessionService.clear();
        if (weAppUserScheme != null) {
            schemeManager.unregister(weAppUserScheme);
            weAppUserScheme = null;
        }
    }
}
