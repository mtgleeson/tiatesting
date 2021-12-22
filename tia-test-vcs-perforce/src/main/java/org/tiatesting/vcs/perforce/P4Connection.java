package org.tiatesting.vcs.perforce;

import java.net.URISyntaxException;
import java.util.Map;
import java.util.Properties;
import com.perforce.p4java.client.IClient;
import com.perforce.p4java.exception.AccessException;
import com.perforce.p4java.exception.ConnectionException;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.exception.RequestException;
import com.perforce.p4java.option.UsageOptions;
import com.perforce.p4java.server.IOptionsServer;
import com.perforce.p4java.server.IServerAddress;
import com.perforce.p4java.server.ServerFactory;

public class P4Connection
{
    private static String serverUri;
    private static String userName;
    private static String clientName;
    private static String password;

    private static P4Connection INSTANCE;

    private IClient client;
    private IOptionsServer server;

    // Private constructor will prevent the instantiation of this class directly
    private P4Connection() {}

    /**
     * Returns the singleton instance of P4Connection.
     *
     * @return {@link P4Connection}
     */
    public static P4Connection getInstance()
    {
        if (INSTANCE == null)
        {
            INSTANCE = new P4Connection();
        }
        return INSTANCE;
    }

    public IClient getClient()
    {
        return client;
    }

    public IOptionsServer getServer()
    {
        return server;
    }

    /**
     * Connects to the P4 server using the instance param settings.
     *
     * @return
     */
    public boolean start() throws Exception
    {
        if (serverUri == null || serverUri.isEmpty() || password == null || password.isEmpty())
        {
            throw new Exception("P4 Settings have not been set yet. Can't establish connection.");
        }

        if (server == null || !server.isConnected())
        {
            server = getOptionsServer(null, null);

            server.setUserName(userName);
            server.login(password);

            client = server.getClient(clientName);

            if (client != null)
            {
                server.setCurrentClient(client);
                return true;
            }

            return false;
        }

        return true;
    }

    /**
     * Disconnects from the P4 server.
     *
     * @throws ConnectionException
     * @throws AccessException
     */
    public void stop() throws ConnectionException, AccessException
    {
        if (server != null && server.isConnected())
        {
            server.disconnect();
            server = null;
        }
    }

    /**
     * Set the P4 connection settings.
     *
     * @param settings
     * @throws Exception
     */
    public void setP4Settings(Map<String, String> settings) throws Exception
    {
        for (Map.Entry<String, String> settingKVpair : settings.entrySet())
        {
            switch(settingKVpair.getKey())
            {
                case P4Constants.P4PORT:
                    serverUri = IServerAddress.Protocol.P4JAVA.toString() + "://" + settingKVpair.getValue();
                    break;
                case P4Constants.P4USER:
                    userName = settingKVpair.getValue();
                    break;
                case P4Constants.P4CLIENT:
                    clientName = settingKVpair.getValue();
                    break;
                case P4Constants.P4PASSWD:
                    password = PasswordUtil.decryptPassword(settings.get(P4Settings.P4_SET_RANDOMKEY_CUSTOM_VAR), settingKVpair.getValue());
                    break;
            }
        }
    }

    /**
     * Refresh P4 client.
     *
     * @throws ConnectionException
     * @throws AccessException
     * @throws RequestException
     */
    public void refreshClient() throws ConnectionException, AccessException, RequestException
    {
        if (client != null && client.canRefresh())
        {
            client.refresh();
        }
    }

    /**
     * Get an IOptionsServer object from the P4Java server factory and connect to it.
     *
     * @param props if not null, P4Java properties object to pass to the P4Java
     * 				server factory.
     * @param opts if not null, P4Java UsageOptions object to pass to the P4Java
     * 				server factory.
     * @return connected IServer object ready for use.
     * @throws P4JavaException thrown if the server factory or the connection method
     * 				detect any errors.
     * @throws URISyntaxException thrown if the server URI passed to the server
     * 				factory is syntactically invalid
     */
    private static IOptionsServer getOptionsServer(Properties props, UsageOptions opts) throws P4JavaException, URISyntaxException {
        IOptionsServer server = ServerFactory.getOptionsServer(serverUri, props, opts);
        if (server != null)
        {
            server.connect();
        }
        return server;
    }
}
