package org.tiatesting.core.library;

/**
 * Convention used by a tracked library project for incrementing its version number around releases.
 * Tia needs this signal because the library's build-file version is the only version it can observe
 * at stamp time, and the two policies give that number opposite meanings during development.
 *
 * <p>See {@code WIKI.md} ("Library versioning and the stamp/drain model") for the full model.
 */
public enum LibraryVersionPolicy {

    /**
     * Declared version stays at the <em>last released</em> version during dev; the bump happens
     * atomically with each release (e.g. released 1.0 → build file reads 1.0 → release 1.1 bumps
     * build file to 1.1). Stamps taken while the build-file version equals the last observed
     * released version describe changes destined for the next, unknown release, and are held by
     * the drainer until the library's build-file version advances.
     */
    BUMP_AT_RELEASE,

    /**
     * Declared version is the <em>next</em> version to be released; post-release the build file
     * is bumped to the next planned version (common Maven release plugin convention:
     * {@code 1.6.0-SNAPSHOT} → release {@code 1.6.0} → bump to {@code 1.7.0-SNAPSHOT}). Every
     * stamp is tagged with the version it will actually ship under; the drainer's "resolved ≥
     * stamped" rule is correct without additional holds. This is the default.
     */
    BUMP_AFTER_RELEASE
}
