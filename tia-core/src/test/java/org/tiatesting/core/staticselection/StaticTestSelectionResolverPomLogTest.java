package org.tiatesting.core.staticselection;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.staticselection.pom.PomChange;
import org.tiatesting.core.staticselection.pom.PomChangeCategory;
import org.tiatesting.core.staticselection.pom.PomChangeReport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the DEBUG description of a POM-aware rule's matched POMs: each POM's own changes on
 * its own line, and changes inherited from an external parent or BOM listed once however many
 * matched POMs inherit them.
 */
class StaticTestSelectionResolverPomLogTest {

    private static final String RULE = "pom-changes";
    private static final String PARENT = "parent org.boot:boot-parent:2";
    private static final String PARENT_BUMP = "external parent org.boot:boot-parent version 1 -> 2 "
            + "(its changes are listed separately)";

    /**
     * Two POMs inheriting the same parent changes list them once, on a parent line naming both.
     */
    @Test
    void sharedParentChangesAreListedOnce() {
        // given
        List<PomChange> fromParent = Arrays.asList(managedBump("org.lib:lib"), managedBump("org.lib:other"));
        Map<String, PomChangeReport> reports = new HashMap<>();
        reports.put("a/pom.xml", report(PomChange.inert(PARENT_BUMP), fromParent));
        reports.put("b/pom.xml", report(PomChange.inert(PARENT_BUMP), fromParent));

        // when
        List<String> lines = StaticTestSelectionResolver.describeMatchedPoms(RULE,
                Arrays.asList("a/pom.xml", "b/pom.xml"), reports);

        // then
        assertEquals(Arrays.asList(
                "Static test selection rule 'pom-changes' POM a/pom.xml: 1 change(s) of its own: ["
                        + PARENT_BUMP + " (inert)]; 2 inherited from " + PARENT,
                "Static test selection rule 'pom-changes' POM b/pom.xml: 1 change(s) of its own: ["
                        + PARENT_BUMP + " (inert)]; 2 inherited from " + PARENT,
                "Static test selection rule 'pom-changes' " + PARENT + ": 2 change(s), inherited by "
                        + "[a/pom.xml, b/pom.xml]: " + inherited(fromParent)), lines);
    }

    /**
     * A POM with only its own changes gets its own line and no parent line.
     */
    @Test
    void ownChangesOnlyLogNoParentLine() {
        // given
        PomChange plugin = PomChange.functional(PomChangeCategory.BUILD_PLUGINS, "plugin x version 1 -> 2");
        Map<String, PomChangeReport> reports = Collections.singletonMap("pom.xml",
                new PomChangeReport(Collections.singletonList(plugin)));

        // when
        List<String> lines = StaticTestSelectionResolver.describeMatchedPoms(RULE,
                Collections.singletonList("pom.xml"), reports);

        // then
        assertEquals(Collections.singletonList("Static test selection rule 'pom-changes' POM pom.xml: "
                + "1 change(s) of its own: [plugin x version 1 -> 2 (BUILD_PLUGINS)]"), lines);
    }

    /**
     * POMs that inherit different changes from the same parent (each classified in its own
     * surroundings) get one parent line per distinct list, so nothing is merged that differs.
     */
    @Test
    void differentChangesFromTheSameParentGetALineEach() {
        // given
        List<PomChange> forA = Collections.singletonList(managedBump("org.lib:lib"));
        List<PomChange> forB = Arrays.asList(managedBump("org.lib:lib"), managedBump("org.lib:other"));
        Map<String, PomChangeReport> reports = new HashMap<>();
        reports.put("a/pom.xml", report(null, forA));
        reports.put("b/pom.xml", report(null, forB));

        // when
        List<String> lines = StaticTestSelectionResolver.describeMatchedPoms(RULE,
                Arrays.asList("a/pom.xml", "b/pom.xml"), reports);

        // then
        assertEquals(Arrays.asList(
                "Static test selection rule 'pom-changes' POM a/pom.xml: no changes of its own; 1 inherited from " + PARENT,
                "Static test selection rule 'pom-changes' POM b/pom.xml: no changes of its own; 2 inherited from " + PARENT,
                "Static test selection rule 'pom-changes' " + PARENT + ": 1 change(s), inherited by [a/pom.xml]: "
                        + inherited(forA),
                "Static test selection rule 'pom-changes' " + PARENT + ": 2 change(s), inherited by [b/pom.xml]: "
                        + inherited(forB)), lines);
    }

    /**
     * The INFO summary counts a parent change inherited by several matched POMs once, so its
     * {@code and N more} reflects the distinct changes.
     */
    @Test
    void summaryCountsSharedInheritedChangesOnce() {
        // given
        List<PomChange> fromParent = Arrays.asList(managedBump("org.lib:l1"), managedBump("org.lib:l2"),
                managedBump("org.lib:l3"), managedBump("org.lib:l4"), managedBump("org.lib:l5"));
        List<PomChange> firing = new ArrayList<>();
        firing.addAll(report(null, fromParent).getChanges());
        firing.addAll(report(null, fromParent).getChanges());
        firing.addAll(report(null, fromParent).getChanges());

        // when
        String summary = StaticTestSelectionResolver.summarize(firing);

        // then
        assertEquals(inherited(fromParent.subList(0, 3)) + " and 2 more", summary);
    }

    /**
     * @param coordinates the managed dependency's {@code groupId:artifactId}.
     * @return a managed dependency version bump, before attribution to the parent.
     */
    private static PomChange managedBump(final String coordinates) {
        return PomChange.functional(PomChangeCategory.DEPENDENCY_VERSIONS,
                "managed dependency " + coordinates + " version 1.0 -> 1.1");
    }

    /**
     * @param own the POM's own change, or {@code null} for none.
     * @param fromParent the changes the parent brings, before attribution.
     * @return a report of the own change followed by the parent's changes attributed to it.
     */
    private static PomChangeReport report(final PomChange own, final List<PomChange> fromParent) {
        List<PomChange> changes = new ArrayList<>();
        if (own != null) {
            changes.add(own);
        }
        for (PomChange change : fromParent) {
            changes.add(change.inheritedFrom(PARENT));
        }
        return new PomChangeReport(changes);
    }

    /**
     * @param fromParent the changes the parent brings, before attribution.
     * @return how the attributed changes print in a log line.
     */
    private static String inherited(final List<PomChange> fromParent) {
        List<PomChange> attributed = new ArrayList<>();
        for (PomChange change : fromParent) {
            attributed.add(change.inheritedFrom(PARENT));
        }
        return attributed.toString();
    }
}
