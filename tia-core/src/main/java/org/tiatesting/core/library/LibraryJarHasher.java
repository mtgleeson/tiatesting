package org.tiatesting.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Content-hashes library artifacts for the publish ledger and the drain lookup. The producer
 * hashes the jar it is publishing; the consumer hashes the jar it resolved and matches it against
 * the ledger to identify the exact published build (version strings are shared across SNAPSHOT
 * builds and identify nothing). See the ledger and drain sections of the library publish-time stamping chapter in {@code WIKI.md}.
 */
public final class LibraryJarHasher {

    private static final Logger log = LoggerFactory.getLogger(LibraryJarHasher.class);

    private LibraryJarHasher() {
    }

    /**
     * Compute the SHA-256 hash of a file's contents, returned as a lowercase hex string.
     *
     * @param file the file to hash.
     * @return the hex-encoded SHA-256 hash, or {@code null} when the file cannot be read.
     */
    public static String computeSha256Hash(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            byte[] hashBytes = digest.digest();
            StringBuilder hex = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            log.warn("Failed to compute SHA-256 hash for {}: {}", file.getAbsolutePath(), e.getMessage());
            return null;
        }
    }
}
