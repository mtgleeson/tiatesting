package org.tiatesting.vcs.perforce.connection;

import org.tiatesting.core.vcs.VCSAnalyzerException;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class P4Settings
{
    private static final String P4_SET_VAR_PREFIX = "P4";
    /**
     * The trailing "where this value came from" annotation {@code p4 set} appends, e.g.
     * {@code " (set)"}, {@code " (env)"} or {@code " (config \"/home/me/.p4config\")"}. The
     * wording varies by OS and by how the value was set, so every trailing parenthesised group is
     * stripped rather than one literal suffix - see {@link #parseP4SetOutput}.
     */
    private static final Pattern P4_SET_VAR_SOURCE_ANNOTATION =
            Pattern.compile("(?:\\s+\\([^()]*\\))+$");
    private static final String P4_SET_VAR_NO_VALUE = "none";
    private static final String P4_SET_VAR_SEPARATOR = "=";
    private static final String P4_VARIABLE_NOT_SET = "%s variable is not set. Disabling feature.";
    private static final List<String> requiredSettingsFromP4set = Arrays.asList(P4Constants.P4CLIENT, P4Constants.P4PORT, P4Constants.P4USER);

    // For the sake of password encryption/decryption,
    // we will need to store an additional setting that is not a P4 one per se
    public static final String P4_SET_RANDOMKEY_CUSTOM_VAR = P4_SET_VAR_PREFIX + "K";
    public static final String LOCAL_P4_SETTINGS_FILE = "local_p4.txt";

    private static P4Settings INSTANCE;

    private Map<String, String> settingsMap = new HashMap<>();

    // Private constructor will prevent the instantiation of this class directly
    private P4Settings() {}

    /**
     * Returns the singleton instance of P4Settings.
     *
     * @return {@link P4Settings}
     */
    public static P4Settings getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new P4Settings();
        }
        return INSTANCE;
    }

    public Map<String, String> getP4SettingsMap()
    {
        return settingsMap;
    }

    /**
     * Read P4 set vars from a file and store all of them in a map.
     *
     * @param file the file to retrieve saved P4 settings from
     * @throws Exception an Exception
     */
    public void setP4SettingsMapFromFile(File file) throws Exception {
        if (!file.exists()) {
            return;
        }

        Scanner scanner;

        try {
            scanner = new Scanner(file);
        } catch (FileNotFoundException ignored) {
            return;
        }

        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();

            int equalsIndex = line.indexOf(P4_SET_VAR_SEPARATOR);
            addSettingToMap(line.substring(0, equalsIndex), line.substring(equalsIndex + 1));
        }

        scanner.close();

        for (String setting : requiredSettingsFromP4set) {
            if (!settingsMap.containsKey(setting))
            {
                throw new Exception(String.format(P4_VARIABLE_NOT_SET, setting));
            }
        }
    }

    /**
     * Add a specific P4 setting to the map.
     *
     * @param key the P4 settings key
     * @param value the P4 settings value
     * @throws Exception an Exception
     */
    public void addSettingToMap(String key, String value) throws Exception {
        if (key.startsWith(P4_SET_VAR_PREFIX)) {
            if (value.equals(P4_SET_VAR_NO_VALUE))
            {
                throw new Exception(String.format(P4_VARIABLE_NOT_SET, key));
            }
            settingsMap.put(key, value);
        }
    }

    /**
     * From the given {@link String} filePath, create a new text file. Call 'p4 set' command and store all vars there.
     *
     * @param filePath the path to the file used to store the P4 settings
     * @return {@link File} the created file used to store the P4 settings
     * @throws Exception an Exception
     */
    public File createLocalP4SettingsFile(String filePath) throws Exception {
        File file = new File(filePath);
        file.createNewFile();

        Map<String, String> p4vars = executeP4SetCommand();
        // TODO create a String from the map that resembles the p4 set command.
        // i.e. join each key with the value and then plan each map entry on a new line
        //try (PrintWriter out = new PrintWriter(file.getName()))
        //{
        //    out.println(p4vars);
        //}

        return file;
    }

    /**
     * Executes the P4 set command and returns the configured Perforce settings.
     *
     * <p>The command is run directly rather than through a shell, so it behaves the same on every
     * OS. The previous {@code cmd.exe /c} form could only ever work on Windows.
     *
     * <p>Reading and parsing are kept apart: this method owns the process and hands the raw lines
     * to {@link #parseP4SetOutput}, which is where every format question lives and which is
     * unit-tested against captured real output from both platforms without needing {@code p4}
     * installed.
     *
     * @return the configured P4 settings, keyed by variable name; empty when {@code p4 set}
     *         reported none of the settings Tia looks for
     * @throws VCSAnalyzerException if the {@code p4} executable cannot be run, or the read is
     *                              interrupted
     */
    public static Map<String, String> executeP4SetCommand() {
        try {
            final ProcessBuilder builder =
                    new ProcessBuilder(P4Constants.P4_EXECUTABLE, P4Constants.P4_SET_ARG);
            builder.redirectErrorStream(true);

            final Process process = builder.start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader bufReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = bufReader.readLine()) != null) {
                    lines.add(line);
                }
            } finally {
                // The output stream is at EOF, so the process has finished or is about to. Reap it
                // rather than leaving it for the GC: a build that analyses many changelists would
                // otherwise accumulate zombie processes.
                waitForQuietly(process);
            }

            return parseP4SetOutput(lines);
        } catch (IOException e) {
            throw new VCSAnalyzerException("Failed to execute command: " + P4Constants.P4_SET
                    + ". Check that the Perforce command line client ('" + P4Constants.P4_EXECUTABLE
                    + "') is installed and on the PATH of the process running the build.", e);
        }
    }

    /**
     * Wait briefly for a finished process to be reaped, without letting a wedged {@code p4} hang
     * the build. The output stream is already at EOF by the time this is called, so the wait is
     * expected to return immediately; the timeout only bounds the pathological case.
     *
     * @param process the process to reap
     */
    private static void waitForQuietly(final Process process) {
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
        }
    }

    /**
     * Parse the output of {@code p4 set} into the settings Tia needs.
     *
     * <p><b>The source annotation is what makes this OS-dependent, and why it is stripped
     * generically.</b> {@code p4 set} reports where each value came from, and the wording differs
     * by platform and by how the value was set:
     *
     * <pre>
     *   P4PORT=ssl:host:1666 (set)                        Windows, from the registry
     *   P4PORT=ssl:host:1666 (env)                        any OS, from the environment
     *   P4CLIENT=my-ws (config "/home/me/.p4config")      any OS, from a P4CONFIG file
     * </pre>
     *
     * Matching only the literal {@code " (set)"} left the annotation on the value everywhere except
     * Windows - so a URI would come back as {@code ssl:host:1666 (config)} and be concatenated
     * straight into the connection string. Every trailing parenthesised group is therefore removed,
     * which covers the wordings above and any future one.
     *
     * <p><b>Names are matched as a whole assignment</b> ({@code NAME=}), not as a substring. A
     * substring match means the {@code P4CLIENTPATH} line also matches {@code P4CLIENT}, and since
     * {@code p4 set} output is alphabetical it lands after the real value and overwrites it with
     * the wrong one.
     *
     * <p>Lines that are not assignments - blank lines, and the warning text {@code p4} prints when
     * it has nothing to report - are skipped rather than parsed.
     *
     * @param lines the raw lines of {@code p4 set} output, in order
     * @return the settings Tia looks for that were present in the output, keyed by variable name
     */
    static Map<String, String> parseP4SetOutput(final List<String> lines) {
        Map<String, String> p4SetArgs = new HashMap<>();

        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String trimmedLine = line.trim();

            for (String p4Setting : requiredSettingsFromP4set) {
                if (extractP4Setting(p4Setting, trimmedLine, p4SetArgs)) {
                    break;
                }
            }
        }

        return p4SetArgs;
    }

    /**
     * Extract one named setting from a line of {@code p4 set} output, if that line is the
     * assignment for it.
     *
     * @param p4Setting the variable name to look for
     * @param line the trimmed line of output
     * @param p4SetArgs the map to add the value to when the line matches
     * @return {@code true} when the line was the assignment for {@code p4Setting}, so the caller
     *         can stop testing this line against other names
     */
    private static boolean extractP4Setting(String p4Setting, String line, Map<String, String> p4SetArgs) {
        String assignmentPrefix = p4Setting + P4_SET_VAR_SEPARATOR;
        if (!line.startsWith(assignmentPrefix)) {
            return false;
        }

        String value = line.substring(assignmentPrefix.length());
        value = P4_SET_VAR_SOURCE_ANNOTATION.matcher(value).replaceAll("").trim();
        p4SetArgs.put(p4Setting, value);
        return true;
    }
}
