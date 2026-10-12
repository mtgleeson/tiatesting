package org.tiatesting.core.staticselection.pom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The result of classifying the difference between two versions of one POM: every change found,
 * functional and inert, in the order the classifier found them.
 */
public final class PomChangeReport {

    private final List<PomChange> changes;

    /**
     * Construct a report.
     *
     * @param changes the classified changes; copied.
     */
    public PomChangeReport(final List<PomChange> changes) {
        this.changes = Collections.unmodifiableList(new ArrayList<>(changes));
    }

    /**
     * @return every classified change, functional and inert; never {@code null}.
     */
    public List<PomChange> getChanges() {
        return changes;
    }

    /**
     * @return the categories of the functional changes; empty when every change was inert.
     */
    public Set<PomChangeCategory> getCategories() {
        Set<PomChangeCategory> categories = EnumSet.noneOf(PomChangeCategory.class);
        for (PomChange change : changes) {
            if (!change.isInert()) {
                categories.add(change.getCategory());
            }
        }
        return categories;
    }

    /**
     * Check whether a rule configured with any of the given categories fires for this report.
     *
     * @param ruleCategories the categories a rule is configured with.
     * @return {@code true} when at least one functional change is covered by one of the categories.
     */
    public boolean firesFor(final Set<PomChangeCategory> ruleCategories) {
        for (PomChange change : changes) {
            if (change.isInert()) {
                continue;
            }
            for (PomChangeCategory ruleCategory : ruleCategories) {
                if (ruleCategory.covers(change.getCategory())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Describe the report as its list of changes.
     *
     * @return the changes, each with its category.
     */
    @Override
    public String toString() {
        return changes.toString();
    }
}
