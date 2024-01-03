package org.tiatesting.core.agent;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static java.lang.String.format;

public class AgentOptions {

    /**
     * Specifies the file path for the project being analyzed.
     */
    public static final String PROJECT_DIR = "projectDir";

    /**
     * Specifies the default file path for the project being analyzed.
     */
    private static final String DEFAULT_PROJECT_DIR = ".";

    /**
     * Specifies the file path for to the saved DB containing the previous analysis of the project.
     */
    public static final String DB_FILE_PATH = "dbFilePath";

    /**
     * Specifies the default file path for to the saved DB containing the previous analysis of the project.
     */
    private static final String DEFAULT_DB_FILE_PATH = ".";

    /**
     * Specifies the source files path for the project being analyzed.
     */
    public static final String SOURCE_FILES_DIRS = "sourceFilesDir";

    /**
     * Specifies the default source files path for the project being analyzed.
     */
    private static final String DEFAULT_SOURCE_FILES_DIRS = ".";

    /**
     * Specifies the test files path for the project being analyzed.
     */
    public static final String TEST_FILES_DIRS = "testFilesDir";

    /**
     * Specifies the default test files path for the project being analyzed.
     */
    private static final String DEFAULT_TEST_FILES_DIRS = ".";

    /**
     * Specifies whether the Tia database is being updated in the test run.
     */
    public static final String UPDATE_DB = "updateDB";

    /**
     * Specifies the default option for whether the Tia database is being updated in the test run.
     */
    private static final String DEFAULT_UPDATE_DB = "false";

    /**
     * Specifies whether Tia should analyse local unsubmitted (staged/shelved) changes when selecting tests.
     */
    public static final String CHECK_LOCAL_CHANGES = "checkLocalChanges";

    /**
     * Specifies the default option for whether Tia should analyse local unsubmitted (staged/shelved) changes when selecting tests.
     */
    private static final String DEFAULT_CHECK_LOCAL_CHANGES = "false";

    /**
     * Specifies the server URI of the VCS system.
     */
    public static final String VCS_SERVER_URI = "vcsServerUri";

    /**
     * Specifies the default server URI of the VCS system.
     */
    private static final String DEFAULT_VCS_SERVER_URI = "";

    /**
     * Specifies the username for connecting to the VCS system.
     */
    public static final String VCS_USER_NAME = "vcsUserName";

    /**
     * Specifies the default username for connecting to the VCS system.
     */
    private static final String DEFAULT_VCS_USER_NAME = "";

    /**
     * Specifies the password for connecting to the VCS system.
     */
    public static final String VCS_PASSWORD = "vcsPassword";

    /**
     * Specifies the default password for connecting to the VCS system.
     */
    private static final String DEFAULT_VCS_PASSWORD = "";

    /**
     * Specifies the client name used when connecting to the VCS system.
     */
    public static final String VCS_CLIENT_NAME = "vcsClientName";

    /**
     * Specifies the default client name used when connecting to the VCS system.
     */
    private static final String DEFAULT_VCS_CLIENT_NAME = "";

    private static final Collection<String> VALID_OPTIONS = Arrays.asList(PROJECT_DIR, DB_FILE_PATH, SOURCE_FILES_DIRS,
            TEST_FILES_DIRS, UPDATE_DB, CHECK_LOCAL_CHANGES, VCS_SERVER_URI, VCS_USER_NAME, VCS_PASSWORD, VCS_CLIENT_NAME);

    private static final Pattern OPTION_SPLIT = Pattern.compile(",(?=[a-zA-Z0-9_\\-]+=)");

    private final Map<String, String> options;

    /**
     * New instance with all values set to default.
     */
    public AgentOptions() {
        this.options = new HashMap<String, String>();
    }

    /**
     * New instance parsed from the given option string.
     *
     * @param optionstr
     *            string to parse or <code>null</code>
     */
    public AgentOptions(final String optionstr) {
        this();
        if (optionstr != null && optionstr.length() > 0) {
            for (final String entry : OPTION_SPLIT.split(optionstr)) {
                final int pos = entry.indexOf('=');
                if (pos == -1) {
                    throw new IllegalArgumentException(format(
                            "Invalid agent option syntax \"%s\".", optionstr));
                }
                final String key = entry.substring(0, pos);
                if (!VALID_OPTIONS.contains(key)) {
                    throw new IllegalArgumentException(
                            format("Unknown agent option \"%s\".", key));
                }

                final String value = entry.substring(pos + 1);
                setOption(key, value);
            }

            validateAll();
        }
    }

    private void validateAll() {
    }

    private void setOption(final String key, final String value) {
        options.put(key, value);
    }

    private String getOption(final String key, final String defaultValue) {
        final String value = options.get(key);
        return value == null ? defaultValue : value;
    }

    /**
     * Creates a string representation that can be passed to the agent via the
     * command line. Might be the empty string, if no options are set.
     */
    public String toCommandLineOptionsString() {
        final StringBuilder sb = new StringBuilder();
        for (final String key : VALID_OPTIONS) {
            final String value = options.get(key);
            if (value != null) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(key).append('=').append(value);
            }
        }
        return sb.toString();
    }

    public String getProjectDir() {
        return getOption(PROJECT_DIR, DEFAULT_PROJECT_DIR);
    }

    public void setProjectDir(String projectDir) {
        setOption(PROJECT_DIR, projectDir);
    }

    public String getDBFilePath() {
        return getOption(DB_FILE_PATH, DEFAULT_DB_FILE_PATH);
    }

    public void setDBFilePath(String dbFilePath) {
        setOption(DB_FILE_PATH, dbFilePath);
    }

    public String getSourceFilesDirs() {
        return getOption(SOURCE_FILES_DIRS, DEFAULT_SOURCE_FILES_DIRS);
    }

    public void setSourceFilesDirs(String sourceFilesDirs) {
        setOption(SOURCE_FILES_DIRS, sourceFilesDirs);
    }

    public String getTestFilesDirs() {
        return getOption(TEST_FILES_DIRS, DEFAULT_TEST_FILES_DIRS);
    }

    public void setTestFilesDirs(String testFilesDirs) {
        setOption(TEST_FILES_DIRS, testFilesDirs);
    }

    public String getUpdateDB() {
        return getOption(UPDATE_DB, DEFAULT_UPDATE_DB);
    }

    public void setUpdateDB(String updateDB) {
        setOption(UPDATE_DB, updateDB);
    }

    public String getCheckLocalChanges() {
        return getOption(CHECK_LOCAL_CHANGES, DEFAULT_CHECK_LOCAL_CHANGES);
    }

    public void setCheckLocalChanges(String checkLocalChanges) {
        setOption(CHECK_LOCAL_CHANGES, checkLocalChanges);
    }

    public String getVcsServerUri() {
        return getOption(VCS_SERVER_URI, DEFAULT_VCS_SERVER_URI);
    }

    public void setVcsServerUri(String vcsServerUri) {
        setOption(VCS_SERVER_URI, vcsServerUri);
    }

    public String getVcsUserName() {
        return getOption(VCS_USER_NAME, DEFAULT_VCS_USER_NAME);
    }

    public void setVcsUserName(String vcsUserName) {
        setOption(VCS_USER_NAME, vcsUserName);
    }

    public String getVcsPassword() {
        return getOption(VCS_PASSWORD, DEFAULT_VCS_PASSWORD);
    }

    public void setVcsPassword(String vcsPassword) {
        setOption(VCS_PASSWORD, vcsPassword);
    }

    public String getVcsClientName() {
        return getOption(VCS_CLIENT_NAME, DEFAULT_VCS_CLIENT_NAME);
    }

    public void setVcsClientName(String vcsClientName) {
        setOption(VCS_CLIENT_NAME, vcsClientName);
    }
}
