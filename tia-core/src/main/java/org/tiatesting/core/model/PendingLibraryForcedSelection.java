package org.tiatesting.core.model;

import org.tiatesting.core.staticselection.StaticTestSelectionRuleMode;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a forced test-selection intent recorded for a single published library build.
 * Produced at publish time when one of the library's own static test selection rules matches a
 * file changed since the library's previous publish, and drained by the consumer against the build
 * it resolves - exactly like {@link PendingLibraryImpactedMethod}, but carrying a rule mode plus
 * suite-name patterns rather than method ids. See the library publish-time stamping chapter in
 * {@code WIKI.md}.
 */
public class PendingLibraryForcedSelection implements Serializable {
    private static final long serialVersionUID = 1L;

    /** {@code groupId:artifactId} of the tracked library. */
    private String groupArtifact;

    /** The version the batch's publish shipped under - display only; the drain keys on {@link #publishSeq}. */
    private String stampVersion;

    /** The publish-ledger sequence of the build this forced selection shipped in. */
    private long publishSeq;

    /** The display name of the library static rule that produced this forced selection. */
    private String ruleName;

    /** The selection mode: {@code RUN_ALL} or {@code SUITE_NAMES}. */
    private StaticTestSelectionRuleMode mode;

    /** The rule's suite-name regex patterns; empty for {@code RUN_ALL}. */
    private List<String> suiteNamePatterns;

    /**
     * Construct an empty forced-selection batch with an empty pattern list, for frameworks that
     * populate fields via setters (e.g. deserialization).
     */
    public PendingLibraryForcedSelection() {
        this.suiteNamePatterns = new ArrayList<>();
    }

    /**
     * Construct a fully populated forced-selection batch.
     *
     * @param groupArtifact {@code groupId:artifactId} of the tracked library.
     * @param stampVersion the version the batch's publish shipped under (display only).
     * @param publishSeq the publish-ledger sequence of the build this forced selection shipped in.
     * @param ruleName the display name of the matching library static rule.
     * @param mode the selection mode.
     * @param suiteNamePatterns the suite-name regex patterns; {@code null} is treated as empty.
     */
    public PendingLibraryForcedSelection(String groupArtifact, String stampVersion, long publishSeq,
                                         String ruleName, StaticTestSelectionRuleMode mode,
                                         List<String> suiteNamePatterns) {
        this.groupArtifact = groupArtifact;
        this.stampVersion = stampVersion;
        this.publishSeq = publishSeq;
        this.ruleName = ruleName;
        this.mode = mode;
        this.suiteNamePatterns = suiteNamePatterns != null ? new ArrayList<>(suiteNamePatterns) : new ArrayList<>();
    }

    /**
     * Return the tracked library's coordinate.
     *
     * @return the {@code groupId:artifactId} of the tracked library.
     */
    public String getGroupArtifact() {
        return groupArtifact;
    }

    /**
     * Set the tracked library's coordinate.
     *
     * @param groupArtifact the {@code groupId:artifactId} of the tracked library.
     */
    public void setGroupArtifact(String groupArtifact) {
        this.groupArtifact = groupArtifact;
    }

    /**
     * Return the display-only version the batch's publish shipped under.
     *
     * @return the stamp version string.
     */
    public String getStampVersion() {
        return stampVersion;
    }

    /**
     * Set the display-only version the batch's publish shipped under.
     *
     * @param stampVersion the stamp version string.
     */
    public void setStampVersion(String stampVersion) {
        this.stampVersion = stampVersion;
    }

    /**
     * Return the publish-ledger sequence of the build this forced selection shipped in.
     *
     * @return the publish sequence number.
     */
    public long getPublishSeq() {
        return publishSeq;
    }

    /**
     * Set the publish-ledger sequence of the build this forced selection shipped in.
     *
     * @param publishSeq the publish sequence number.
     */
    public void setPublishSeq(long publishSeq) {
        this.publishSeq = publishSeq;
    }

    /**
     * Return the display name of the library static rule that produced this forced selection.
     *
     * @return the rule name.
     */
    public String getRuleName() {
        return ruleName;
    }

    /**
     * Set the display name of the library static rule that produced this forced selection.
     *
     * @param ruleName the rule name.
     */
    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    /**
     * Return the selection mode this forced selection was produced under.
     *
     * @return the selection mode.
     */
    public StaticTestSelectionRuleMode getMode() {
        return mode;
    }

    /**
     * Set the selection mode this forced selection was produced under.
     *
     * @param mode the selection mode.
     */
    public void setMode(StaticTestSelectionRuleMode mode) {
        this.mode = mode;
    }

    /**
     * Return the rule's suite-name regex patterns.
     *
     * @return the suite-name patterns; empty (never {@code null}) for {@code RUN_ALL}.
     */
    public List<String> getSuiteNamePatterns() {
        return suiteNamePatterns;
    }

    /**
     * Set the rule's suite-name regex patterns.
     *
     * @param suiteNamePatterns the suite-name patterns; {@code null} is treated as empty.
     */
    public void setSuiteNamePatterns(List<String> suiteNamePatterns) {
        this.suiteNamePatterns = suiteNamePatterns != null ? new ArrayList<>(suiteNamePatterns) : new ArrayList<>();
    }

    /**
     * Compare for equality on the batch's identity key: {@code (groupArtifact, publishSeq, ruleName)}.
     *
     * @param o the object to compare against.
     * @return {@code true} if {@code o} is a {@code PendingLibraryForcedSelection} with the same
     *         {@code groupArtifact}, {@code publishSeq}, and {@code ruleName}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PendingLibraryForcedSelection that = (PendingLibraryForcedSelection) o;
        return publishSeq == that.publishSeq
                && Objects.equals(groupArtifact, that.groupArtifact)
                && Objects.equals(ruleName, that.ruleName);
    }

    /**
     * Compute a hash code consistent with {@link #equals(Object)}'s identity key.
     *
     * @return the hash code derived from {@code (groupArtifact, publishSeq, ruleName)}.
     */
    @Override
    public int hashCode() {
        return Objects.hash(groupArtifact, publishSeq, ruleName);
    }

    /**
     * Render a diagnostic summary of this forced selection batch.
     *
     * @return a string containing the group artifact, publish sequence, rule name, mode, and patterns.
     */
    @Override
    public String toString() {
        return "PendingLibraryForcedSelection{groupArtifact='" + groupArtifact
                + "', publishSeq=" + publishSeq
                + ", ruleName='" + ruleName
                + "', mode=" + mode
                + ", patterns=" + suiteNamePatterns + "}";
    }
}
