package org.tiatesting.core.agent;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static java.lang.String.format;

public class AgentOptions {

    /**
     * Specifies the directory containing the source files for the program being analyzed.
     */
    public static final String SOURCEFILESDIR = "sourceFilesDir";

    /**
     * Specifies the directory containing the source files for the program being analyzed.
     */
    private static final String DEFAULT_SOURCEFILESDIR = "/src/main/java";

    private static final Pattern OPTION_SPLIT = Pattern.compile(",(?=[a-zA-Z0-9_\\-]+=)");

    private static final Collection<String> VALID_OPTIONS = Arrays.asList(SOURCEFILESDIR);

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

    public String getSourceFilesDir() {
        return getOption(SOURCEFILESDIR, DEFAULT_SOURCEFILESDIR);
    }

    public void setSourceFilesDir(String sourceFilesDir) {
        setOption(SOURCEFILESDIR, sourceFilesDir);
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
}
