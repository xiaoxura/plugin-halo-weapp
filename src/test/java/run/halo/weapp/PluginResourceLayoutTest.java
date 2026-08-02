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
    void anonymousRoleGrantsCollectionVerbForPublicGetEndpoints() throws IOException {
        ClassLoader loader = PluginResourceLayoutTest.class.getClassLoader();
        try (InputStream roleStream = loader.getResourceAsStream("extensions/roles.yaml")) {
            assertNotNull(roleStream, "anonymous role must be packaged below extensions/");
            JsonNode role = YAML_MAPPER.readTree(roleStream);

            assertRuleVerbs(role, "config", Set.of("get", "list"));
        }
    }

    @Test
    void maintenanceSettingPreservesV020FeatureConfigWithoutEnablingIt() throws IOException {
        ClassLoader loader = PluginResourceLayoutTest.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream("extensions/settings.yaml")) {
            assertNotNull(stream, "maintenance Setting must be packaged below extensions/");
            JsonNode setting = YAML_MAPPER.readTree(stream);
            JsonNode features = null;
            for (JsonNode form : setting.path("spec").path("forms")) {
                if ("features".equals(form.path("group").asText())) {
                    features = form;
                    break;
                }
            }
            assertNotNull(features,
                "v0.1.1 must retain v0.2.0 feature ConfigMap data across rollback");

            Set<String> names = new java.util.HashSet<>();
            for (JsonNode field : features.path("formSchema")) {
                names.add(field.path("name").asText());
                assertEquals(false, field.path("value").asBoolean(true),
                    "forward-compatible feature controls must remain disabled by default");
            }
            assertEquals(Set.of("momentsEnabled", "momentCommentEnabled", "readerAccountEnabled"),
                names);
        }
    }

    private static void assertRuleVerbs(JsonNode role, String resource, Set<String> expected) {
        for (JsonNode rule : role.path("rules")) {
            boolean matches = false;
            for (JsonNode candidate : rule.path("resources")) {
                if (resource.equals(candidate.asText())) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                continue;
            }

            Set<String> actual = new java.util.HashSet<>();
            rule.path("verbs").forEach(verb -> actual.add(verb.asText()));
            assertEquals(expected, actual,
                "public GET endpoint must include Halo's collection-level list verb");
            return;
        }
        throw new AssertionError("missing RBAC rule for resource " + resource);
    }
}
