package org.tiatesting.maven;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Cover {@link AgentMojo#skipJacocoWhenNotNeeded}: a run that does not update the mapping sets
 * {@code jacoco.skip} so a JaCoCo {@code prepare-agent} declared after Tia adds no agent, while a
 * mapping run or a disabled Tia leaves JaCoCo alone.
 */
class AgentMojoJacocoSkipTest {

    /**
     * Mojo whose enabled and update-mapping flags are set directly, so the skip decision can be
     * driven without a Maven session.
     */
    private static final class TestMojo extends AgentMojo {
        private final boolean enabled;
        private final boolean updateDBMapping;

        /**
         * @param enabled         the value {@link #isTiaEnabled()} returns
         * @param updateDBMapping the value {@link #isTiaUpdateDBMapping()} returns
         */
        TestMojo(final boolean enabled, final boolean updateDBMapping) {
            this.enabled = enabled;
            this.updateDBMapping = updateDBMapping;
        }

        @Override
        public boolean isTiaEnabled() {
            return enabled;
        }

        @Override
        public boolean isTiaUpdateDBMapping() {
            return updateDBMapping;
        }
    }

    @Test
    void setsJacocoSkipWhenNotUpdatingTheMapping() {
        // given
        TestMojo mojo = new TestMojo(true, false);
        Properties projectProperties = new Properties();

        // when
        mojo.skipJacocoWhenNotNeeded(projectProperties);

        // then
        assertEquals("true", projectProperties.getProperty(AgentMojo.JACOCO_SKIP_PROPERTY));
    }

    @Test
    void overridesAProjectJacocoSkipOfFalseWhenNotUpdatingTheMapping() {
        // given
        TestMojo mojo = new TestMojo(true, false);
        Properties projectProperties = new Properties();
        projectProperties.setProperty(AgentMojo.JACOCO_SKIP_PROPERTY, "false");

        // when
        mojo.skipJacocoWhenNotNeeded(projectProperties);

        // then
        assertEquals("true", projectProperties.getProperty(AgentMojo.JACOCO_SKIP_PROPERTY));
    }

    @Test
    void leavesJacocoAloneWhenUpdatingTheMapping() {
        // given
        TestMojo mojo = new TestMojo(true, true);
        Properties projectProperties = new Properties();

        // when
        mojo.skipJacocoWhenNotNeeded(projectProperties);

        // then
        assertNull(projectProperties.getProperty(AgentMojo.JACOCO_SKIP_PROPERTY));
    }

    @Test
    void leavesJacocoAloneWhenTiaIsDisabled() {
        // given
        TestMojo mojo = new TestMojo(false, false);
        Properties projectProperties = new Properties();

        // when
        mojo.skipJacocoWhenNotNeeded(projectProperties);

        // then
        assertNull(projectProperties.getProperty(AgentMojo.JACOCO_SKIP_PROPERTY));
    }
}
