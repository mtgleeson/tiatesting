package org.tiatesting.core.distributed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link DistributedRunConfig}'s validation rules and accessor round-tripping. Each
 * validation test asserts on the exception message content, not just its type, because the whole
 * point of naming the user-facing property in the message is defeated if a test would pass just
 * as well with a generic message.
 */
class DistributedRunConfigTest {

    /**
     * Verifies that a null runId is rejected and the message names the user-facing property
     * tiaRunId, so a user who left it unset knows exactly which setting to fix.
     */
    @Test
    void validated_nullRunId_throwsNamingTiaRunId() {
        // given - a null runId
        String runId = null;

        // when
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DistributedRunConfig.validated(runId, 4, null, null, null));

        // then
        assertTrue(ex.getMessage().contains("tiaRunId"),
                "message should name tiaRunId, was: " + ex.getMessage());
    }

    /**
     * Verifies that a blank runId is rejected and the message names tiaRunId, the same as a null
     * runId, since a whitespace-only value is just as unusable as no value.
     */
    @Test
    void validated_blankRunId_throwsNamingTiaRunId() {
        // given - a whitespace-only runId
        String runId = "   ";

        // when
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DistributedRunConfig.validated(runId, 4, null, null, null));

        // then
        assertTrue(ex.getMessage().contains("tiaRunId"),
                "message should name tiaRunId, was: " + ex.getMessage());
    }

    /**
     * Verifies that supplying neither groupCount nor targetRunTimeMs is rejected and the message
     * names both user-facing properties, since a user reading it needs to know both options.
     */
    @Test
    void validated_neitherGroupCountNorTargetRunTime_throwsNamingBothProperties() {
        // given - neither groupCount nor targetRunTimeMs supplied

        // when
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DistributedRunConfig.validated("run-1", null, null, null, null));

        // then
        assertTrue(ex.getMessage().contains("tiaDistributedGroupCount"),
                "message should name tiaDistributedGroupCount, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("tiaDistributedTargetRunTime"),
                "message should name tiaDistributedTargetRunTime, was: " + ex.getMessage());
    }

    /**
     * Verifies that supplying both groupCount and targetRunTimeMs is rejected and the message
     * names both user-facing properties, since the two settings are mutually exclusive modes and
     * a user who set both needs to know which pair conflicted.
     */
    @Test
    void validated_bothGroupCountAndTargetRunTime_throwsNamingBothProperties() {
        // given - both groupCount and targetRunTimeMs supplied

        // when
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DistributedRunConfig.validated("run-1", 4, 60000L, null, null));

        // then
        assertTrue(ex.getMessage().contains("tiaDistributedGroupCount"),
                "message should name tiaDistributedGroupCount, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("tiaDistributedTargetRunTime"),
                "message should name tiaDistributedTargetRunTime, was: " + ex.getMessage());
    }

    /**
     * Verifies that a groupCount below 1 is rejected and the message names
     * tiaDistributedGroupCount, so a user who set tiaDistributedGroupCount=0 knows which setting
     * is at fault rather than having to guess from a generic message.
     */
    @Test
    void validated_groupCountBelowOne_throwsNamingTiaDistributedGroupCount() {
        // given - a groupCount of zero

        // when
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DistributedRunConfig.validated("run-1", 0, null, null, null));

        // then
        assertTrue(ex.getMessage().contains("tiaDistributedGroupCount"),
                "message should name tiaDistributedGroupCount, was: " + ex.getMessage());
    }

    /**
     * Verifies that a non-positive targetRunTimeMs is rejected and the message names
     * tiaDistributedTargetRunTime, so a user who set tiaDistributedTargetRunTime=0 knows which
     * setting is at fault.
     */
    @Test
    void validated_targetRunTimeNotPositive_throwsNamingTiaDistributedTargetRunTime() {
        // given - a targetRunTimeMs of zero

        // when
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DistributedRunConfig.validated("run-1", null, 0L, null, null));

        // then
        assertTrue(ex.getMessage().contains("tiaDistributedTargetRunTime"),
                "message should name tiaDistributedTargetRunTime, was: " + ex.getMessage());
    }

    /**
     * Verifies that a negative targetRunTimeMs is rejected the same as zero, and the message
     * names tiaDistributedTargetRunTime.
     */
    @Test
    void validated_negativeTargetRunTime_throwsNamingTiaDistributedTargetRunTime() {
        // given - a negative targetRunTimeMs

        // when
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DistributedRunConfig.validated("run-1", null, -1L, null, null));

        // then
        assertTrue(ex.getMessage().contains("tiaDistributedTargetRunTime"),
                "message should name tiaDistributedTargetRunTime, was: " + ex.getMessage());
    }

    /**
     * Verifies that a maxGroups below 1 is rejected and the message names
     * tiaDistributedMaxGroups, so a user who set tiaDistributedMaxGroups=0 knows which setting is
     * at fault.
     */
    @Test
    void validated_maxGroupsBelowOne_throwsNamingTiaDistributedMaxGroups() {
        // given - a maxGroups of zero alongside a valid target run time

        // when
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DistributedRunConfig.validated("run-1", null, 60000L, 0, null));

        // then
        assertTrue(ex.getMessage().contains("tiaDistributedMaxGroups"),
                "message should name tiaDistributedMaxGroups, was: " + ex.getMessage());
    }

    /**
     * Verifies that supplying maxGroups together with a fixed groupCount is rejected, since a
     * ceiling on an already-fixed group count is meaningless, and the message names both
     * properties so the user understands why the combination is invalid rather than assuming
     * either one alone is wrong.
     */
    @Test
    void validated_maxGroupsWithFixedGroupCount_throwsNamingBothProperties() {
        // given - maxGroups supplied alongside a fixed groupCount

        // when
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DistributedRunConfig.validated("run-1", 4, null, 8, null));

        // then
        assertTrue(ex.getMessage().contains("tiaDistributedMaxGroups"),
                "message should name tiaDistributedMaxGroups, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("tiaDistributedGroupCount"),
                "message should name tiaDistributedGroupCount, was: " + ex.getMessage());
    }

    /**
     * Verifies that a valid static-groups config round-trips its values through the getters and
     * reports isStaticGroups() true / isDynamicGroups() false, confirming the two modes are
     * mutually exclusive and correctly derived from which field was set.
     */
    @Test
    void validated_staticGroupsConfig_roundTripsValuesAndReportsStaticMode() {
        // given - a fixed groupCount, no target run time, no maxGroups

        // when
        DistributedRunConfig config =
                DistributedRunConfig.validated("run-1", 6, null, null, null);

        // then
        assertEquals("run-1", config.getRunId());
        assertEquals(Integer.valueOf(6), config.getGroupCount());
        assertNull(config.getTargetRunTimeMs(), "targetRunTimeMs should be null in static mode");
        assertNull(config.getMaxGroups(), "maxGroups should be null when not supplied");
        assertTrue(config.isStaticGroups(), "should report static groups mode");
        assertFalse(config.isDynamicGroups(), "should not report dynamic groups mode");
    }

    /**
     * Verifies that a valid dynamic-groups config without a maxGroups ceiling round-trips its
     * values and reports isDynamicGroups() true / isStaticGroups() false.
     */
    @Test
    void validated_dynamicGroupsConfigWithoutMaxGroups_roundTripsValuesAndReportsDynamicMode() {
        // given - a target run time, no groupCount, no maxGroups

        // when
        DistributedRunConfig config =
                DistributedRunConfig.validated("run-2", null, 300000L, null, null);

        // then
        assertEquals("run-2", config.getRunId());
        assertNull(config.getGroupCount(), "groupCount should be null in dynamic mode");
        assertEquals(Long.valueOf(300000L), config.getTargetRunTimeMs());
        assertNull(config.getMaxGroups(), "maxGroups should be null when not supplied");
        assertTrue(config.isDynamicGroups(), "should report dynamic groups mode");
        assertFalse(config.isStaticGroups(), "should not report static groups mode");
    }

    /**
     * Verifies that a valid dynamic-groups config with a maxGroups ceiling round-trips the
     * ceiling value alongside the target run time, confirming the two can be combined - unlike
     * maxGroups with a fixed groupCount, which is rejected.
     */
    @Test
    void validated_dynamicGroupsConfigWithMaxGroups_roundTripsCeilingValue() {
        // given - a target run time with a maxGroups ceiling

        // when
        DistributedRunConfig config =
                DistributedRunConfig.validated("run-3", null, 300000L, 10, null);

        // then
        assertEquals(Long.valueOf(300000L), config.getTargetRunTimeMs());
        assertEquals(Integer.valueOf(10), config.getMaxGroups());
        assertTrue(config.isDynamicGroups(), "should report dynamic groups mode");
        assertFalse(config.isStaticGroups(), "should not report static groups mode");
    }

    /**
     * Verifies that a null runnerKey is accepted without error, since it is an optional
     * per-runner value that the planner never reads and stage 5 falls back to deriving its own
     * value when it is absent.
     */
    @Test
    void validated_nullRunnerKey_isAccepted() {
        // given - no runnerKey supplied

        // when
        DistributedRunConfig config =
                DistributedRunConfig.validated("run-4", 3, null, null, null);

        // then
        assertNull(config.getRunnerKey(), "runnerKey should be null when not supplied");
    }

    /**
     * Verifies that a supplied runnerKey round-trips unchanged, confirming this class stores it
     * without interpreting or validating it.
     */
    @Test
    void validated_suppliedRunnerKey_roundTripsUnchanged() {
        // given - a runnerKey value

        // when
        DistributedRunConfig config =
                DistributedRunConfig.validated("run-5", 3, null, null, "runner-abc-1234-99");

        // then
        assertEquals("runner-abc-1234-99", config.getRunnerKey());
    }
}
