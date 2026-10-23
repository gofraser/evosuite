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

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.evosuite.Properties;
import org.evosuite.maven.util.EvoSuiteRunner;
import org.evosuite.maven.util.FileUtils;
import org.evosuite.maven.util.GeneratedTests;
import org.evosuite.maven.util.GenerationSettings;
import org.evosuite.maven.util.JobProperties;
import org.evosuite.maven.util.SpawnProcessManagerLease;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generate JUnit tests.
 *
 * <p>The generated tests are written to {@code src/test/evosuite} (see {@code testFolder}), which the
 * {@code setup} goal adds to the build. Tests for classes that no longer exist are removed from there.</p>
 */
@Mojo(name = "generate", requiresDependencyResolution = ResolutionScope.TEST,
        requiresDependencyCollection = ResolutionScope.TEST, threadSafe = true)
@Execute(phase = LifecyclePhase.COMPILE)
public class GenerateMojo extends AbstractEvoSuiteMojo {

    /**
     * Total Memory (in MB) for all classes tested in parallel, at least 500 per core.
     * If not set, 1000 per core.
     */
    @Parameter(property = "evosuite.memoryInMB")
    private Integer memoryInMB;

    /**
     * Number of cores CTG will use. In the POM configuration as {@code <cores>} or {@code <numberOfCores>}.
     */
    @Parameter(property = "evosuite.cores", alias = "cores", defaultValue = "1")
    private int numberOfCores;

    /**
     * Comma ',' separated list of CUTs to use in CTG. If none specified, then test all classes.
     */
    @Parameter(property = "evosuite.cuts")
    private String cuts;

    /**
     * Absolute path to a file having the list of CUTs specified. This is needed for operating
     * systems like Windows that have constraints on the size of input parameters and so could
     * not use "cuts" parameter instead if too many CUTs are specified.
     */
    @Parameter(property = "evosuite.cutsFile")
    private String cutsFile;

    /**
     * Patterns of class files to generate tests for, relative to the compiled classes, eg
     * {@code com/example/service/**}. All classes by default. Ignored if {@code cuts} or {@code cutsFile}
     * is given.
     */
    @Parameter
    private List<String> classIncludes;

    /**
     * Patterns of class files not to generate tests for, eg {@code **}{@code /generated/**}.
     * Ignored if {@code cuts} or {@code cutsFile} is given.
     */
    @Parameter
    private List<String> classExcludes;

    /**
     * How many minutes to allocate for each class. {@code timePerClass} takes precedence.
     */
    @Parameter(property = "evosuite.timeInMinutesPerClass", defaultValue = "2")
    private int timeInMinutesPerClass;

    /**
     * How long to generate tests for each class, eg {@code 90s}, {@code 2m} or {@code 1m30s}; at least 1m.
     * Takes precedence over {@code timeInMinutesPerClass}.
     */
    @Parameter(property = "evosuite.timePerClass")
    private String timePerClass;

    /**
     * How many minutes to allocate for each project/module.
     * If this parameter is not set, then the total time will be
     * timeInMinutesPerClass x number_of_classes.
     */
    @Parameter(property = "evosuite.timeInMinutesPerProject", defaultValue = "0")
    private int timeInMinutesPerProject;

    /**
     * Coverage criterion. Can define more than one criterion by using a ':' separated list.
     */
    // FIXME would be nice to have the value of Properties.CRITERION but seems to be not possible
    @Parameter(property = "evosuite.criterion",
            defaultValue = "LINE:BRANCH:EXCEPTION:WEAKMUTATION:OUTPUT:METHOD:METHODNOEXCEPTION:CBRANCH")
    private String criterion;

    /**
     * Port of a spawn process manager run by the caller (eg the IntelliJ plugin), which EvoSuite stops
     * for if the caller goes away.
     */
    @Parameter(property = "evosuite.spawnManagerPort")
    private Integer spawnManagerPort;

    /**
     * Further EvoSuite properties, as a space separated list of {@code -Dkey=value}.
     */
    @Parameter(property = "evosuite.extraArgs", defaultValue = "")
    private String extraArgs;

    /**
     * Schedule used to run CTG (SIMPLE, BUDGET, SEEDING, BUDGET_AND_SEEDING, HISTORY).
     */
    @Parameter(property = "evosuite.schedule", defaultValue = "BUDGET")
    private String schedule;

    /**
     * Folder the generated tests are written to, relative to the module's base directory.
     * Tests are also kept in EvoSuite's own {@code .evosuite} folder between runs.
     */
    @Parameter(property = "evosuite.testFolder", defaultValue = GeneratedTests.DEFAULT_FOLDER)
    private String testFolder;

    /**
     * Whether to delete generated tests from {@code testFolder} whose class under test no longer exists.
     */
    @Parameter(property = "evosuite.deleteStaleTests", defaultValue = "true")
    private boolean deleteStaleTests;

    @Parameter(property = "evosuite.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Further EvoSuite properties for test generation, eg the {@code llm_*} ones:
     * {@code <evosuiteProperties><llm_provider>OLLAMA</llm_provider></evosuiteProperties>}.
     * Values may contain spaces. Names are checked against EvoSuite's properties before any test is generated.
     * {@code extraArgs} take precedence. Do not put API keys here, see {@code llmApiKeyServerId}.
     */
    @Parameter
    private Map<String, String> evosuiteProperties;

    /**
     * Id of a {@code <server>} in the Maven {@code settings.xml} whose password is the API key of the LLM
     * provider. The password may be encrypted with {@code mvn --encrypt-password}. EvoSuite gets the key
     * through its environment, so it appears neither in the pom nor on any command line.
     */
    @Parameter(property = "evosuite.llmApiKeyServerId")
    private String llmApiKeyServerId;

    @Parameter(defaultValue = "${settings}", readonly = true)
    private Settings settings;

    @Component
    private SettingsDecrypter settingsDecrypter;

    /**
     * {@code timePerClass} in seconds, if given.
     */
    private Integer secondsPerClass;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        if (skip) {
            getLog().info("Skipping EvoSuite test generation");
            return;
        }
        applyLegacyProperties();

        getLog().info("Going to generate tests with EvoSuite");
        memoryInMB = GenerationSettings.memoryInMB(memoryInMB, numberOfCores);
        if (timePerClass != null && !timePerClass.trim().isEmpty()) {
            try {
                secondsPerClass = GenerationSettings.parseSeconds(timePerClass);
            } catch (IllegalArgumentException e) {
                throw new MojoExecutionException("Invalid timePerClass: " + e.getMessage(), e);
            }
            if (secondsPerClass < GenerationSettings.MIN_SECONDS_PER_CLASS) {
                throw new MojoExecutionException("timePerClass must be at least "
                        + GenerationSettings.MIN_SECONDS_PER_CLASS + "s, not " + timePerClass);
            }
        }

        getLog().info("Total memory: " + memoryInMB + "mb");
        getLog().info("Time per class: " + (secondsPerClass != null ? timePerClass.trim()
                : timeInMinutesPerClass + " minutes"));
        getLog().info("Number of used cores: " + numberOfCores);

        if (cuts != null) {
            getLog().info("Specified classes under test: " + cuts);
        }

        String target = null;
        String cp = null;

        try {

            //the targets we want to generate tests for, ie the CUTs
            for (String element : project.getCompileClasspathElements()) {
                if (element.endsWith(".jar")) {  // we only target what has been compiled to a folder
                    continue;
                }

                File file = new File(element);
                if (!file.exists()) {
                    /*
                     * don't add to target an element that does not exist
                     */
                    continue;
                }

                if (!file.getAbsolutePath().startsWith(
                        project.getBuild().getOutputDirectory())) {
                    /*
                     * This can happen in multi-module projects when module A has dependency on
                     * module B. Then, both A and B source folders will end up on compile classpath,
                     * although we are interested only in A.
                     */
                    continue;
                }

                if (target == null) {
                    target = element;
                } else {
                    target = target + File.pathSeparator + element;
                }
            }

            //build the classpath
            Set<String> alreadyAdded = new HashSet<>();
            for (String element : project.getTestClasspathElements()) {
                if (element.toLowerCase().contains("powermock")) {
                    //PowerMock just leads to a lot of troubles, as it includes tools.jar code
                    getLog().warn("Skipping PowerMock dependency at: " + element);
                    continue;
                }
                if (element.toLowerCase().contains("jmockit")) {
                    //JMockit has same issue
                    getLog().warn("Skipping JMockit dependency at: " + element);
                    continue;
                }
                getLog().debug("TEST ELEMENT: " + element);
                cp = addPathIfExists(cp, element, alreadyAdded);
            }

        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Failed to resolve the project classpath: " + e.getMessage(), e);
        }

        File basedir = project.getBasedir();

        getLog().info("Target: " + target);
        getLog().debug("Classpath: " + cp);
        getLog().info("Basedir: " + basedir.getAbsolutePath());
        if (target == null || cp == null || basedir == null) {
            getLog().info("Nothing to test");
            return;
        }

        runEvoSuiteOnSeparatedProcess(target, cp, basedir.getAbsolutePath());

    }

    /**
     * Old, unprefixed property names, still sent by released versions of the IntelliJ plugin.
     */
    private void applyLegacyProperties() throws MojoExecutionException {
        String value;
        try {
            if ((value = legacyProperty("evosuite.memoryInMB", "memoryInMB")) != null) {
                memoryInMB = Integer.parseInt(value);
            }
            if ((value = legacyProperty("evosuite.cores", "cores")) != null) {
                numberOfCores = Integer.parseInt(value);
            }
            if ((value = legacyProperty("evosuite.timeInMinutesPerClass", "timeInMinutesPerClass")) != null) {
                timeInMinutesPerClass = Integer.parseInt(value);
            }
            if ((value = legacyProperty("evosuite.timeInMinutesPerProject", "timeInMinutesPerProject")) != null) {
                timeInMinutesPerProject = Integer.parseInt(value);
            }
            if ((value = legacyProperty("evosuite.spawnManagerPort", "spawnManagerPort")) != null) {
                spawnManagerPort = Integer.valueOf(value);
            }
        } catch (NumberFormatException e) {
            throw new MojoExecutionException("Invalid number: " + e.getMessage(), e);
        }
        if ((value = legacyProperty("evosuite.cuts", "cuts")) != null) {
            cuts = value;
        }
        if ((value = legacyProperty("evosuite.cutsFile", "cutsFile")) != null) {
            cutsFile = value;
        }
        if ((value = legacyProperty("evosuite.criterion", "criterion")) != null) {
            criterion = value;
        }
        if ((value = legacyProperty("evosuite.extraArgs", "extraArgs")) != null) {
            extraArgs = value;
        }
        if ((value = legacyProperty("evosuite.schedule", "schedule")) != null) {
            schedule = value;
        }
        if ((value = legacyProperty("evosuite.testFolder", "targetFolder")) != null) {
            testFolder = value;
        }
    }

    private String addPathIfExists(String cp, String element, Set<String> alreadyExist) {
        File file = new File(element);
        if (!file.exists()) {
            /*
             * don't add to CP an element that does not exist
             */
            return cp;
        }

        if (alreadyExist.contains(element)) {
            return cp;
        }

        alreadyExist.add(element);

        if (cp == null) {
            cp = element;
        } else {
            cp = cp + File.pathSeparator + element;
        }
        return cp;
    }

    private void runEvoSuiteOnSeparatedProcess(String target, String cp, String dir)
            throws MojoExecutionException, MojoFailureException {

        List<String> params = new ArrayList<>();
        params.add("-continuous");
        params.add("execute");
        params.add("-target");
        params.add(target);
        params.add("-Dcriterion=" + criterion);
        params.add("-Dctg_schedule=" + schedule);
        params.add("-Dctg_memory=" + memoryInMB);
        params.add("-Dctg_cores=" + numberOfCores);

        if (timeInMinutesPerProject != 0) {
            params.add("-Dctg_time=" + timeInMinutesPerProject);
            // the minimum per class is given in whole minutes
            int minMinutes = secondsPerClass != null ? Math.max(1, (secondsPerClass + 59) / 60) : timeInMinutesPerClass;
            params.add("-Dctg_min_time_per_job=" + minMinutes);
        } else if (secondsPerClass != null) {
            params.add("-Dctg_seconds_per_class=" + secondsPerClass);
        } else {
            // there is no time limit, so test all classes X minutes
            params.add("-Dctg_time_per_class=" + timeInMinutesPerClass);
        }
        if (cuts != null) {
            params.add("-Dctg_selected_cuts=" + cuts);
        }
        if (cutsFile != null) {
            params.add("-Dctg_selected_cuts_file_location=" + cutsFile);
        }
        if (cuts == null && cutsFile == null && hasClassFilter()) {
            String selected = writeFilteredClassesToFile();
            if (selected == null) {
                getLog().info("No class matches classIncludes/classExcludes, nothing to test");
                return;
            }
            params.add("-Dctg_selected_cuts_file_location=" + selected);
            // a filter, not a request to test these classes again: unchanged ones are still skipped
            params.add("-Dctg_selected_cuts_are_filter=true");
        }

        File exportFolder = GeneratedTests.resolveFolder(project.getBasedir(), testFolder);
        params.add("-Dctg_export_folder=" + exportFolder.getAbsolutePath());

        List<String> unknown = JobProperties.unknownNames(evosuiteProperties);
        if (!unknown.isEmpty()) {
            throw new MojoExecutionException("evosuiteProperties contains properties EvoSuite does not have: "
                    + String.join(", ", unknown));
        }
        for (String retired : JobProperties.retiredNames(evosuiteProperties)) {
            getLog().warn("evosuiteProperties contains " + retired + ", which EvoSuite no longer has; it is ignored");
        }
        if (evosuiteProperties != null && evosuiteProperties.containsKey(JobProperties.LLM_API_KEY)) {
            getLog().warn("evosuiteProperties contains " + JobProperties.LLM_API_KEY
                    + ", which ends up on the command line of every test generation process;"
                    + " use llmApiKeyServerId or the " + JobProperties.LLM_API_KEY_VARIABLE
                    + " environment variable instead");
        }
        try {
            File properties = JobProperties.write(JobProperties.forJobs(
                    new File(dir, Properties.CTG_DIR), withDetectedTestFormat(evosuiteProperties)));
            params.add("-Dctg_extra_properties_file=" + properties.getAbsolutePath());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write the EvoSuite properties: " + e.getMessage(), e);
        }
        Map<String, String> environment = new HashMap<>();
        String apiKey = resolveLlmApiKey(settings, settingsDecrypter, llmApiKeyServerId);
        if (apiKey != null) {
            environment.put(JobProperties.LLM_API_KEY_VARIABLE, apiKey);
        }

        if (extraArgs != null && !extraArgs.isEmpty()) {

            String args = "";

            //note this does not for properly for parameters with strings using spaces
            String[] tokens = extraArgs.split(" ");
            for (String token : tokens) {
                token = token.trim();
                if (token.isEmpty()) {
                    continue;
                }
                if (!token.startsWith("-D")) {
                    getLog().error("Invalid extra argument \"" + token + "\". It should start with a -D");
                } else {
                    args += " " + token;
                }
            }

            params.add("-Dctg_extra_args=\"" + args + "\"");
        }

        String path = writeClasspathToFile(cp);
        params.add("-DCP_file_path=" + path);
        //params.add("-DCP=" + cp); //this did not work properly on Windows

        EvoSuiteRunner runner = createRunner();
        boolean ok;
        try (SpawnProcessManagerLease lease = SpawnProcessManagerLease.acquire(spawnManagerPort)) {
            params.add("-Dspawn_process_manager_port=" + lease.getPort());
            ok = runner.runEvoSuite(dir, params, environment);
        } catch (IllegalStateException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        if (!ok) {
            throw new MojoFailureException("Failed to correctly execute EvoSuite");
        }
        getLog().info("Generated tests are in " + exportFolder);

        if (deleteStaleTests) {
            removeStaleTests(exportFolder);
        }
    }

    private void removeStaleTests(File folder) throws MojoExecutionException {
        try {
            for (Path stale : GeneratedTests.findStaleTests(folder,
                    new File(project.getBuild().getOutputDirectory()))) {
                Files.delete(stale);
                getLog().info("Deleted test for a class that no longer exists: " + stale);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to delete outdated generated tests: " + e.getMessage(), e);
        }
    }

    /**
     * The LLM API key stored as the password of the server {@code llmApiKeyServerId} in settings.xml.
     *
     * @return the key, or {@code null} if no server is configured
     */
    static String resolveLlmApiKey(Settings settings, SettingsDecrypter settingsDecrypter, String llmApiKeyServerId)
            throws MojoExecutionException {
        if (llmApiKeyServerId == null || llmApiKeyServerId.trim().isEmpty()) {
            return null;
        }
        Server server = settings == null ? null : settings.getServer(llmApiKeyServerId.trim());
        if (server == null) {
            throw new MojoExecutionException("llmApiKeyServerId is '" + llmApiKeyServerId
                    + "', but settings.xml has no <server> with that <id>");
        }
        SettingsDecryptionResult result =
                settingsDecrypter.decrypt(new DefaultSettingsDecryptionRequest(server));
        if (!result.getProblems().isEmpty()) {
            throw new MojoExecutionException("Cannot decrypt the password of server '" + llmApiKeyServerId
                    + "' in settings.xml: " + result.getProblems().get(0).getMessage());
        }
        String key = result.getServer().getPassword();
        if (key == null || key.trim().isEmpty()) {
            throw new MojoExecutionException("Server '" + llmApiKeyServerId
                    + "' in settings.xml has no <password> holding the LLM API key");
        }
        return key.trim();
    }

    /**
     * EvoSuite writes JUnit 5 tests unless told otherwise. A project that only has JUnit 4 gets JUnit 4 tests,
     * unless the configuration names a test format itself.
     */
    private Map<String, String> withDetectedTestFormat(Map<String, String> configured) {
        Map<String, String> properties = new LinkedHashMap<>();
        String format = GenerationSettings.testFormatFor(project.getArtifacts());
        if (format != null && (configured == null || !configured.containsKey("test_format"))) {
            getLog().info("Generating JUnit 4 tests, as the project has JUnit 4 but no junit-jupiter-api"
                    + " test dependency");
            properties.put("test_format", format);
        }
        if (configured != null) {
            properties.putAll(configured);
        }
        return properties;
    }

    private boolean hasClassFilter() {
        return (classIncludes != null && !classIncludes.isEmpty())
                || (classExcludes != null && !classExcludes.isEmpty());
    }

    /**
     * The classes to test, as selected by {@code classIncludes} and {@code classExcludes}, written to a
     * file as CTG reads them.
     *
     * @return the file, or {@code null} if no class is selected
     */
    private String writeFilteredClassesToFile() throws MojoExecutionException {
        File classes = new File(project.getBuild().getOutputDirectory());
        String[] include = classIncludes == null || classIncludes.isEmpty()
                ? new String[]{"**/*.class"} : toClassPatterns(classIncludes);
        String[] exclude = classExcludes == null ? null : toClassPatterns(classExcludes);
        List<String> names = new ArrayList<>();
        for (File file : FileUtils.scan(classes, include, exclude)) {
            String relative = classes.toPath().relativize(file.toPath()).toString();
            if (relative.endsWith(".class")) {
                names.add(relative.substring(0, relative.length() - ".class".length())
                        .replace(File.separatorChar, '.'));
            }
        }
        getLog().info("Selected " + names.size() + " classes with classIncludes/classExcludes");
        if (names.isEmpty()) {
            return null;
        }
        try {
            File file = File.createTempFile("EvoSuite_cuts", ".txt");
            file.deleteOnExit();
            Files.write(file.toPath(), Collections.singletonList(String.join(",", names)), StandardCharsets.UTF_8);
            return file.getAbsolutePath();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write the selected classes: " + e.getMessage(), e);
        }
    }

    /**
     * A pattern given for a package or for a class without extension should still match the class files.
     */
    private static String[] toClassPatterns(List<String> patterns) {
        return patterns.stream()
                .map(p -> p.endsWith("/**") || p.endsWith(".class") || p.endsWith("*") ? p : p + ".class")
                .toArray(String[]::new);
    }

    private String writeClasspathToFile(String classpath) {

        try {
            File file = File.createTempFile("EvoSuite_classpathFile", ".txt");
            file.deleteOnExit();

            BufferedWriter out = new BufferedWriter(new FileWriter(file));
            String line = classpath;
            out.write(line);
            out.newLine();
            out.close();

            return file.getAbsolutePath();

        } catch (Exception e) {
            throw new IllegalStateException("Failed to create tmp file for classpath specification: " + e.getMessage());
        }
    }
}
