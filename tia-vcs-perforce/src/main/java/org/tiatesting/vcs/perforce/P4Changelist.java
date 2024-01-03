package org.tiatesting.vcs.perforce;

import java.util.Date;
import java.util.List;
import com.perforce.p4java.client.IClient;
import com.perforce.p4java.core.ChangelistStatus;
import com.perforce.p4java.core.IChangelist;
import com.perforce.p4java.core.file.IFileSpec;
import com.perforce.p4java.exception.AccessException;
import com.perforce.p4java.exception.ConnectionException;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.exception.RequestException;
import com.perforce.p4java.impl.generic.core.Changelist;
import com.perforce.p4java.impl.mapbased.server.Server;
import com.perforce.p4java.option.client.AddFilesOptions;
import com.perforce.p4java.option.client.EditFilesOptions;
import com.perforce.p4java.option.client.ReconcileFilesOptions;
import com.perforce.p4java.option.client.ReopenFilesOptions;
import com.perforce.p4java.option.client.RevertFilesOptions;
import com.perforce.p4java.server.IOptionsServer;
import org.tiatesting.vcs.perforce.connection.P4Connection;

public class P4Changelist
{
    private P4Connection p4Connection;
    private IChangelist changelist;

    public P4Changelist(P4Connection p4Connection)
    {
        this.p4Connection = p4Connection;
    }

    /**
     * Creates an empty changelist with the provided description.
     *
     * @param clDescription
     * @return
     * @throws ConnectionException
     * @throws AccessException
     * @throws RequestException
     */
    public void createEmpty(String clDescription) throws ConnectionException, AccessException, RequestException
    {
        IClient client = p4Connection.getClient();
        IOptionsServer server = p4Connection.getServer();

        Changelist changeListImpl = new Changelist(IChangelist.UNKNOWN, client.getName(), server.getUserName(),
                                                   ChangelistStatus.NEW, new Date(), clDescription, false,
                                                   (Server) server);

        changelist = client.createChangelist(changeListImpl);
    }

    /**
     * Add specified files to the current CL.
     *
     * @param files
     * @throws P4JavaException
     */
    public void add(List<IFileSpec> files) throws P4JavaException
    {
        AddFilesOptions addFilesOptions = new AddFilesOptions()
                .setChangelistId(changelist.getId());

        p4Connection.getClient().addFiles(files, addFilesOptions);
    }

    /**
     * Open for edit the specified files in the current CL.
     *
     * @param files
     * @throws P4JavaException
     */
    public void openForEdit(List<IFileSpec> files) throws P4JavaException
    {
        EditFilesOptions editFilesOptions = new EditFilesOptions()
                .setChangelistId(changelist.getId());

        p4Connection.getClient().editFiles(files, editFilesOptions);
    }

    /**
     * Reconcile offline changes in the specified files.
     *
     * @param files
     * @throws P4JavaException
     */
    public void reconcile(List<IFileSpec> files) throws P4JavaException
    {
        ReconcileFilesOptions reconcileFilesOptions = new ReconcileFilesOptions()
                .setOutsideAdd(true)
                .setOutsideEdit(true)
                .setRemoved(true);

        if (changelist != null)
        {
            reconcileFilesOptions.setChangelistId(changelist.getId());
        }

        p4Connection.getClient().reconcileFiles(files, reconcileFilesOptions);
    }

    /**
     * If specified files exist in other CLs, move them to the current CL.
     *
     * @param files
     * @throws P4JavaException
     */
    public void reopen(List<IFileSpec> files) throws P4JavaException
    {
        ReopenFilesOptions reopenFilesOptions = new ReopenFilesOptions()
                .setChangelistId(changelist.getId());

        p4Connection.getClient().reopenFiles(files, reopenFilesOptions);
    }

    /**
     * Reverts the specified files.
     *
     * @param files
     * @param wipeAddFiles
     * @param revertOnlyUnchanged
     * @throws P4JavaException
     */
    public void revert(List<IFileSpec> files, boolean wipeAddFiles, boolean revertOnlyUnchanged) throws P4JavaException
    {
        RevertFilesOptions revertFilesOptions = new RevertFilesOptions()
                .setWipeAddFiles(wipeAddFiles)
                .setRevertOnlyUnchanged(revertOnlyUnchanged);

        p4Connection.getClient().revertFiles(files, revertFilesOptions);
    }

    /**
     * Triggers a CL update to pickup latest changes
     */
    public void update() throws ConnectionException, AccessException, RequestException
    {
        if (changelist != null && changelist.canUpdate())
        {
            changelist.update();
        }
    }
}
