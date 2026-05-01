package org.tiatesting.core.report.html;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

/**
 * Extracts the report's static assets (CSS, JS, images) from the {@code tia-core} JAR
 * into the report output directory at {@code <reportOutputDir>/html/<branch>/assets/}.
 * Pages reference these via relative paths so reports work offline / in CI artefact archives.
 */
class HtmlAssetCopier {

    private static final Logger log = LoggerFactory.getLogger(HtmlAssetCopier.class);

    /** Root inside the {@code tia-core} JAR where bundled assets live. */
    private static final String CLASSPATH_ROOT = "/report/assets/";

    /** Output sub-directory under the per-branch report dir. Pages use this in {@code href}/{@code src}. */
    static final String ASSETS_DIR_NAME = "assets";

    /** Hardcoded asset list. Update if files are added/removed under {@code resources/report/assets/}. */
    private static final List<String> ASSET_PATHS = Arrays.asList(
            "css/pico.classless.min.css",
            "css/simple-datatables.css",
            "css/tia.css",
            "js/simple-datatables.min.js",
            "images/tia_logo.png",
            "images/tia_icon.png",
            "images/tia_favicon.ico"
    );

    private HtmlAssetCopier() {}

    /**
     * Copy every bundled asset to {@code <branchReportDir>/assets/...}, preserving subpaths.
     * Idempotent: overwrites on each run so an updated TIA jar refreshes the assets next time.
     */
    static void copyAssetsTo(File branchReportDir) {
        File assetsRoot = new File(branchReportDir, ASSETS_DIR_NAME);
        if (!assetsRoot.exists() && !assetsRoot.mkdirs()) {
            throw new RuntimeException("Failed to create report assets dir: " + assetsRoot.getAbsolutePath());
        }

        for (String relPath : ASSET_PATHS) {
            copyOne(relPath, new File(assetsRoot, relPath));
        }
    }

    private static void copyOne(String relPath, File target) {
        String classpathPath = CLASSPATH_ROOT + relPath;
        try (InputStream in = HtmlAssetCopier.class.getResourceAsStream(classpathPath)) {
            if (in == null) {
                log.warn("Bundled report asset not found on classpath: {}", classpathPath);
                return;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Failed to create asset parent dir: " + parent.getAbsolutePath());
            }
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy report asset " + classpathPath
                    + " to " + target.getAbsolutePath(), e);
        }
    }
}
