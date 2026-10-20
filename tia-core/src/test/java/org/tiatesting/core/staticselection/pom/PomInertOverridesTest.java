package org.tiatesting.core.staticselection.pom;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PomInertOverrides}: a POM-aware rule's own inert plugins and profiles.
 */
class PomInertOverridesTest {

    /**
     * Verify a bare artifactId matches the plugin under any groupId and records the reason.
     */
    @Test
    void bareArtifactIdMatchesAnyGroupId() {
        // given
        PomInertOverrides overrides = PomInertOverrides.parse("remote-maven-plugin", null);
        PomChange change = buildPluginChange("com.acme.rs:remote-maven-plugin");

        // when
        PomChange applied = overrides.apply(change);

        // then
        assertTrue(applied.isInert());
        assertEquals("plugin com.acme.rs:remote-maven-plugin changed: configuration (plugin listed in inertPlugins)",
                applied.getDescription());
    }

    /**
     * Verify a {@code groupId:artifactId} entry matches only that groupId.
     */
    @Test
    void qualifiedEntryMatchesOnlyItsGroupId() {
        // given
        PomInertOverrides overrides = PomInertOverrides.parse("com.acme:upload-plugin", null);

        // when
        PomChange same = overrides.apply(buildPluginChange("com.acme:upload-plugin"));
        PomChange other = overrides.apply(buildPluginChange("org.other:upload-plugin"));

        // then
        assertTrue(same.isInert());
        assertFalse(other.isInert());
    }

    /**
     * Verify a profile regex must match the whole profile id.
     */
    @Test
    void profileRegexMatchesTheWholeId() {
        // given
        PomInertOverrides overrides = PomInertOverrides.parse(null, "Deploy.*");
        PomChange deploy = PomChange.functional(PomChangeCategory.OTHER, "x in profile DeployToGCS").inProfile("DeployToGCS");
        PomChange preDeploy = PomChange.functional(PomChangeCategory.OTHER, "x in profile PreDeploy").inProfile("PreDeploy");

        // when
        PomChange appliedDeploy = overrides.apply(deploy);
        PomChange appliedPreDeploy = overrides.apply(preDeploy);

        // then
        assertTrue(appliedDeploy.isInert());
        assertTrue(appliedDeploy.getDescription().endsWith("(in profile DeployToGCS matching inertProfiles)"),
                appliedDeploy.getDescription());
        assertFalse(appliedPreDeploy.isInert());
    }

    /**
     * Verify a change with no matching tag, and an already inert change, are returned unchanged.
     */
    @Test
    void untouchedChangesAreReturnedAsIs() {
        // given
        PomInertOverrides overrides = PomInertOverrides.parse("upload-plugin", "Deploy.*");
        PomChange dependency = PomChange.functional(PomChangeCategory.DEPENDENCY_VERSIONS, "dependency x 1 -> 2");
        PomChange inert = PomChange.inert("plugin y changed").inPlugin("com.acme:upload-plugin");

        // when / then
        assertSame(dependency, overrides.apply(dependency));
        assertSame(inert, overrides.apply(inert));
    }

    /**
     * Verify entries are trimmed, blanks are dropped, and blank settings give {@link PomInertOverrides#NONE}.
     */
    @Test
    void entriesAreTrimmedAndBlankSettingsAreNone() {
        // given / when
        PomInertOverrides overrides = PomInertOverrides.parse(" a , com.acme:b ,", " Deploy.* , Release.* ");
        PomInertOverrides blank = PomInertOverrides.parse(" , ", "");

        // then
        assertEquals(new LinkedHashSet<>(Arrays.asList("a", "com.acme:b")), overrides.getPlugins());
        assertEquals(Arrays.asList("Deploy.*", "Release.*"), overrides.getProfilePatterns());
        assertSame(PomInertOverrides.NONE, blank);
        assertTrue(blank.isEmpty());
        assertEquals(Collections.emptySet(), PomInertOverrides.NONE.getPlugins());
    }

    /**
     * Verify an invalid profile regex fails with the setting name and the regex.
     */
    @Test
    void invalidProfileRegexFails() {
        // given / when
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PomInertOverrides.parse(null, "Deploy("));

        // then
        assertTrue(error.getMessage().startsWith("inertProfiles: invalid regex 'Deploy('"), error.getMessage());
    }

    /**
     * @param pluginKey the plugin's {@code groupId:artifactId}.
     * @return a {@code BUILD_PLUGINS} configuration change tagged with the plugin.
     */
    private static PomChange buildPluginChange(String pluginKey) {
        return PomChange.functional(PomChangeCategory.BUILD_PLUGINS, "plugin " + pluginKey + " changed: configuration")
                .inPlugin(pluginKey);
    }
}
