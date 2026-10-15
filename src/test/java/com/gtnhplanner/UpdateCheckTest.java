package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.client.UpdateCheck;

/** The update check's reading of versions and of GitHub's list of releases. */
class UpdateCheckTest {

    @Test
    void comparesVersions() {
        assertTrue(UpdateCheck.newer("0.2.0-alpha.6", "0.2.0-alpha.5"));
        assertTrue(UpdateCheck.newer("0.2.0-alpha.10", "0.2.0-alpha.9"));
        assertTrue(UpdateCheck.newer("0.2.0", "0.2.0-alpha.9"));
        assertTrue(UpdateCheck.newer("0.3.0-alpha.1", "0.2.0"));
        assertFalse(UpdateCheck.newer("0.2.0-alpha.5", "0.2.0-alpha.5"));
        assertFalse(UpdateCheck.newer("0.2.0-alpha.4", "0.2.0-alpha.5"));
        // A dev build is its tag's version: the same tag is not newer, the next one is.
        assertFalse(UpdateCheck.newer("0.2.0-alpha.5", "0.2.0-alpha.5-claude-x.2+f69732c5fd-dirty"));
        assertTrue(UpdateCheck.newer("0.2.0-alpha.6", "0.2.0-alpha.5-claude-x.2+f69732c5fd-dirty"));
        assertFalse(UpdateCheck.newer("nightly", "0.2.0-alpha.5"));
        // Alpha, beta, rc, then the release.
        assertTrue(UpdateCheck.newer("0.2.0-beta.1", "0.2.0-alpha.5"));
        assertTrue(UpdateCheck.newer("0.2.0-beta.2", "0.2.0-beta.1"));
        assertTrue(UpdateCheck.newer("0.2.0-rc.1", "0.2.0-beta.9"));
        assertTrue(UpdateCheck.newer("0.2.0", "0.2.0-rc.3"));
        assertFalse(UpdateCheck.newer("0.2.0-alpha.9", "0.2.0-beta.1"));
        assertFalse(UpdateCheck.newer("0.2.0-beta.1", "0.2.0-beta.1-main.3+58f9e3d1a2"));
        assertTrue(UpdateCheck.newer("0.2.0-beta.2", "0.2.0-beta.1-main.3+58f9e3d1a2"));
    }

    @Test
    void picksTheNewestReleaseThatIsNotADraft() throws Exception {
        final String json = "[{\"tag_name\":\"0.2.0-alpha.7\",\"html_url\":\"u7\",\"draft\":true},"
            + "{\"tag_name\":\"0.2.0-alpha.5\",\"html_url\":\"u5\",\"draft\":false},"
            + "{\"tag_name\":\"0.2.0-alpha.6\",\"html_url\":\"u6\",\"draft\":false,\"prerelease\":true}]";
        final UpdateCheck.Release r = newest(json);
        assertEquals("0.2.0-alpha.6", r.tag());
        assertEquals("u6", r.url());
        assertNull(newest("{\"message\":\"rate limited\"}"));
    }

    private static UpdateCheck.Release newest(final String json) throws Exception {
        final Method m = UpdateCheck.class.getDeclaredMethod("newest", String.class);
        m.setAccessible(true);
        return (UpdateCheck.Release) m.invoke(null, json);
    }
}
