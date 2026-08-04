package run.halo.weapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies the resource layout consumed by Halo's production plugin loader.
 *
 * <p>Halo discovers extension definitions below {@code extensions/}. Keeping a Setting only at
 * the jar root lets compilation and unit tests pass but causes plugin reconciliation to loop with
 * "setting extension was not found" at runtime.</p>
 */
class PluginResourceLayoutTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    @Test
    void manifestSettingReferenceResolvesToDiscoverableExtension() throws IOException {
        ClassLoader loader = PluginResourceLayoutTest.class.getClassLoader();
        assertNull(loader.getResource("settings.yaml"),
            "Setting must not be left at the jar root");

        try (InputStream manifestStream = loader.getResourceAsStream("plugin.yaml");
             InputStream settingStream = loader.getResourceAsStream("extensions/settings.yaml")) {
            assertNotNull(manifestStream, "plugin.yaml must be packaged");
            assertNotNull(settingStream,
                "Halo only discovers the plugin Setting below extensions/");

            JsonNode manifest = YAML_MAPPER.readTree(manifestStream);
            JsonNode setting = YAML_MAPPER.readTree(settingStream);

            assertEquals("Setting", setting.path("kind").asText());
            assertEquals(
                manifest.path("spec").path("settingName").asText(),
                setting.path("metadata").path("name").asText(),
                "plugin.yaml spec.settingName must reference the packaged Setting");
        }
    }

    @Test
    void anonymousRoleFollowsHaloRequestInfoSemanticsForPublicRoutes() throws IOException {
        ClassLoader loader = PluginResourceLayoutTest.class.getClassLoader();
        try (InputStream roleStream = loader.getResourceAsStream("extensions/roles.yaml")) {
            assertNotNull(roleStream, "anonymous role must be packaged below extensions/");
            JsonNode role = YAML_MAPPER.readTree(roleStream);

            assertResourceRule(role, "config", Set.of(), Set.of("get", "list"));
            assertResourceRule(role, "session", Set.of(), Set.of("create"));
            assertResourceRule(role, "auth", Set.of("profile"), Set.of("get", "patch"));
            assertResourceRule(role, "auth", Set.of("session"), Set.of("delete"));
            assertResourceRule(role, "auth", Set.of("account"), Set.of("delete"));
            assertResourceRule(role, "comments", Set.of(), Set.of("create"));
            assertResourceRule(role, "comments/replies", Set.of(), Set.of("create"));
            assertResourceRule(role, "moments/comments", Set.of(), Set.of("create"));

            // Halo treats POST /resource/{name} as a non-resource request, so /auth/login needs an
            // exact nonResourceURL grant. Reply creation is /comments/{name}/replies instead and is
            // therefore the comments/replies subresource asserted above.
            assertNonResourceRule(role,
                "/apis/api.weapp.halo.run/v1alpha1/auth/login", Set.of("create"));
        }
    }

    private static void assertResourceRule(JsonNode role, String resource,
                                           Set<String> resourceNames, Set<String> verbs) {
        for (JsonNode rule : role.path("rules")) {
            if (!contains(rule.path("resources"), resource)
                || !values(rule.path("resourceNames")).equals(resourceNames)) {
                continue;
            }

            assertEquals(verbs, values(rule.path("verbs")),
                "unexpected verbs for resource " + resource + " names " + resourceNames);
            return;
        }
        throw new AssertionError(
            "missing RBAC rule for resource " + resource + " names " + resourceNames);
    }

    private static void assertNonResourceRule(JsonNode role, String url, Set<String> verbs) {
        for (JsonNode rule : role.path("rules")) {
            if (!contains(rule.path("nonResourceURLs"), url)) {
                continue;
            }
            assertEquals(verbs, values(rule.path("verbs")),
                "unexpected verbs for non-resource URL " + url);
            return;
        }
        throw new AssertionError("missing RBAC rule for non-resource URL " + url);
    }

    private static boolean contains(JsonNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> values(JsonNode values) {
        Set<String> result = new java.util.HashSet<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }
}
