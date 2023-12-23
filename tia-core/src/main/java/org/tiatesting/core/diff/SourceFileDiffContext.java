package org.tiatesting.core.diff;

import java.util.Objects;

public class SourceFileDiffContext {

    /**
     * The old file path including the filename of the source file impacted by a commit in the range analyzed.
     */
    String oldFilePath;

    /**
     * The new file path including the filename of the source file impacted by a commit in the range analyzed.
     * This will only be different to the oldFilePath if the file was renamed.
     */
    String newFilePath;

    /**
     * The VCS change type operation on the impacted source code file.
     */
    ChangeType changeType;

    /**
     * The file content for a source file impacted by a commit in the range analyzed.
     * The file content is based on the 'from' version of the commit range.
     */
    String sourceContentOriginal;

    /**
     * The file content for a source file impacted by a commit in the range analyzed.
     * The file content is based on the 'to' version of the commit range.
     */
    String sourceContentNew;

    public SourceFileDiffContext(String oldFilePath, String newFilePath, ChangeType changeType) {
        this.oldFilePath = oldFilePath;
        this.newFilePath = newFilePath;
        this.changeType = changeType;
    }

    public String getOldFilePath() {
        return oldFilePath;
    }

    public String getNewFilePath() {
        return newFilePath;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public void setChangeType(ChangeType changeType) {
        this.changeType = changeType;
    }

    public String getSourceContentOriginal() {
        return sourceContentOriginal;
    }

    public void setSourceContentOriginal(String sourceContentOriginal) {
        this.sourceContentOriginal = sourceContentOriginal;
    }

    public String getSourceContentNew() {
        return sourceContentNew;
    }

    public void setSourceContentNew(String sourceContentNew) {
        this.sourceContentNew = sourceContentNew;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SourceFileDiffContext that = (SourceFileDiffContext) o;
        return oldFilePath.equals(that.oldFilePath)
                && newFilePath.equals(that.newFilePath)
                && changeType == that.changeType
                && Objects.equals(sourceContentOriginal, that.sourceContentOriginal)
                && Objects.equals(sourceContentNew, that.sourceContentNew);
    }

    @Override
    public int hashCode() {
        return Objects.hash(oldFilePath, newFilePath, changeType, sourceContentOriginal, sourceContentNew);
    }
}
