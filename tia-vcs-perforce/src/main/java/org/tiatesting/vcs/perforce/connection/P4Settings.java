package org.tiatesting.vcs.perforce.connection;

import org.tiatesting.core.vcs.VCSAnalyzerException;

import java.io.*;
import java.util.*;

public class P4Settings
{
    private static final String P4_SET_VAR_PREFIX = "P4";
    private static final String P4_SET_VAR_UNWANTED_SUFFIX = " (set)";
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
    public static P4Settings getInstance()
    {
        if (INSTANCE == null)
        {
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
     * @param file
     * @throws Exception
     */
    public void setP4SettingsMapFromFile(File file) throws Exception
    {
        if (!file.exists())
        {
            return;
        }

        Scanner scanner;

        try
        {
            scanner = new Scanner(file);
        }
        catch (FileNotFoundException ignored)
        {
            return;
        }

        while (scanner.hasNextLine())
        {
            String line = scanner.nextLine();

            int equalsIndex = line.indexOf(P4_SET_VAR_SEPARATOR);
            addSettingToMap(line.substring(0, equalsIndex), line.substring(equalsIndex + 1));
        }

        scanner.close();

        for (String setting : requiredSettingsFromP4set)
        {
            if (!settingsMap.containsKey(setting))
            {
                throw new Exception(String.format(P4_VARIABLE_NOT_SET, setting));
            }
        }
    }

    /**
     * Add a specific P4 setting to the map.
     *
     * @param key
     * @param value
     */
    public void addSettingToMap(String key, String value) throws Exception
    {
        if (key.startsWith(P4_SET_VAR_PREFIX))
        {
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
     * @param filePath
     * @return {@link File}
     * @throws Exception
     */
    public File createLocalP4SettingsFile(String filePath) throws Exception
    {
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
     * Executes a command prompt p4 set command from java and returns the output.
     *
     * @return
     */
    public static Map<String, String> executeP4SetCommand()
    {
        final String command = P4Constants.P4_SET;
        try {
            final ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/c", command);
            builder.redirectErrorStream(true);

            final Process process = builder.start();
            final BufferedReader bufReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            Map<String, String> p4SetArgs = new HashMap<>();
            String line;
            while (true)
            {
                line = bufReader.readLine();
                if (line == null)
                {
                    break;
                }

                for (String p4Setting: requiredSettingsFromP4set){
                    boolean p4SettingExtracted = extractP4Setting(p4Setting, line, p4SetArgs);
                    if (p4SettingExtracted){
                        break;
                    }
                }
            }
            bufReader.close();
            return p4SetArgs;
        }
        catch (IOException e)
        {
            throw new VCSAnalyzerException("Failed to execute command: " + command, e);
        }
    }

    private static boolean extractP4Setting(String p4Setting, String line, Map<String, String> p4SetArgs) {
        if (line.contains(p4Setting))
        {
            String setting = line.endsWith(P4_SET_VAR_UNWANTED_SUFFIX) ? line.substring(0, line.indexOf(P4_SET_VAR_UNWANTED_SUFFIX)) : line;
            setting = setting.replace(p4Setting + "=", "");
            p4SetArgs.put(p4Setting, setting);
            return true;
        }
        return false;
    }
}
