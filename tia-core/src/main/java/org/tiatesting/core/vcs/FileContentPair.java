package org.tiatesting.core.vcs;

import java.util.Objects;

/**
 * The "before" and "after" content of a single file, as returned by
 * {@link VCSReader#loadFileContents}. The "before" side is the file at the stored mapping commit
 * (or at the workspace head, for local changes); the "after" side is the file at head (or in the
 * working copy, for local changes). Either side is {@code null} when the file does not exist on
 * that side - e.g. an added file has no old content and a deleted file has no new content.
 */
public class FileContentPair {

    private final String oldContent;
    private final String newContent;

    /**
     * Construct a content pair.
     *
     * @param oldContent the file's content on the "before" side, or {@code null} if it did not exist.
     * @param newContent the file's content on the "after" side, or {@code null} if it does not exist.
     */
    public FileContentPair(final String oldContent, final String newContent) {
        this.oldContent = oldContent;
        this.newContent = newContent;
    }

    /**
     * @return the file's content on the "before" side, or {@code null} if it did not exist there.
     */
    public String getOldContent() {
        return oldContent;
    }

    /**
     * @return the file's content on the "after" side, or {@code null} if it does not exist there.
     */
    public String getNewContent() {
        return newContent;
    }

    /**
     * Compare two pairs by both sides' content.
     *
     * @param o the object to compare with.
     * @return {@code true} when {@code o} is a pair with equal old and new content.
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        FileContentPair that = (FileContentPair) o;
        return Objects.equals(oldContent, that.oldContent) && Objects.equals(newContent, that.newContent);
    }

    /**
     * Hash both sides' content, consistent with {@link #equals(Object)}.
     *
     * @return the hash code.
     */
    @Override
    public int hashCode() {
        return Objects.hash(oldContent, newContent);
    }

    /**
     * Describe the pair by the length of each side, not the content itself, so log lines stay short.
     *
     * @return a short description of the pair.
     */
    @Override
    public String toString() {
        return "FileContentPair{oldLength=" + (oldContent == null ? "absent" : oldContent.length())
                + ", newLength=" + (newContent == null ? "absent" : newContent.length()) + "}";
    }
}
