package org.tiatesting.core.vcs;

/**
 * The build-tool-neutral settings a {@link VCSReaderProvider} needs to construct a
 * {@link VCSReader}, and that {@link VcsDetector} reads to work out which VCS a project uses.
 * Built by each build plugin from its own configuration (Maven parameters, Gradle extension) via
 * {@link #builder()}. Settings a given VCS does not use are left {@code null}.
 */
public final class VcsSettings {

    private final String projectDir;
    private final boolean enabled;
    private final String vcsName;
    private final String serverUri;
    private final String userName;
    private final String password;
    private final String clientName;

    /**
     * Copy the builder's values into an immutable settings instance.
     *
     * @param builder the builder holding the configured values
     */
    private VcsSettings(final Builder builder) {
        this.projectDir = builder.projectDir;
        this.enabled = builder.enabled;
        this.vcsName = builder.vcsName;
        this.serverUri = builder.serverUri;
        this.userName = builder.userName;
        this.password = builder.password;
        this.clientName = builder.clientName;
    }

    /**
     * Start building a settings instance. Tia is enabled by default; every other value defaults
     * to {@code null}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return the project directory Tia runs against; the Git provider resolves the repository
     * from it, and {@link VcsDetector} looks for a {@code .git} directory from it upwards
     */
    public String getProjectDir() {
        return projectDir;
    }

    /**
     * @return whether Tia is enabled for this build; the Perforce provider skips connecting to
     * the server when it is not
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return the VCS name the user configured explicitly (e.g. {@code git}, {@code perforce}),
     * or {@code null} to auto-detect
     */
    public String getVcsName() {
        return vcsName;
    }

    /**
     * @return the VCS server URI (Perforce), or {@code null}
     */
    public String getServerUri() {
        return serverUri;
    }

    /**
     * @return the VCS user name (Perforce), or {@code null}
     */
    public String getUserName() {
        return userName;
    }

    /**
     * @return the VCS password (Perforce), or {@code null}
     */
    public String getPassword() {
        return password;
    }

    /**
     * @return the VCS client / workspace name (Perforce), or {@code null}
     */
    public String getClientName() {
        return clientName;
    }

    /**
     * Fluent builder for {@link VcsSettings}.
     */
    public static final class Builder {

        private String projectDir;
        private boolean enabled = true;
        private String vcsName;
        private String serverUri;
        private String userName;
        private String password;
        private String clientName;

        /**
         * Private so instances come from {@link VcsSettings#builder()}.
         */
        private Builder() {
        }

        /**
         * @param projectDir the project directory Tia runs against
         * @return this builder
         */
        public Builder projectDir(final String projectDir) {
            this.projectDir = projectDir;
            return this;
        }

        /**
         * @param enabled whether Tia is enabled for this build
         * @return this builder
         */
        public Builder enabled(final boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * @param vcsName the explicitly configured VCS name, or {@code null} to auto-detect
         * @return this builder
         */
        public Builder vcsName(final String vcsName) {
            this.vcsName = vcsName;
            return this;
        }

        /**
         * @param serverUri the VCS server URI (Perforce)
         * @return this builder
         */
        public Builder serverUri(final String serverUri) {
            this.serverUri = serverUri;
            return this;
        }

        /**
         * @param userName the VCS user name (Perforce)
         * @return this builder
         */
        public Builder userName(final String userName) {
            this.userName = userName;
            return this;
        }

        /**
         * @param password the VCS password (Perforce)
         * @return this builder
         */
        public Builder password(final String password) {
            this.password = password;
            return this;
        }

        /**
         * @param clientName the VCS client / workspace name (Perforce)
         * @return this builder
         */
        public Builder clientName(final String clientName) {
            this.clientName = clientName;
            return this;
        }

        /**
         * @return an immutable settings instance holding the values set on this builder
         */
        public VcsSettings build() {
            return new VcsSettings(this);
        }
    }
}
