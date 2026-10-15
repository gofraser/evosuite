/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.evosuite.maven.util.GeneratedTests;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

/**
 * Wire the generated tests into the build, so that the normal {@code test} phase compiles and runs them.
 *
 * <ul>
 *     <li>adds the folder of generated tests ({@code src/test/evosuite} by default) as a test source root,
 *     so no build-helper-maven-plugin configuration is needed;</li>
 *     <li>appends {@code -javaagent:<EvoSuite runtime>} to the {@code argLine} property, which Surefire and
 *     Failsafe pass to the test JVM, so the runtime does not have to attach itself while tests run;</li>
 *     <li>fails the build early, with the dependency to add, if the generated tests cannot compile because the
 *     EvoSuite runtime or their JUnit version is missing from the test classpath.</li>
 * </ul>
 *
 * <p>If Surefire is configured with an explicit {@code <argLine>}, it has to include {@code @{argLine}} for
 * the agent to be passed on, as with other agents set through this property (eg JaCoCo's).</p>
 */
@Mojo(name = "setup", defaultPhase = LifecyclePhase.GENERATE_TEST_SOURCES,
        requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public class SetupMojo extends AbstractMojo {

    static final String EVOSUITE_GROUP_ID = "org.evosuite";

    static final String STANDALONE_RUNTIME = "evosuite-standalone-runtime";

    static final String RUNTIME = "evosuite-runtime";

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    /**
     * Folder of the generated tests, relative to the module's base directory.
     */
    @Parameter(property = "evosuite.testFolder", defaultValue = GeneratedTests.DEFAULT_FOLDER)
    private String testFolder;

    /**
     * Whether to pass the EvoSuite runtime to the test JVM as a java agent.
     */
    @Parameter(property = "evosuite.agent", defaultValue = "true")
    private boolean agent;

    /**
     * Name of the property the agent is added to. Surefire and Failsafe read {@code argLine}.
     */
    @Parameter(property = "evosuite.argLinePropertyName", defaultValue = "argLine")
    private String argLinePropertyName;

    @Parameter(property = "evosuite.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(defaultValue = "${plugin}", readonly = true)
    private PluginDescriptor plugin;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping EvoSuite setup");
            return;
        }

        File folder = GeneratedTests.resolveFolder(project.getBasedir(), testFolder);
        project.addTestCompileSourceRoot(folder.getAbsolutePath());
        getLog().info("Generated tests folder: " + folder);

        GeneratedTests.Requirements needed;
        try {
            needed = GeneratedTests.scanRequirements(folder);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read the generated tests in " + folder, e);
        }

        Optional<Artifact> runtime = findRuntime();
        checkDependencies(needed, runtime.isPresent());
        if (runtime.isPresent() && plugin != null) {
            String mismatch = versionMismatch(plugin.getVersion(), runtime.get());
            if (mismatch != null) {
                throw new MojoExecutionException(mismatch);
            }
        }

        if (agent && runtime.isPresent()) {
            String value = GeneratedTests.appendAgent(project.getProperties().getProperty(argLinePropertyName),
                    runtime.get().getFile());
            project.getProperties().setProperty(argLinePropertyName, value);
            getLog().info(argLinePropertyName + " set to " + value);
        }
    }

    /**
     * Generated tests call the API of the runtime of the EvoSuite version that generated them, so the
     * runtime dependency has to be the plugin's version.
     *
     * @param pluginVersion the version of this plugin
     * @param runtime       the runtime dependency
     * @return what is wrong, or {@code null} if the versions match
     */
    static String versionMismatch(String pluginVersion, Artifact runtime) {
        String runtimeVersion = runtime.getBaseVersion();
        if (pluginVersion == null || pluginVersion.equals(runtimeVersion)) {
            return null;
        }
        return "The test dependency " + runtime.getArtifactId() + " has version " + runtimeVersion
                + ", but the EvoSuite plugin is version " + pluginVersion
                + ". Use the same version for both, eg with a property like ${evosuite.version}.";
    }

    private Optional<Artifact> findRuntime() {
        Optional<Artifact> standalone = findArtifact(EVOSUITE_GROUP_ID, STANDALONE_RUNTIME);
        return standalone.isPresent() ? standalone : findArtifact(EVOSUITE_GROUP_ID, RUNTIME);
    }

    private Optional<Artifact> findArtifact(String groupId, String artifactId) {
        return project.getArtifacts().stream()
                .filter(a -> groupId.equals(a.getGroupId()) && artifactId.equals(a.getArtifactId()))
                .filter(a -> a.getFile() != null)
                .findFirst();
    }

    private void checkDependencies(GeneratedTests.Requirements needed, boolean hasRuntime)
            throws MojoExecutionException {
        StringBuilder missing = new StringBuilder();
        if (needed.evosuiteRuntime && !hasRuntime) {
            missing.append(snippet(EVOSUITE_GROUP_ID, STANDALONE_RUNTIME, "<!-- the EvoSuite version -->"));
        }
        if (needed.jupiter && !findArtifact("org.junit.jupiter", "junit-jupiter-api").isPresent()) {
            missing.append(snippet("org.junit.jupiter", "junit-jupiter", "<!-- 5.x -->"));
        }
        if (needed.junit4 && !findArtifact("junit", "junit").isPresent()) {
            missing.append(snippet("junit", "junit", "<!-- 4.13.x -->"));
        }
        if (missing.length() > 0) {
            throw new MojoExecutionException("The EvoSuite tests in " + testFolder + " cannot compile:"
                    + " add these test dependencies to the pom of " + project.getArtifactId() + "\n" + missing);
        }
    }

    private static String snippet(String groupId, String artifactId, String version) {
        return "    <dependency>\n"
                + "        <groupId>" + groupId + "</groupId>\n"
                + "        <artifactId>" + artifactId + "</artifactId>\n"
                + "        <version>" + version + "</version>\n"
                + "        <scope>test</scope>\n"
                + "    </dependency>\n";
    }
}
