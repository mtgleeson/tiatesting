package org.tiatesting.vcs.perforce;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.vcs.VCSReaderFactory;
import org.tiatesting.core.vcs.VcsSettings;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link P4ReaderProvider} is registered for {@code ServiceLoader} and builds a
 * {@link P4Reader}. Tia is disabled in the settings so no Perforce server connection is attempted.
 */
class P4ReaderProviderTest {

    @Test
    void factoryFindsPerforceProviderWithoutConnectingWhenDisabled() {
        // given
        VcsSettings settings = VcsSettings.builder().projectDir(".").enabled(false)
                .serverUri("p4java://localhost:1666").userName("user").clientName("client").build();

        // when
        Optional<VCSReader> reader = VCSReaderFactory.create(settings, getClass().getClassLoader());

        // then
        assertEquals(P4Reader.class, reader.get().getClass());
    }

    @Test
    void nameIsPerforce() {
        // given
        P4ReaderProvider provider = new P4ReaderProvider();

        // when
        String name = provider.name();

        // then
        assertEquals("perforce", name);
    }
}
