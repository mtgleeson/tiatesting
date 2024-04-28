package org.tiatesting.core.agent;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static java.lang.String.format;

public class AgentOptions {
    /**
     * Specifies the path for the file containing the list of tests to ignore.
     */
    public static final String IGNORE_TESTS_FILE = "ignoreTestsFile";

    /**
     * Specifies the default file path for the project being analyzed.
     */
    private static final String DEFAULT_IGNORE_TESTS_FILE = ".";

    /**
     * Specifies the path for the file containing the list of tests that were selected to run by Tia.
     */
    public static final String SELECTED_TESTS_FILE = "selectedTestsFile";

    /**
     * Specifies the default file path for the project being analyzed.
     */
    private static final String DEFAULT_SELECTED_TESTS_FILE = ".";

    private static final Collection<String> VALID_OPTIONS = Arrays.asList(IGNORE_TESTS_FILE, SELECTED_TESTS_FILE);

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

                String value = entry.substring(pos + 1);
                value = CommandLineSupport.unquote(value);
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
     *
     * @return the command line options as a string
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

    public String getIgnoreTestsFile() {
        return getOption(IGNORE_TESTS_FILE, DEFAULT_IGNORE_TESTS_FILE);
    }

    public void setIgnoreTestsFile(String ignoreTestsFile) {
        setOption(IGNORE_TESTS_FILE, ignoreTestsFile);
    }

    public String getSelectedTestsFile() {
        return getOption(SELECTED_TESTS_FILE, DEFAULT_SELECTED_TESTS_FILE);
    }

    public void setSelectedTestsFile(String selectedTestsFile) {
        setOption(SELECTED_TESTS_FILE, selectedTestsFile);
    }
}
