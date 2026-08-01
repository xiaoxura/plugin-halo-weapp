package run.halo.weapp;

import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * HaloWeApp 微信小程序配套插件主类。
 *
 * <p>能力：公开配置下发（/config）、微信登录短会话（/session）、
 * 安全评论与回复写入（/comments、/comments/{name}/replies）。
 * 所有写能力默认关闭，依赖实时配置与微信内容安全检测，详见 docs/。</p>
 */
@Component
public class WeappPlugin extends BasePlugin {

    public WeappPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        // 启动诊断在 diagnostics 包中按需执行，此处不做阻塞性检查
    }

    @Override
    public void stop() {
        // 内存会话随插件停止全部失效，客户端将收到 SESSION_EXPIRED 并重新登录
    }
}
