package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.persistence.connection.ConnectionProvider;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies {@link DataStoreFactory#fromConfig} resolves credentials the same way for every dialect.
 *
 * <p>The environment fallback used to live inside {@code H2ConnectionSettings}, which
 * {@code fromConfig} reached only in its H2 branch, so a Postgres or generic-JDBC build silently
 * ignored {@value CredentialResolver#ENV_DB_USER} / {@value CredentialResolver#ENV_DB_PASSWORD} and
 * was forced to put the password in checked-in build config. These tests pin the behaviour that
 * replaced it. No connection is opened, so they run without a database.
 */
class DataStoreFactoryCredentialsTest {

    private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/tiadb";

    /**
     * Read a private field from a connection provider. The providers deliberately expose no
     * username or password accessor - keeping credentials off their public surface is part of what
     * stops them being logged - so a test that needs to prove what was handed to one reaches in
     * rather than widening that surface.
     *
     * @param provider the connection provider to inspect
     * @param name     the declared field name to read
     * @return the field's value on {@code provider}
     * @throws Exception if the field does not exist or cannot be read
     */
    private static Object field(final ConnectionProvider provider, final String name) throws Exception {
        Class<?> type = provider.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(provider);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /**
     * Extract the connection provider the factory built for these settings.
     *
     * @param store the datastore returned by the factory
     * @return the datastore's connection provider
     */
    private static ConnectionProvider providerOf(final DataStore store) {
        return ((JdbcDataStore) store).getConnectionProvider();
    }

    @Test
    void postgresFallsBackToTheEnvironmentWhenNoCredentialsAreConfigured() throws Exception {
        // given
        Map<String, String> env = new HashMap<>();
        env.put(CredentialResolver.ENV_DB_USER, "envuser");
        env.put(CredentialResolver.ENV_DB_PASSWORD, "envsecret");

        // when
        DataStore store = DataStoreFactory.fromConfig(null, POSTGRES_URL, null, null, null,
                "main", null, env::get);

        // then
        assertEquals("envuser", field(providerOf(store), "user"));
        assertEquals("envsecret", field(providerOf(store), "password"));
    }

    @Test
    void postgresPrefersConfiguredCredentialsOverTheEnvironment() throws Exception {
        // given
        Map<String, String> env = new HashMap<>();
        env.put(CredentialResolver.ENV_DB_USER, "envuser");
        env.put(CredentialResolver.ENV_DB_PASSWORD, "envsecret");

        // when
        DataStore store = DataStoreFactory.fromConfig(null, POSTGRES_URL, "configured", "configsecret",
                null, "main", null, env::get);

        // then
        assertEquals("configured", field(providerOf(store), "user"));
        assertEquals("configsecret", field(providerOf(store), "password"));
    }

    /**
     * The {@code tia} username default is H2's own convention. Applying it to another vendor would
     * silently attempt a login as a user that vendor was never told about, so a non-H2 dialect with
     * nothing configured gets no username at all.
     */
    @Test
    void nonH2DialectsDoNotInheritTheH2UsernameDefault() throws Exception {
        // given
        Map<String, String> env = new HashMap<>();

        // when
        DataStore store = DataStoreFactory.fromConfig(null, POSTGRES_URL, null, null, null,
                "main", null, env::get);

        // then
        assertNull(field(providerOf(store), "user"));
        assertEquals("", field(providerOf(store), "password"));
    }

    @Test
    void h2StillDefaultsItsUsernameToTia() {
        // given
        Map<String, String> env = new HashMap<>();
        String url = "jdbc:h2:tcp://h2host:9092/tiadb";

        // when
        DataStore store = DataStoreFactory.fromConfig(null, url, null, null, null, "main", null,
                env::get);

        // then
        H2ConnectionSettings settings = H2ConnectionSettings.fromConfig(null, url, null, null);
        assertEquals("tia", settings.getUsername());
        assertEquals(url, ((JdbcDataStore) store).getConnectionProvider().jdbcUrl());
    }

    @Test
    void h2FallsBackToTheEnvironmentForItsCredentials() throws Exception {
        // given
        Map<String, String> env = new HashMap<>();
        env.put(CredentialResolver.ENV_DB_USER, "envuser");
        env.put(CredentialResolver.ENV_DB_PASSWORD, "envsecret");

        // when
        DataStore store = DataStoreFactory.fromConfig(null, "jdbc:h2:tcp://h2host:9092/tiadb",
                null, null, null, "main", null, env::get);

        // then
        H2ConnectionSettings settings = (H2ConnectionSettings) field(providerOf(store), "settings");
        assertEquals("envuser", settings.getUsername());
        assertEquals("envsecret", settings.getPassword());
    }
}
