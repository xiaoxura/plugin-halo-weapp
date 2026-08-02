package run.halo.weapp.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SemverPolicyTest {

    @Test
    void comparesCoreAndPrereleaseVersions() {
        assertEquals(-1, SemverPolicy.compare("0.3.9", "0.4.0"));
        assertEquals(0, SemverPolicy.compare("0.4.0+build.2", "0.4.0+build.1"));
        assertEquals(-1, SemverPolicy.compare("0.4.0-rc.1", "0.4.0"));
        assertEquals(1, SemverPolicy.compare("0.4.0-rc.10", "0.4.0-rc.2"));
    }

    @Test
    void rejectsInvalidVersions() {
        assertNull(SemverPolicy.compare("v0.4.0", "0.4.0"));
        assertNull(SemverPolicy.compare("0.4", "0.4.0"));
        assertNull(SemverPolicy.compare("0.4.0-01", "0.4.0"));
    }
}
