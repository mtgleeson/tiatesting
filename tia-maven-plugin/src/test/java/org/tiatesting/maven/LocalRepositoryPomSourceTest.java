package org.tiatesting.maven;

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link LocalRepositoryPomSource}: the standard local repository layout and missing POMs.
 */
class LocalRepositoryPomSourceTest {

    /**
     * A POM in the standard layout is read as UTF-8.
     *
     * @param repository a temporary local repository.
     * @throws Exception if the fixture can't be written.
     */
    @Test
    void readsAPomFromTheStandardLayout(@TempDir File repository) throws Exception {
        // given
        File pom = new File(repository, "org/springframework/boot/spring-boot-starter-parent/3.2.0/"
                + "spring-boot-starter-parent-3.2.0.pom");
        pom.getParentFile().mkdirs();
        Files.write(pom.toPath(), "<project>é</project>".getBytes(StandardCharsets.UTF_8));
        LocalRepositoryPomSource source = new LocalRepositoryPomSource(repository, new SystemStreamLog());

        // when
        String content = source.readPom("org.springframework.boot", "spring-boot-starter-parent", "3.2.0");

        // then
        assertEquals("<project>é</project>", content);
    }

    /**
     * A POM not in the local repository is reported as missing, not downloaded.
     *
     * @param repository a temporary, empty local repository.
     */
    @Test
    void missingPomIsNull(@TempDir File repository) {
        // given
        LocalRepositoryPomSource source = new LocalRepositoryPomSource(repository, new SystemStreamLog());

        // when
        String content = source.readPom("org.platform", "bom", "9.9");

        // then
        assertNull(content);
    }
}
