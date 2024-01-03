package org.tiatesting.vcs.perforce;

import org.tiatesting.vcs.perforce.connection.P4Connection;

import java.util.Objects;

public class P4Context {

    private final P4Connection p4Connection;

    private final String branchName;

    private final String headCL;

    public P4Context(final P4Connection p4Connection, final String branchName, final String headCL) {
        this.p4Connection = p4Connection;
        this.branchName = branchName;
        this.headCL = headCL;
    }

    public P4Connection getP4Connection() {
        return p4Connection;
    }

    public String getBranchName() {
        return branchName;
    }

    public String getHeadCL() {
        return headCL;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        P4Context p4Context = (P4Context) o;
        return Objects.equals(p4Connection, p4Context.p4Connection) && Objects.equals(branchName, p4Context.branchName) && Objects.equals(headCL, p4Context.headCL);
    }

    @Override
    public int hashCode() {
        return Objects.hash(p4Connection, branchName, headCL);
    }
}
