package org.tiatesting.vcs.perforce.connection;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock the parsing of {@code p4 set} output against the shapes it really takes on each OS.
 *
 * <p>This is the half of the P4 settings flow that was silently Windows-only. Running the command
 * through {@code cmd.exe} was the obvious platform dependency, but the parser carried a second one:
 * it stripped the literal {@code " (set)"} that only Windows emits, so on macOS and Linux the
 * source annotation stayed attached to the value and was concatenated straight into the Perforce
 * connection URI.
 *
 * <p>Parsing is tested separately from running the command precisely so these cases can be covered
 * on any machine, with or without a {@code p4} binary installed, and on an OS other than the one
 * whose output is being asserted.
 */
class P4SettingsParseTest {

    /**
     * Windows reports values held in the registry with a {@code (set)} annotation. This is the only
     * form the original parser handled, and it must keep working.
     */
    @Test
    void windowsRegistryOutputIsParsed() {
        // given
        List<String> lines = Arrays.asList(
                "P4CLIENT=my-workspace (set)",
                "P4PORT=ssl:perforce.example.com:1666 (set)",
                "P4USER=mgleeson (set)");

        // when
        Map<String, String> settings = P4Settings.parseP4SetOutput(lines);

        // then
        assertEquals("my-workspace", settings.get(P4Constants.P4CLIENT));
        assertEquals("ssl:perforce.example.com:1666", settings.get(P4Constants.P4PORT));
        assertEquals("mgleeson", settings.get(P4Constants.P4USER));
    }

    /**
     * <b>The case that was broken.</b> On macOS and Linux the values come from the environment or a
     * P4CONFIG file, and the annotation says so. Left attached, {@code P4PORT} would come back as
     * {@code ssl:perforce.example.com:1666 (config)} and be concatenated into the connection URI.
     */
    @Test
    void unixEnvironmentAndConfigOutputIsParsed() {
        // given
        List<String> lines = Arrays.asList(
                "P4CLIENT=my-workspace (config \"/Users/mgleeson/ws/.p4config\")",
                "P4PORT=ssl:perforce.example.com:1666 (config)",
                "P4USER=mgleeson (enviro)");

        // when
        Map<String, String> settings = P4Settings.parseP4SetOutput(lines);

        // then
        assertEquals("my-workspace", settings.get(P4Constants.P4CLIENT),
                "a value sourced from a P4CONFIG file must not keep the file path annotation");
        assertEquals("ssl:perforce.example.com:1666", settings.get(P4Constants.P4PORT),
                "a value sourced from a config must not keep the annotation - it would end up in "
                        + "the connection URI");
        assertEquals("mgleeson", settings.get(P4Constants.P4USER),
                "a value sourced from the environment must not keep the annotation");
    }

    /**
     * <b>Real captured output</b> from a macOS workspace, verbatim apart from the host and user
     * names. This is the ground truth the invented cases above are modelled on, and it is worth
     * pinning for two reasons.
     *
     * <p>First, the three settings Tia reads carry <b>no annotation at all</b> here, so this machine
     * never hit the annotation bug. That is why the OS fix appeared to work on macOS with only the
     * command-invocation half applied: whether a value is annotated depends on where it was set, not
     * on the platform, and nothing on this machine set them anywhere that annotates.
     *
     * <p>Second, {@code P4_<port>_CHARSET} shows that {@code p4} emits variable names with the port
     * embedded in them, dots and colons included. It must be ignored rather than mistaken for one of
     * the names Tia reads - the failure mode a substring match invites.
     */
    @Test
    void realMacOsOutputIsParsed() {
        // given - the exact output of `p4 set` on a macOS workspace
        List<String> lines = Arrays.asList(
                "P4CLIENT=abc_mainline_mac",
                "P4PORT=abc.com:1666",
                "P4USER=user",
                "P4_abc.com:1666_CHARSET=auto (enviro)");

        // when
        Map<String, String> settings = P4Settings.parseP4SetOutput(lines);

        // then
        assertEquals("abc_mainline_mac", settings.get(P4Constants.P4CLIENT));
        assertEquals("abc.com:1666", settings.get(P4Constants.P4PORT),
                "an unannotated value must survive the annotation stripping untouched - this is "
                        + "what goes straight into the p4java connection URI");
        assertEquals("user", settings.get(P4Constants.P4USER));
        assertEquals(3, settings.size(),
                "the per-port charset variable is not a setting Tia reads, so it must not be "
                        + "collected or mistaken for one that is");
    }

    /**
     * A value with no annotation at all is left exactly as it stands, so the stripping cannot eat
     * into a legitimate value.
     */
    @Test
    void anUnannotatedValueIsUnchanged() {
        // given
        List<String> lines = Collections.singletonList("P4PORT=ssl:perforce.example.com:1666");

        // when
        Map<String, String> settings = P4Settings.parseP4SetOutput(lines);

        // then
        assertEquals("ssl:perforce.example.com:1666", settings.get(P4Constants.P4PORT));
    }

    /**
     * More than one trailing annotation is stripped. {@code p4} can report both where a value is
     * set and where it was overridden from.
     */
    @Test
    void everyTrailingAnnotationIsStripped() {
        // given
        List<String> lines = Collections.singletonList("P4CLIENT=my-workspace (set) (config)");

        // when
        Map<String, String> settings = P4Settings.parseP4SetOutput(lines);

        // then
        assertEquals("my-workspace", settings.get(P4Constants.P4CLIENT));
    }

    /**
     * <b>The substring collision.</b> {@code P4CLIENTPATH} contains {@code P4CLIENT}, and
     * {@code p4 set} output is alphabetical, so a {@code contains} match parsed the real value first
     * and then overwrote it with the wrong line. Names must match as a whole assignment.
     */
    @Test
    void aLongerVariableNameDoesNotOverwriteTheOneItStartsWith() {
        // given - the order p4 set really emits them in
        List<String> lines = Arrays.asList(
                "P4CLIENT=my-workspace (config)",
                "P4CLIENTPATH=/Users/mgleeson/ws (config)");

        // when
        Map<String, String> settings = P4Settings.parseP4SetOutput(lines);

        // then
        assertEquals("my-workspace", settings.get(P4Constants.P4CLIENT),
                "P4CLIENTPATH must not be mistaken for P4CLIENT and overwrite it");
        assertFalse(settings.containsKey("P4CLIENTPATH"),
                "P4CLIENTPATH is not a setting Tia reads, so it must not be collected");
    }

    /**
     * Lines that are not assignments are skipped rather than parsed. {@code p4 set} prints
     * explanatory text when it has nothing to report, and output can carry blank lines.
     */
    @Test
    void nonAssignmentLinesAreSkipped() {
        // given
        List<String> lines = Arrays.asList(
                "",
                "   ",
                "Perforce client environment settings:",
                "P4PORT=ssl:perforce.example.com:1666 (enviro)");

        // when
        Map<String, String> settings = P4Settings.parseP4SetOutput(lines);

        // then
        assertEquals(1, settings.size(), "only the assignment line contributes a setting");
        assertEquals("ssl:perforce.example.com:1666", settings.get(P4Constants.P4PORT));
    }

    /**
     * Output carrying none of the settings Tia looks for yields an empty map rather than failing.
     * The caller falls back to whatever is configured in the Tia settings.
     */
    @Test
    void outputWithNoRelevantSettingsYieldsAnEmptyMap() {
        // given
        List<String> lines = Arrays.asList(
                "P4EDITOR=vi (config)",
                "P4IGNORE=.p4ignore (config)");

        // when
        Map<String, String> settings = P4Settings.parseP4SetOutput(lines);

        // then
        assertTrue(settings.isEmpty(),
                "none of these are settings Tia reads, so none should be collected");
    }

    /**
     * Surrounding whitespace, including the carriage return left by output written on Windows and
     * read on a platform whose reader does not strip it, does not end up in the value.
     */
    @Test
    void surroundingWhitespaceIsNotPartOfTheValue() {
        // given
        List<String> lines = Collections.singletonList("  P4USER=mgleeson (set)  ");

        // when
        Map<String, String> settings = P4Settings.parseP4SetOutput(lines);

        // then
        assertEquals("mgleeson", settings.get(P4Constants.P4USER));
    }

    /**
     * A null line is skipped rather than throwing, so one unexpected entry cannot abort the parse of
     * the rest.
     */
    @Test
    void nullLinesAreSkipped() {
        // given
        List<String> lines = Arrays.asList(null, "P4USER=mgleeson (enviro)");

        // when
        Map<String, String> settings = P4Settings.parseP4SetOutput(lines);

        // then
        assertEquals("mgleeson", settings.get(P4Constants.P4USER));
    }
}
