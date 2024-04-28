package org.tiatesting.vcs.perforce.connection;

import com.perforce.p4java.client.IClient;
import com.perforce.p4java.exception.AccessException;
import com.perforce.p4java.exception.ConnectionException;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.exception.RequestException;
import com.perforce.p4java.option.UsageOptions;
import com.perforce.p4java.server.IOptionsServer;
import com.perforce.p4java.server.IServerAddress;
import com.perforce.p4java.server.ServerFactory;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.vcs.VCSAnalyzerException;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

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
     */
    public void stop(){
        if (server != null && server.isConnected())
        {
            try {
                server.disconnect();
            } catch (ConnectionException | AccessException e) {
                throw new VCSAnalyzerException(e);
            }
            server = null;
        }
    }

    /**
     * Set the P4 connection settings. If either of the serverUri, userName or clientName is not set,
     * try read it from the users workspace using the "p4 set" command.
     *
     * @param serverUri the Perforce server URI
     * @param userName the Perforce server username to connect with
     * @param password the Perforce server password to connect with
     * @param clientName the Perforce server client name to connect with
     */
    public void setP4Settings(final String serverUri, final String userName, final String password, final String clientName){
        Map<String, String> p4Settings = new HashMap<>();
        if (StringUtils.isBlank(serverUri) || StringUtils.isBlank(userName) || StringUtils.isBlank(clientName)){
            p4Settings = P4Settings.executeP4SetCommand();
        }
        setServerUri(serverUri, p4Settings.get(P4Constants.P4PORT));
        setUserName(userName, p4Settings.get(P4Constants.P4USER));
        setPassword(password);
        setClientName(clientName, p4Settings.get(P4Constants.P4CLIENT));
    }

    /**
     * Set the P4 server URI. If the server URI defined in the Tia configuration is not set, then use the value
     * from the P4 Set command.
     * @param configuredServerUri
     * @param p4SettingServerUri
     */
    private void setServerUri(final String configuredServerUri, String p4SettingServerUri){
        String serverUri = StringUtils.isBlank(configuredServerUri) ? p4SettingServerUri : configuredServerUri;
        this.serverUri = IServerAddress.Protocol.P4JAVA.toString() + "://" + serverUri;
    }

    /**
     * Set the P4 username. If the username defined in the Tia configuration is not set, then use the value
     * from the P4 Set command.
     * @param username
     * @param p4SettingUsername
     */
    private void setUserName(final String username, String p4SettingUsername){
        this.userName = StringUtils.isBlank(username) ? p4SettingUsername : username;
    }

    /**
     * Set the P4 password defined in the Tia configuration.
     * @param password
     */
    private void setPassword(final String password){
        this.password = password;
    }

    /**
     * Set the P4 client name. If the client name defined in the Tia configuration is not set, then use the value
     * from the P4 Set command.
     * @param clientName
     * @param p4SettingClientName
     */
    private void setClientName(final String clientName, String p4SettingClientName){
        this.clientName = StringUtils.isBlank(clientName) ? p4SettingClientName : clientName;
    }

    /**
     * Refresh P4 client.
     *
     * @throws ConnectionException a connection exception
     * @throws AccessException an access exception
     * @throws RequestException a request exception
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

    public String getServerUri() {
        return serverUri;
    }

    public String getUserName() {
        return userName;
    }

    public String getClientName() {
        return clientName;
    }
}
