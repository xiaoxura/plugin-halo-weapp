package run.halo.weapp.wechat;

import reactor.core.publisher.Mono;

/**
 * 微信服务端 API 抽象，便于测试注入假实现。
 */
public interface WeChatClient {

    /** 用一次性 wx.login code 换取 OpenID（session_key 立即丢弃）。 */
    Mono<Code2SessionResult> code2Session(String code);

    /** 获取小程序全局 access token（带缓存与单飞刷新）。 */
    Mono<String> getAccessToken();

    /** 文本安全检测（msgSecCheck version=2 scene=2），任何异常都失败关闭。 */
    Mono<SecCheckResult> msgSecCheck(String openId, String content);
}
