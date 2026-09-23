package org.tiatesting.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.SettingsProblem;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.tiatesting.core.distributed.DistributedRunPreconditions;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.persistence.CredentialResolver;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.DataStoreFactory;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.staticselection.StaticTestSelectionRule;
import org.tiatesting.core.staticselection.StaticTestSelectionRuleMode;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.vcs.WorkspaceIdentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractTiaMojo extends AbstractMojo {

    /**
     * Name of the fork properties file written under {@link #getTiaBuildDir()}, carrying the
     * system properties the forked test JVM needs plus the distributed-run handoff (resolved
     * runner key, claimed group number). Shared here, rather than declared separately by the
     * writer and the reader, so {@link AbstractTiaAgentMojo#writeForkPropertiesFile} and the
     * {@code dist-complete} goal that reads it back can never drift apart on the filename.
     */
    static final String FORK_PROPERTIES_FILENAME = "fork.properties";

    /**
     * Maven project.
     */
    @Parameter(property = "project", readonly = true)
    private MavenProject project;

    @Parameter(property = "tiaBuildDir", defaultValue = "${project.build.directory}/tia")
    String tiaBuildDir;

    /**
     * The file path to the root folder of the project being analyzed.
     *
     */
    @Parameter(property = "tiaProjectDir")
    String tiaProjectDir;

    /**
     * The file path for the saved DB containing the previous analysis of the project.
     * Used for embedded H2 mode. Ignored when {@link #tiaDBUrl} is set.
     */
    @Parameter(property = "tiaDBFilePath")
    String tiaDBFilePath;

    /**
     * JDBC URL of an H2 database running in server (TCP) mode, e.g.
     * {@code jdbc:h2:tcp://h2host:9092/tiadb}. When set, Tia connects to that server instead of
     * an embedded file-on-disk database and {@link #tiaDBFilePath} is ignored. The URL is used
     * verbatim, so per-branch isolation (if wanted) must be encoded in the URL.
     */
    @Parameter(property = "tiaDBUrl")
    String tiaDBUrl;

    /**
     * Explicit SQL dialect id override (e.g. {@code "h2"}), or unset to infer the dialect from
     * {@link #tiaDBUrl}. See {@link DataStoreFactory} ({@code fromConfig}).
     */
    @Parameter(property = "tiaDBDialect")
    String tiaDBDialect;

    /**
     * Database username for server-mode H2 ({@link #tiaDBUrl}). Defaults to {@code tia} when unset.
     */
    @Parameter(property = "tiaDBUser")
    String tiaDBUser;

    /**
     * Database password for server-mode H2 ({@link #tiaDBUrl}). Defaults to an empty password
     * when unset.
     */
    @Parameter(property = "tiaDBPassword")
    String tiaDBPassword;

    /**
     * Path of a file holding the database password, as an alternative to {@link #tiaDBPassword}.
     *
     * <p>Tia only ever reads this file - it never writes one here - so the secret need not appear
     * in the POM and no copy of it is staged anywhere: only the path is forwarded to the forked
     * test JVM, and a path is not a secret. Intended for a mounted Docker or Kubernetes secret, or
     * a file CI writes. Exactly one trailing newline is stripped when it is read.
     */
    @Parameter(property = "tiaDBPasswordFile")
    String tiaDBPasswordFile;

    /**
     * Id of a {@code <server>} entry in {@code ~/.m2/settings.xml} to take the database username
     * and password from.
     *
     * <p>A server id is not a secret, so it can live in a committed parent POM while the credential
     * stays on each developer's machine. This is also the only route on which Maven's own password
     * encryption applies: {@code mvn --encrypt-password} covers {@code <server>} and
     * {@code <proxy>} passwords, never an arbitrary {@code <properties>} entry.
     *
     * <p>A machine with no matching {@code <server>} is not an error - resolution falls through to
     * the next channel - so one parent POM can name a server id that only developer machines
     * define while CI supplies {@code TIA_DB_PASSWORD} instead.
     */
    @Parameter(property = "tiaDBServerId")
    String tiaDBServerId;

    /** The effective settings, supplying the {@code <server>} entry {@link #tiaDBServerId} names. */
    @Parameter(defaultValue = "${settings}", readonly = true)
    Settings settings;

    /** Maven's own decrypter, so an encrypted {@code <server>} password works as it does elsewhere. */
    @Component
    SettingsDecrypter settingsDecrypter;

    /**
     * The source files directories for the project being analyzed.
     */
    @Parameter(property = "tiaSourceFilesDirs")
    String tiaSourceFilesDirs;

    /**
     * Comma-separated list of {@code groupId:artifactId} coordinates identifying in-repo
     * libraries that should additionally be tracked for coverage. TIA resolves the version
     * from the source project's pom and includes the corresponding JAR in JaCoCo analysis.
     * The library source directories should also be listed in {@link #tiaSourceFilesDirs} so
     * VCS diff analysis picks up changes.
     */
    @Parameter(property = "tiaSourceLibs")
    String tiaSourceLibs;

    /**
     * The file path to the root of the source project - the project whose pom declares the
     * dependencies used to resolve {@link #tiaSourceLibs} to JAR files. Defaults to
     * {@link #tiaProjectDir} when not set. Only needed when the project running the tests is
     * different from the source project being tracked.
     */
    @Parameter(property = "tiaSourceProjectDir")
    String tiaSourceProjectDir;

    /**
     * The test files directories for the project being analyzed.
     */
    @Parameter(property = "tiaTestFilesDirs")
    String tiaTestFilesDirs;

    /**
     * The compiled class file directories for the project being analyzed, forwarded to the forked
     * test JVM for {@code JacocoClient}. Optional.
     */
    @Parameter(property = "tiaClassFilesDirs")
    String tiaClassFilesDirs;

    /**
     * Optional CSV of directory paths that contain built library jars (e.g. a deployment
     * {@code lib/} directory with version-stamped filenames like {@code <artifactId>-<version>.jar}).
     * When set, Tia resolves each {@link #tiaSourceLibs} coordinate to its jar by filename matching
     * inside these directories instead of resolving through the source project's pom - the
     * offline-safe path that needs no complete local Maven repository. See the "Directory-based
     * library-jar resolution" chapter in {@code WIKI.md}.
     */
    @Parameter(property = "tiaLibraryJarsDirs")
    String tiaLibraryJarsDirs;

    /**
     * Is TIA enabled?
     */
    @Parameter(property = "tiaEnabled")
    boolean tiaEnabled;

    /**
     * Should the mapping data in the TIA DB be updated with this test run? This also controls the
     * run statistics: the build that owns the mapping is the build whose timings are the reference
     * ones, so the two are one decision rather than two.
     */
    @Parameter(property = "tiaUpdateDBMapping")
    boolean tiaUpdateDBMapping;

    /**
     * Should this run log a row to the {@code tia_test_run_history} table? Defaults to
     * {@code true} - the history log is cheap and only useful when continuously populated.
     */
    @Parameter(property = "tiaUpdateDBTestRunHistory", defaultValue = "true")
    boolean tiaUpdateDBTestRunHistory;

    /**
     * The label recorded in the history row's {@code run_source} column, overriding Tia's own
     * detection.
     * <p>
     * Leave this unset unless the detection gets it wrong. Tia reads the CI marker environment
     * variables, which a forked test JVM inherits, so an ordinary CI job is already labelled
     * {@code CI} and a developer's machine {@code LOCAL} with nothing configured. Set it to
     * distinguish a build the detection cannot tell apart from any other - a nightly or a
     * performance rig - or to label a CI system Tia does not recognise.
     */
    @Parameter(property = "tiaRunSource")
    String tiaRunSource;

    /**
     * Isolates this execution's datastore into its own schema, {@code tia_<branch>_<suffix>}.
     * <p>
     * Declare one per test execution on a module that runs more than one Tia-enabled execution -
     * surefire and failsafe, say. Two executions sharing a schema delete each other's tracked test
     * suites and share one stored commit value, which costs selectivity and can silently
     * under-select.
     * <p>
     * Leave unset for a module with a single test execution: the schema is then the
     * {@code tia_<branch>} Tia has always used, so nothing moves.
     */
    @Parameter(property = "tiaDBSchemaSuffix")
    String tiaDBSchemaSuffix;

    /**
     * Comma-separated schema suffixes a library publish stamp is written to - the schemas of the
     * projects that <em>consume</em> this library.
     * <p>
     * Only needed by a module publishing a tracked library to consumers that isolate their tests
     * into suffixed schemas. Tia cannot derive the list: the consuming app is a separate build, so
     * this module has no visibility of its schemas, and a stamp written where no consumer reads it
     * is never drained - the suites the library change affects are never re-run.
     * <p>
     * Leave unset when consumers use the plain {@code tia_<branch>} schema.
     */
    @Parameter(property = "tiaLibraryStampSchemas")
    String tiaLibraryStampSchemas;

    /**
     * Specifies the default option for whether Tia should analyse local changes when selecting tests.
     */
    @Parameter(property = "tiaCheckLocalChanges")
    boolean tiaCheckLocalChanges;

    /**
     * Specifies the server URI of the VCS system.
     */
    @Parameter(property = "tiaVcsServerUri")
    String tiaVcsServerUri;

    /**
     * Specifies the username for connecting to the VCS system.
     */
    @Parameter(property = "tiaVcsUserName")
    String tiaVcsUserName;

    /**
     * Specifies the password for connecting to the VCS system.
     */
    @Parameter(property = "tiaVcsPassword")
    String tiaVcsPassword;

    /**
     * Specifies the client name used when connecting to the VCS system.
     */
    @Parameter(property = "tiaVcsClientName")
    String tiaVcsClientName;

    /**
     * The branch this build is running against, overriding the branch Tia would otherwise read
     * from the version control system. The branch selects the datastore schema, so it has to be
     * known before any database connection is opened and cannot be read back out of the database.
     *
     * <p>Set this on a build with no version control access - a distributed test run's runner job
     * holding nothing but a checked-out tree - and Tia never constructs a VCS reader to resolve it.
     * Leave it unset and the branch is read from the VCS exactly as before.
     */
    @Parameter(property = "tiaBranch")
    String tiaBranch;

    /**
     * The commit this build is running against, overriding the head commit Tia would otherwise
     * read from the version control system.
     *
     * <p>On a distributed runner this is what the claim compares against the commit the plan was
     * built by diffing, so it must be the commit the pipeline actually checked out (a CI system's
     * own checkout SHA variable), not the plan's own reported commit fed back in - that would
     * compare a value with itself. Leave it unset and the commit is read from the VCS as before.
     */
    @Parameter(property = "tiaCommitValue")
    String tiaCommitValue;

    /**
     * Whether this build participates in a distributed test run: the tests Tia selects are split
     * into groups and persisted to a shared database instead of all running in this one build.
     * Distributed runs require a shared datastore ({@link #tiaDBUrl}), and reject {@link
     * #tiaCheckLocalChanges} only when {@link #tiaUpdateDBMapping} is also on (checking local
     * changes is allowed when the run does not update the mapping) - see {@code
     * DistributedRunPreconditions} in {@code tia-core}.
     */
    @Parameter(property = "tiaDistributed")
    boolean tiaDistributed;

    /**
     * The shared identifier every runner in a distributed test run must agree on, so each runner
     * finds the same run's rows in the shared database. Required when {@link #tiaDistributed} is
     * enabled; typically the CI pipeline's build or run id.
     */
    @Parameter(property = "tiaRunId")
    String tiaRunId;

    /**
     * The fixed number of groups to split a distributed run's selected tests into. Mutually
     * exclusive with {@link #tiaDistributedTargetRunTime} - exactly one of the two must be set.
     * Boxed rather than a primitive {@code int} so an unset value is distinguishable from a
     * deliberate {@code 0}, which {@code DistributedRunConfig} relies on to tell "not configured"
     * apart from a configured value of zero.
     */
    @Parameter(property = "tiaDistributedGroupCount")
    Integer tiaDistributedGroupCount;

    /**
     * The target wall-clock run time, in milliseconds, a distributed run should balance groups to
     * meet. Mutually exclusive with {@link #tiaDistributedGroupCount} - exactly one of the two
     * must be set. Boxed for the same "unset vs. zero" reason as {@link
     * #tiaDistributedGroupCount}.
     */
    @Parameter(property = "tiaDistributedTargetRunTime")
    Long tiaDistributedTargetRunTime;

    /**
     * An optional ceiling on the number of groups a distributed run may be split into when
     * balancing for {@link #tiaDistributedTargetRunTime}. Meaningless - and rejected - alongside a
     * fixed {@link #tiaDistributedGroupCount}. Boxed so "no ceiling configured" is distinguishable
     * from a configured ceiling of zero.
     */
    @Parameter(property = "tiaDistributedMaxGroups")
    Integer tiaDistributedMaxGroups;

    /**
     * An optional per-runner identity value used by the distributed run's claim protocol to tell
     * concurrent runners apart. Falls back to {@code runId + hostname + pid} when not supplied.
     */
    @Parameter(property = "tiaDistributedRunnerKey")
    String tiaDistributedRunnerKey;

    /**
     * Static test selection rules. Each rule maps a regex over the repo-relative paths of
     * changed files to a set of test suites that should be force-run regardless of dynamic
     * coverage-based selection. Rules are additive: their selected suites are unioned into
     * the dynamic test selection.
     *
     * <p>Configured as a nested element list in the plugin block, e.g.
     * <pre>{@code
     * <tiaStaticTestSelectionRules>
     *   <tiaStaticTestSelectionRule>
     *     <name>db-migrations</name>
     *     <filePathPattern>src/main/resources/db/migrations/.*\.sql$</filePathPattern>
     *     <mode>SUITE_NAMES</mode>
     *     <suiteNamePatterns>
     *       <suiteNamePattern>.*MigrationIT$</suiteNamePattern>
     *     </suiteNamePatterns>
     *   </tiaStaticTestSelectionRule>
     * </tiaStaticTestSelectionRules>
     * }</pre>
     */
    @Parameter
    List<MavenStaticTestSelectionRule> tiaStaticTestSelectionRules;

    /**
     * Maven project builder used to load the source project's pom so its declared dependencies
     * can be resolved when mapping {@code tiaSourceLibs} coordinates to JAR files.
     */
    @Component
    protected ProjectBuilder projectBuilder;

    /**
     * Current Maven session, used to seed the {@link org.apache.maven.project.ProjectBuildingRequest}
     * passed to {@link #projectBuilder} with the active repositories and settings.
     */
    @Parameter(defaultValue = "${session}", readonly = true)
    protected MavenSession session;

    public MavenProject getProject(){
        return project;
    }

    /**
     * Resolve the Maven projects taking part in the current build session, so a distributed-run
     * precondition can see more than just the module currently executing. Both {@code
     * dist-plan} and {@code prepare-agent} are bound to per-module Maven phases, not to an
     * aggregator goal, so on a multi-module reactor each runs once per module - a caller that read
     * only {@link #getProject()} would see just its own module and never detect that more than one
     * module is taking part. Exposed as its own overridable method, rather than reading {@link
     * #session} inline, so a unit test can drive a mojo's {@code execute()} without constructing a
     * real {@code MavenSession}.
     *
     * @return the projects in the current Maven session, in build order - {@link
     *         MavenSession#getProjects()} reports the projects actually taking part in this
     *         invocation, which for a reactor-scoped build (e.g. {@code mvn -pl a}) is narrower
     *         than the full reactor
     */
    protected List<MavenProject> getReactorProjects() {
        return session.getProjects();
    }

    /**
     * Append the reactor's project artifact ids to a distributed-run precondition failure message
     * when the failure is the multi-project-reactor rule, so both distributed-run entry points -
     * the {@code dist-plan} goal and the {@code prepare-agent} goal - report exactly which
     * modules were found in the reactor. Shared here rather than duplicated per mojo since {@code
     * tia-core} has no Maven type to name the projects with itself; this method converts {@link
     * #getReactorProjects()}'s {@link MavenProject} list to plain artifact-id strings and delegates
     * the "only when relevant" gate to {@link
     * DistributedRunPreconditions#withReactorProjectNamesIfRelevant}, the same core helper the
     * Gradle plan task's equivalent wrapper delegates to.
     *
     * @param message the failure message from {@code DistributedRunPreconditions.check}
     * @param reactorProjects the projects {@link #getReactorProjects()} resolved for this build
     * @return {@code message} unchanged, or with the reactor's project artifact ids appended when
     *         Tia is enabled and the reactor holds more than one project
     */
    protected String withReactorProjectNamesIfRelevant(final String message, final List<MavenProject> reactorProjects) {
        List<String> names = new ArrayList<>(reactorProjects.size());
        for (MavenProject reactorProject : reactorProjects) {
            names.add(reactorProject.getArtifactId());
        }
        return DistributedRunPreconditions.withReactorProjectNamesIfRelevant(message, isTiaEnabled(), names);
    }

    public String getTiaBuildDir() {
        return tiaBuildDir;
    }

    /**
     * Resolve the fork properties file's path under {@link #getTiaBuildDir()}. Centralised so the
     * mojo that writes the file and the goal that later reads it back build the identical path
     * from the same constant, rather than each concatenating {@link #getTiaBuildDir()} and
     * {@link #FORK_PROPERTIES_FILENAME} separately.
     *
     * @return the absolute path of the fork properties file for this build
     */
    protected String getForkPropertiesFilename() {
        return getTiaBuildDir() + "/" + FORK_PROPERTIES_FILENAME;
    }

    public String getTiaProjectDir(){
        return tiaProjectDir;
    }

    public String getTiaDBFilePath(){
        return tiaDBFilePath;
    }

    /**
     * @return the configured server-mode H2 JDBC URL, or {@code null} for embedded mode
     */
    public String getTiaDBUrl(){
        return tiaDBUrl;
    }

    /**
     * @return the configured SQL dialect override, or {@code null} to infer the dialect from
     *         {@link #getTiaDBUrl()}
     */
    public String getTiaDBDialect(){
        return tiaDBDialect;
    }

    /**
     * @return the configured server-mode H2 username, or {@code null} to use the default
     */
    public String getTiaDBUser(){
        return tiaDBUser;
    }

    /**
     * @return the configured server-mode H2 password, or {@code null} to use the default
     */
    public String getTiaDBPassword(){
        return tiaDBPassword;
    }

    /**
     * Resolve the database password from the channels a build can supply it through, in precedence
     * order: the configured {@link #tiaDBPassword}, then {@link #tiaDBPasswordFile}, then the
     * {@value CredentialResolver#ENV_DB_PASSWORD} environment variable, then an empty password.
     *
     * <p>Resolving in the build JVM rather than in the forked test JVM is what lets the fork be
     * handed a reference instead of the secret. See the credentials chapter in {@code WIKI.md}.
     *
     * @return the resolved password, never {@code null}
     * @throws MojoExecutionException if the configured password is an unresolved Maven expression,
     *         or the configured password file cannot be read
     */
    String resolveDbPassword() throws MojoExecutionException {
        String configured = configuredPassword();
        return configured != null
                ? configured
                : CredentialResolver.resolvePassword(null, System::getenv);
    }

    /**
     * The password this build configured through a channel other than the environment: the
     * {@link #tiaDBPassword} parameter, the {@code <server>} entry {@link #tiaDBServerId} names, or
     * {@link #tiaDBPasswordFile}, in that order.
     *
     * <p>Separate from {@link #resolveDbPassword()} because the two callers need different answers.
     * Resolution wants a usable password and so falls through to the environment; the fork handoff
     * needs to know whether anything was configured *at all*, since a build that configured nothing
     * must forward nothing and let the fork read the environment it already inherits. Keeping the
     * environment out of this method is also what preserves the null-vs-empty rule across the fork
     * boundary: an explicit empty password is a configured value and is forwarded.
     *
     * @return the configured password, or {@code null} when no non-environment channel supplied one
     * @throws MojoExecutionException if the configured password is an unresolved Maven expression,
     *         the named server entry cannot be decrypted, or the password file cannot be read
     */
    String configuredPassword() throws MojoExecutionException {
        rejectUnresolvedExpression("tiaDBPassword", tiaDBPassword);
        if (tiaDBPassword != null) {
            return tiaDBPassword;
        }
        Server server = decryptedServer();
        if (server != null && server.getPassword() != null) {
            return server.getPassword();
        }
        if (tiaDBPasswordFile != null && !tiaDBPasswordFile.trim().isEmpty()) {
            try {
                return CredentialResolver.readPasswordFile(tiaDBPasswordFile);
            } catch (IllegalStateException e) {
                // Rethrown as a Maven failure so a misconfigured path is reported as a build error
                // with its message, rather than as an internal error with a stack trace.
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
        return null;
    }

    /**
     * Resolve the database username by precedence: the configured {@link #tiaDBUser}, then the
     * username on the {@code <server>} entry {@link #tiaDBServerId} names, then
     * {@value CredentialResolver#ENV_DB_USER}, then H2's {@code tia} convention. A build naming a
     * server id should not have to repeat the username the entry already carries.
     *
     * @return the resolved username
     * @throws MojoExecutionException if the named server entry cannot be decrypted
     */
    String resolveDbUser() throws MojoExecutionException {
        rejectUnresolvedExpression("tiaDBUser", tiaDBUser);
        if (tiaDBUser != null && !tiaDBUser.trim().isEmpty()) {
            return tiaDBUser;
        }
        Server server = decryptedServer();
        if (server != null && server.getUsername() != null && !server.getUsername().trim().isEmpty()) {
            return server.getUsername();
        }
        // Null rather than a blank string, so DataStoreFactory treats the username as unconfigured
        // and applies the environment fallback and the H2-only default itself.
        return null;
    }

    /**
     * Decrypt the {@code <server>} entry {@link #tiaDBServerId} names, if there is one.
     *
     * <p>A missing entry returns {@code null} rather than failing, so a parent POM can name a
     * server id that only some machines define. A decryption *failure* is a different matter and
     * must be fatal: {@link SettingsDecrypter} reports it only through
     * {@link SettingsDecryptionResult#getProblems()} and hands back the server with its password
     * still encrypted, so a caller that ignored the problems would send the ciphertext to the
     * database and the user would see only an opaque authentication error.
     *
     * @return the decrypted server entry, or {@code null} when no server id is configured, no
     *         matching entry exists, or the settings are unavailable
     * @throws MojoExecutionException if the entry exists but could not be decrypted
     */
    private Server decryptedServer() throws MojoExecutionException {
        if (tiaDBServerId == null || tiaDBServerId.trim().isEmpty() || settings == null) {
            return null;
        }
        Server configured = settings.getServer(tiaDBServerId);
        if (configured == null) {
            return null;
        }
        SettingsDecryptionResult result =
                settingsDecrypter.decrypt(new DefaultSettingsDecryptionRequest(configured));
        for (SettingsProblem problem : result.getProblems()) {
            if (problem.getSeverity() == SettingsProblem.Severity.ERROR
                    || problem.getSeverity() == SettingsProblem.Severity.FATAL) {
                // Deliberately does not echo the stored value: the message is user-facing and the
                // ciphertext is still a credential.
                throw new MojoExecutionException("Tia could not decrypt the password for "
                        + "<server><id>" + tiaDBServerId + "</id></server> in settings.xml: "
                        + problem.getMessage() + ". Check that settings-security.xml exists and "
                        + "holds the master password the entry was encrypted with.");
            }
        }
        return result.getServer();
    }

    /**
     * Fail the build when a configured credential is still a literal Maven expression.
     *
     * <p>Maven leaves an unresolvable {@code ${...}} in place rather than erroring, so a POM
     * carrying {@code <tiaDBPassword>${env.TIA_DB_PASSWORD}</tiaDBPassword>} on a machine where the
     * variable is not set - or {@code <tiaDBUser>${tia.db.user}</tiaDBUser>} where the property is
     * undefined - hands Tia the literal text and the build succeeds. Tia then authenticates with
     * that literal and the failure surfaces only as an opaque authentication error from the
     * database, a long way from its cause. Reject it here and name it instead.
     *
     * <p>Only a value that is entirely one expression is rejected. A credential that merely
     * contains a dollar sign is a real credential, and Maven would have interpolated a genuine
     * expression before the mojo ever saw it.
     *
     * @param parameterName the configuration parameter's name, for the message
     * @param value         the configured value to check
     * @throws MojoExecutionException if the value is entirely an unresolved Maven expression
     */
    private void rejectUnresolvedExpression(final String parameterName, final String value)
            throws MojoExecutionException {
        if (value != null && value.startsWith("${") && value.endsWith("}")
                && value.indexOf('}') == value.length() - 1) {
            throw new MojoExecutionException(parameterName + " resolved to the literal expression "
                    + value + ", which means the property or environment variable behind it is not "
                    + "set. Either set it, or remove <" + parameterName + "> and let Tia resolve "
                    + "the credential from a settings.xml <server> entry or the environment "
                    + "itself.");
        }
    }

    /**
     * Build the {@link DataStore} for this mojo, resolving the SQL dialect from the mojo's
     * configured connection parameters via {@link DataStoreFactory}. Centralising this here keeps
     * every mojo's datastore construction consistent, and is the single place a mojo needs to
     * touch when a new dialect's configuration surface is added.
     *
     * @param branch the VCS branch name, used to derive the per-branch schema selected on each
     *               connection
     * @return the constructed datastore for the resolved dialect
     * @throws MojoExecutionException if the configured password cannot be resolved
     */
    protected DataStore buildDataStore(final String branch) throws MojoExecutionException {
        return buildDataStore(branch, getTiaDBSchemaSuffix());
    }

    /**
     * Construct the datastore for a branch and an explicit schema suffix.
     *
     * @param branch the VCS branch, the base of the schema name
     * @param schemaSuffix the schema suffix, or null for the unsuffixed {@code tia_<branch>} schema
     * @return the constructed datastore for the resolved dialect
     * @throws MojoExecutionException if the configured password cannot be resolved
     */
    protected DataStore buildDataStore(final String branch, final String schemaSuffix)
            throws MojoExecutionException {
        return DataStoreFactory.fromConfig(getTiaDBFilePath(), getTiaDBUrl(),
                resolveDbUser(), resolveDbPassword(), getTiaDBDialect(), branch, schemaSuffix);
    }

    public String getTiaSourceFilesDirs() {
        return tiaSourceFilesDirs;
    }

    public String getTiaSourceLibs() {
        return tiaSourceLibs;
    }

    /**
     * @return the configured source-project root, or {@link #getTiaProjectDir()} when blank.
     */
    public String getTiaSourceProjectDir() {
        if (tiaSourceProjectDir == null || tiaSourceProjectDir.trim().isEmpty()){
            return getTiaProjectDir();
        }
        return tiaSourceProjectDir;
    }

    public String getTiaTestFilesDirs() {
        return tiaTestFilesDirs;
    }

    public String getTiaClassFilesDirs() {
        return tiaClassFilesDirs;
    }

    /**
     * @return the configured CSV of directories to resolve {@link #tiaSourceLibs} jars from by
     *         filename, or null/blank when directory-based resolution is not in use.
     */
    public String getTiaLibraryJarsDirs() {
        return tiaLibraryJarsDirs;
    }

    public boolean isTiaEnabled() {
        return tiaEnabled;
    }

    public boolean isTiaUpdateDBMapping() {
        return tiaUpdateDBMapping;
    }

    /**
     * @return whether this run should log a row to the {@code tia_test_run_history} table
     */
    public boolean isTiaUpdateDBTestRunHistory() {
        return tiaUpdateDBTestRunHistory;
    }

    /**
     * @return the declared label for the history row's {@code run_source} column, or null to let
     *         Tia detect it from the environment
     */
    public String getTiaRunSource() {
        return tiaRunSource;
    }

    /**
     * @return the schema suffix isolating this execution's datastore, or null for none
     */
    public String getTiaDBSchemaSuffix() {
        return tiaDBSchemaSuffix;
    }

    /**
     * @return the consuming schemas' suffixes for a library publish stamp, comma separated, or null
     */
    public String getTiaLibraryStampSchemas() {
        return tiaLibraryStampSchemas;
    }

    public boolean isTiaCheckLocalChanges() {
        return tiaCheckLocalChanges;
    }

    /**
     * @return whether this build participates in a distributed test run
     */
    public boolean isTiaDistributed() {
        return tiaDistributed;
    }

    /**
     * @return the configured distributed run id, or {@code null} if not set
     */
    public String getTiaRunId() {
        return tiaRunId;
    }

    /**
     * @return the configured fixed group count for a distributed run, or {@code null} to use a
     *         target run time instead
     */
    public Integer getTiaDistributedGroupCount() {
        return tiaDistributedGroupCount;
    }

    /**
     * @return the configured target wall-clock run time in ms for a distributed run, or {@code
     *         null} to use a fixed group count instead
     */
    public Long getTiaDistributedTargetRunTime() {
        return tiaDistributedTargetRunTime;
    }

    /**
     * @return the configured ceiling on the group count for a distributed run, or {@code null}
     *         for no ceiling
     */
    public Integer getTiaDistributedMaxGroups() {
        return tiaDistributedMaxGroups;
    }

    /**
     * @return the configured per-runner identity value for a distributed run, or {@code null} to
     *         let the claim protocol derive one
     */
    public String getTiaDistributedRunnerKey() {
        return tiaDistributedRunnerKey;
    }

    public String getTiaVcsServerUri() {
        return tiaVcsServerUri;
    }

    public String getTiaVcsUserName() {
        return tiaVcsUserName;
    }

    public String getTiaVcsPassword() {
        return tiaVcsPassword;
    }

    public String getTiaVcsClientName() {
        return tiaVcsClientName;
    }

    /**
     * @return the configured static test selection rules, or an empty list when not configured.
     */
    public List<MavenStaticTestSelectionRule> getTiaStaticTestSelectionRules() {
        return tiaStaticTestSelectionRules != null ? tiaStaticTestSelectionRules : Collections.emptyList();
    }

    /**
     * @return the configured branch override, or {@code null} to read the branch from the VCS
     */
    public String getTiaBranch() {
        return tiaBranch;
    }

    /**
     * @return the configured commit override, or {@code null} to read the commit from the VCS
     */
    public String getTiaCommitValue() {
        return tiaCommitValue;
    }

    public abstract VCSReader getVCSReader();

    /**
     * Resolve this build's branch and commit, from the configured overrides where they are set and
     * from the version control system where they are not.
     *
     * <p>Every goal that needs either value goes through this rather than calling {@link
     * #getVCSReader()} itself, for two reasons. A configured value must never cause a reader to be
     * constructed - that is what lets a runner with no {@code .git} directory, or no reachable
     * Perforce server, resolve its schema at all. And the goals must agree on the answer: a plan
     * written to the schema the VCS reported while its runners claim from the schema {@code
     * tiaBranch} named would leave every runner unable to find the plan.
     *
     * <p>The returned identity holds at most one reader and closes it, so callers must close it -
     * use try-with-resources. A caller that also needs diffs takes the same reader from it through
     * {@code openVCSReader()} rather than constructing a second one.
     *
     * @return this build's workspace identity; never null
     */
    protected WorkspaceIdentity workspaceIdentity() {
        return WorkspaceIdentity.resolving(getTiaBranch(), getTiaCommitValue(), this::getVCSReader);
    }

    /**
     * Build the library impact analysis configuration from the Maven plugin parameters.
     * Coordinates in {@link #tiaSourceLibs} should be in the format
     * {@code groupId:artifactId} or {@code groupId:artifactId:projectDir}.
     *
     * @return the library impact analysis configuration parsed from the mojo's
     *         {@code tiaSourceLibs} and {@code tiaSourceProjectDir} parameters.
     */
    protected LibraryImpactAnalysisConfig buildLibraryImpactAnalysisConfig() {
        String libs = getTiaSourceLibs();
        if (libs == null || libs.trim().isEmpty()) {
            return new LibraryImpactAnalysisConfig(null, null, null, null);
        }

        List<String> coordinates = new ArrayList<>();
        Map<String, String> libraryProjectDirs = new HashMap<>();
        for (String raw : libs.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            String[] segments = entry.split(":");
            if (segments.length == 3) {
                String coord = segments[0].trim() + ":" + segments[1].trim();
                coordinates.add(coord);
                libraryProjectDirs.put(coord, segments[2].trim());
            } else if (segments.length == 2) {
                coordinates.add(entry);
            } else {
                getLog().warn("Invalid tiaSourceLibs entry '" + entry
                        + "' - expected groupId:artifactId or groupId:artifactId:projectDir, skipping.");
            }
        }

        LibraryJarResolver reader = new LibraryJarResolver(
                projectBuilder, session.getProjectBuildingRequest(), getLog());

        return new LibraryImpactAnalysisConfig(coordinates, libraryProjectDirs, getTiaSourceProjectDir(), reader);
    }

    /**
     * Build the static test selection configuration from the Maven nested
     * {@code <tiaStaticTestSelectionRules>} block. Validates each entry, parses its mode, and
     * pre-compiles its regex patterns. Returns {@link StaticTestSelectionConfig#EMPTY} when
     * no rules are configured.
     *
     * @return the parsed static test selection config.
     * @throws IllegalArgumentException if any rule is missing required fields, has an unknown
     *                                  mode, or contains an invalid regex.
     */
    protected StaticTestSelectionConfig buildStaticTestSelectionConfig() {
        List<MavenStaticTestSelectionRule> rawRules = getTiaStaticTestSelectionRules();
        if (rawRules.isEmpty()) {
            return StaticTestSelectionConfig.EMPTY;
        }

        List<StaticTestSelectionRule> compiledRules = new ArrayList<>(rawRules.size());
        for (MavenStaticTestSelectionRule raw : rawRules) {
            StaticTestSelectionRuleMode mode = parseStaticTestSelectionRuleMode(raw.getMode(), raw.getFilePathPattern());
            compiledRules.add(new StaticTestSelectionRule(
                    raw.getName(), raw.getFilePathPattern(), mode, raw.getSuiteNamePatterns()));
        }
        return new StaticTestSelectionConfig(compiledRules);
    }

    /**
     * Parse the raw mode string from the Maven config into the core enum. Empty or unknown
     * values produce a clear error rather than a silent default; we'd rather fail the build
     * than mis-route a rule.
     *
     * @param raw the raw mode string from the Maven config.
     * @param filePathPattern the rule's file-path pattern, used in the error message.
     * @return the parsed enum value.
     * @throws IllegalArgumentException if the value does not match a known mode.
     */
    private StaticTestSelectionRuleMode parseStaticTestSelectionRuleMode(final String raw,
                                                                         final String filePathPattern) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Static test selection rule '" + filePathPattern
                    + "': mode is required (one of RUN_ALL, SUITE_NAMES).");
        }
        try {
            return StaticTestSelectionRuleMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Static test selection rule '" + filePathPattern
                    + "': unknown mode '" + raw + "'. Expected one of RUN_ALL, SUITE_NAMES.");
        }
    }
}
