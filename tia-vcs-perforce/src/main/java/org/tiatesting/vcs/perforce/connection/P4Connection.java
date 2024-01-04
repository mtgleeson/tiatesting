package org.tiatesting.vcs.perforce.connection;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Properties;
import com.perforce.p4java.client.IClient;
import com.perforce.p4java.exception.*;
import com.perforce.p4java.option.UsageOptions;
import com.perforce.p4java.server.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.vcs.VCSAnalyzerException;
import org.tiatesting.vcs.perforce.P4Reader;

public class P4Connection {
    private static final Logger log = LoggerFactory.getLogger(P4Connection.class);

    private String serverUri;
    private String userName;
    private String clientName;
    private String password;

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
    public void start() throws VCSAnalyzerException {
        if (serverUri == null || serverUri.isEmpty())
        {
            throw new VCSAnalyzerException("P4 Settings have not been set yet. Can't establish connection.");
        }

        if (server != null && server.isConnected()) {
            return;
        }

        try {
            server = getOptionsServer(null, null);
            server.setUserName(userName);

            if (password != null && !password.trim().isEmpty()){
                // login using the provided password if provided. Otherwise it will
                // default to using the p4ticket in user home %USERPROFILE%\p4tickets.txt
                // https://www.perforce.com/manuals/p4sag/Content/P4SAG/superuser.basic.auth.tickets.html
                // https://www.perforce.com/manuals/p4java/Content/P4Java/p4java.advanced.authentication.html
                server.login(password);
            }

            client = server.getClient(clientName);

            if (client == null)
            {
                throw new VCSAnalyzerException("Couldn't get the client from the P4 connection.");
            }

            server.setCurrentClient(client);
        }catch(P4JavaException | URISyntaxException e){
            throw new VCSAnalyzerException(e);
        }
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
     * @param serverUri
     * @param userName
     * @param password
     * @param clientName
     * @throws Exception
     */
    public void setP4Settings(final String serverUri, final String userName, final String password, final String clientName)
    {
        this.serverUri = IServerAddress.Protocol.P4JAVA.toString() + "://" + serverUri;
        this.userName = userName;
        this.clientName = clientName;
        this.password = password;
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
    private IOptionsServer getOptionsServer(Properties props, UsageOptions opts) throws P4JavaException, URISyntaxException {
        IOptionsServer server = ServerFactory.getOptionsServer(serverUri, props, opts);
        if (server != null)
        {
            server.connect();
        }
        return server;
    }
}
