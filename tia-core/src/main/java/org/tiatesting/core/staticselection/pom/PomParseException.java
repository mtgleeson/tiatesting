package org.tiatesting.core.staticselection.pom;

/**
 * Thrown when POM text cannot be parsed. The classifier turns it into an
 * {@link PomChangeCategory#OTHER} change rather than letting it fail test selection.
 */
public class PomParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construct the exception.
     *
     * @param message the failure description.
     * @param cause the underlying parser failure.
     */
    public PomParseException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
