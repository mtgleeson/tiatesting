package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link CredentialResolver}, covering the precedence between a configured value,
 * the environment fallback and the default, and the null-vs-empty rule that lets a build pin an
 * explicitly empty password.
 */
class CredentialResolverTest {

    /**
     * Build an environment lookup holding the given database password.
     *
     * @param password the value {@value CredentialResolver#ENV_DB_PASSWORD} should return
     * @return an environment map to be used as a lookup via {@code ::get}
     */
    private static Map<String, String> envWithPassword(final String password) {
        Map<String, String> env = new HashMap<>();
        env.put(CredentialResolver.ENV_DB_PASSWORD, password);
        return env;
    }

    @Test
    void resolvePasswordPrefersTheConfiguredValueOverTheEnvironment() {
        // given
        Map<String, String> env = envWithPassword("envsecret");

        // when
        String resolved = CredentialResolver.resolvePassword("configured", env::get);

        // then
        assertEquals("configured", resolved);
    }

    /**
     * An explicitly configured empty password must be honoured verbatim rather than falling back to
     * the environment, which is what lets a build pin an empty password and bypass
     * {@value CredentialResolver#ENV_DB_PASSWORD}.
     */
    @Test
    void resolvePasswordPrefersExplicitEmptyOverTheEnvironment() {
        // given
        Map<String, String> env = envWithPassword("envsecret");

        // when
        String resolved = CredentialResolver.resolvePassword("", env::get);

        // then
        assertEquals("", resolved);
    }

    @Test
    void resolvePasswordFallsBackToTheEnvironmentWhenNotConfigured() {
        // given
        Map<String, String> env = envWithPassword("envsecret");

        // when
        String resolved = CredentialResolver.resolvePassword(null, env::get);

        // then
        assertEquals("envsecret", resolved);
    }

    @Test
    void resolvePasswordGivesAnEmptyPasswordWhenNeitherIsSet() {
        // given
        Map<String, String> env = new HashMap<>();

        // when
        String resolved = CredentialResolver.resolvePassword(null, env::get);

        // then
        assertEquals("", resolved);
    }

    @Test
    void resolvePasswordTreatsABlankEnvironmentValueAsUnset() {
        // given
        Map<String, String> env = envWithPassword("   ");

        // when
        String resolved = CredentialResolver.resolvePassword(null, env::get);

        // then
        assertEquals("", resolved);
    }

    /**
     * Leading and trailing whitespace can be significant in a password, so a configured value is
     * never trimmed - unlike the username, where blank means "not configured".
     */
    @Test
    void resolvePasswordNeverTrimsAConfiguredValue() {
        // given
        Map<String, String> env = envWithPassword("envsecret");

        // when
        String resolved = CredentialResolver.resolvePassword("  ", env::get);

        // then
        assertEquals("  ", resolved);
    }

    @Test
    void resolveUserPrefersTheConfiguredValueOverTheEnvironment() {
        // given
        Map<String, String> env = new HashMap<>();
        env.put(CredentialResolver.ENV_DB_USER, "envuser");

        // when
        String resolved = CredentialResolver.resolveUser("configured", "tia", env::get);

        // then
        assertEquals("configured", resolved);
    }

    /**
     * Unlike the password, a blank username is meaningless to every supported database, so it
     * counts as not configured and falls through to the environment.
     */
    @Test
    void resolveUserTreatsABlankConfiguredValueAsUnset() {
        // given
        Map<String, String> env = new HashMap<>();
        env.put(CredentialResolver.ENV_DB_USER, "envuser");

        // when
        String resolved = CredentialResolver.resolveUser("  ", "tia", env::get);

        // then
        assertEquals("envuser", resolved);
    }

    @Test
    void resolveUserFallsBackToTheDefaultWhenNeitherIsSet() {
        // given
        Map<String, String> env = new HashMap<>();

        // when
        String resolved = CredentialResolver.resolveUser(null, "tia", env::get);

        // then
        assertEquals("tia", resolved);
    }

    /**
     * The non-H2 case. A dialect with no username convention of its own passes a null default, so
     * Tia leaves the username unset rather than inventing one the vendor never agreed to.
     */
    @Test
    void resolveUserReturnsNullWhenThereIsNoDefault() {
        // given
        Map<String, String> env = new HashMap<>();

        // when
        String resolved = CredentialResolver.resolveUser(null, null, env::get);

        // then
        assertNull(resolved);
    }
}
