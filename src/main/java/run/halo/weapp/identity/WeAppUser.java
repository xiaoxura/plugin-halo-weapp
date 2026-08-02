package run.halo.weapp.identity;

import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 微信读者内部扩展。
 *
 * <p>只持久化用户主动填写的昵称和隐私版本；原始 OpenID、AppID、完整摘要、token
 * 与 identityKey 均不得进入该资源。该 GVK 不聚合到 anonymous 角色。</p>
 */
@GVK(
    group = "weapp.halo.run",
    version = "v1alpha1",
    kind = "WeAppUser",
    plural = "weappusers",
    singular = "weappuser"
)
public class WeAppUser extends AbstractExtension {

    private WeAppUserSpec spec;

    public WeAppUserSpec getSpec() {
        return spec;
    }

    public void setSpec(WeAppUserSpec spec) {
        this.spec = spec;
    }

    public static class WeAppUserSpec {

        private String displayName;
        private String privacyPolicyVersion;

        public WeAppUserSpec() {
        }

        public WeAppUserSpec(String displayName, String privacyPolicyVersion) {
            this.displayName = displayName;
            this.privacyPolicyVersion = privacyPolicyVersion;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getPrivacyPolicyVersion() {
            return privacyPolicyVersion;
        }

        public void setPrivacyPolicyVersion(String privacyPolicyVersion) {
            this.privacyPolicyVersion = privacyPolicyVersion;
        }
    }
}
