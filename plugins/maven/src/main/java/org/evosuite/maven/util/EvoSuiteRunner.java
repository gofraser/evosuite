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
package org.evosuite.maven.util;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;
import org.evosuite.EvoSuite;
import org.evosuite.executionmode.ExecutionModeUtils;
import org.evosuite.runtime.util.JavaExecCmdUtil;
import org.evosuite.utils.LoggingUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Note: we cannot call EvoSuite directly on same JVM, like the following.
 *
 * <p><code>
 * ContinuousTestGeneration ctg = new ContinuousTestGeneration(target,cp,prefix,conf);
 * ctg.execute();
 * </code></p>
 *
 * <p>Reason is that Maven uses its own classloaders, and setting their classpath
 * becomes very messy, if possible at all.
 * So, we need to call EvoSuite on separated process.</p>
 */
public class EvoSuiteRunner {

    private static final long OUTPUT_DRAIN_TIMEOUT_MS = 10_000;

    private static final long VERSION_PROBE_TIMEOUT_S = 30;

    private static final String SPEC_VERSION_PROPERTY = "java.specification.version";

    private static final String EVOSUITE_GROUP_ID = "org.evosuite";

    private static final String MASTER = "evosuite-master";

    private static final List<String> CONTAINED_IN_MASTER = Arrays.asList("evosuite-client", "evosuite-runtime");

    /**
     * The maven logger of the plugin.
     */
    private final Log logger;

    /**
     * The resolved dependencies of the plugin, which include EvoSuite itself.
     */
    private final List<Artifact> artifacts;

    /**
     * The java executable to run EvoSuite with, eg from a toolchain, or {@code null} for the default one.
     */
    private final String javaExecutable;

    /**
     * Initializes the EvoSuiteRunner.
     *
     * @param logger         the Maven logger
     * @param artifacts      the resolved dependencies of the plugin
     * @param javaExecutable the java executable to use, or {@code null} for the one Maven runs on
     */
    public EvoSuiteRunner(Log logger, List<Artifact> artifacts, String javaExecutable) {
        this.logger = logger;
        this.artifacts = artifacts;
        this.javaExecutable = javaExecutable;
    }

    /**
     * Run EvoSuite in a separate process. The process is destroyed if the JVM exits while it runs.
     * This is blocking.
     *
     * @param dir    The directory to run in.
     * @param params Parameters to pass to EvoSuite.
     * @return True if successful.
     */
    public boolean runEvoSuite(String dir, List<String> params) {
        return runEvoSuite(dir, params, Collections.emptyMap());
    }

    /**
     * Run EvoSuite in a separate process, with further environment variables. The processes EvoSuite
     * spawns inherit them, which makes the environment the way to hand them secrets such as API keys:
     * unlike arguments, it is not visible in process listings or logged.
     *
     * @param dir         The directory to run in.
     * @param params      Parameters to pass to EvoSuite.
     * @param environment Variables to add to the environment of the process.
     * @return True if successful.
     */
    public boolean runEvoSuite(String dir, List<String> params, Map<String, String> environment) {
        List<String> cmd = getCommandToRunEvoSuite();
        cmd.addAll(params);
        return runProcess(dir, cmd, environment);
    }

    /**
     * We run the EvoSuite that is provided with the plugin: Maven has already resolved
     * the plugin's dependencies, and these include EvoSuite and everything it needs.
     *
     * @return The command to run.
     */
    List<String> getCommandToRunEvoSuite() {

        String cp = getEvoSuiteClasspath(artifacts);
        logger.debug("EvoSuite classpath: " + cp);

        String java = getJavaExecutable();

        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        ExecutionModeUtils.addCommonModuleOpens(cmd, getJavaMajorVersion());
        cmd.add("-D" + LoggingUtils.USE_DIFFERENT_LOGGING_XML_PARAMETER + "=logback-ctg-entry.xml");
        cmd.add("-Dlogback.configurationFile=logback-ctg-entry.xml");
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add(EvoSuite.class.getName());
        return cmd;
    }

    /**
     * The master jar is shaded: it contains the client and the runtime, with their dependencies such as
     * ASM relocated. The plain client and runtime jars, which the plugin itself compiles against, must not
     * come before it, or classes compiled against the relocated ASM meet the original one and fail to
     * verify. So the master jar goes first, and the jars it already contains are left out.
     *
     * @param artifacts the resolved dependencies of the plugin
     * @return the classpath to run EvoSuite with
     */
    static String getEvoSuiteClasspath(List<Artifact> artifacts) {
        List<String> master = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            if (artifact.getFile() == null) {
                continue;
            }
            String path = artifact.getFile().getAbsolutePath();
            if (!EVOSUITE_GROUP_ID.equals(artifact.getGroupId())) {
                others.add(path);
            } else if (MASTER.equals(artifact.getArtifactId())) {
                master.add(path);
            } else if (!CONTAINED_IN_MASTER.contains(artifact.getArtifactId())) {
                others.add(path);
            }
        }
        if (master.isEmpty()) {
            throw new IllegalStateException("The plugin's dependencies do not include " + MASTER);
        }
        master.addAll(others);
        return String.join(File.pathSeparator, master);
    }

    private String getJavaExecutable() {
        return javaExecutable != null ? javaExecutable : JavaExecCmdUtil.getJavaBinExecutablePath();
    }

    /**
     * The JDK whose java executable is used, so that the processes EvoSuite spawns use the same one.
     *
     * @return the JDK home, or {@code null} if the default java executable is used
     */
    private String getToolchainJavaHome() {
        if (javaExecutable == null) {
            return null;
        }
        File bin = new File(javaExecutable).getAbsoluteFile().getParentFile();
        return bin == null || bin.getParentFile() == null ? null : bin.getParentFile().getAbsolutePath();
    }

    /**
     * The module flags EvoSuite gets depend on the version of the JVM that runs it. That is the one
     * Maven runs on unless a toolchain selected another one, whose version has to be asked for.
     */
    private int getJavaMajorVersion() {
        int running = ExecutionModeUtils.parseJavaMajorVersion(System.getProperty(SPEC_VERSION_PROPERTY));
        if (javaExecutable == null) {
            return running;
        }
        Integer probed = probeJavaMajorVersion(javaExecutable);
        if (probed == null) {
            logger.warn("Could not determine the version of " + javaExecutable
                    + ", assuming the one Maven runs on (" + running + ")");
            return running;
        }
        return probed;
    }

    private Integer probeJavaMajorVersion(String java) {
        File output = null;
        try {
            output = File.createTempFile("EvoSuite_java_version", ".txt");
            // to a file, not a pipe: reading a pipe would block past the timeout if the JVM hangs
            Process probe = new ProcessBuilder(java, "-XshowSettings:properties", "-version")
                    .redirectErrorStream(true).redirectOutput(output).start();
            if (!probe.waitFor(VERSION_PROBE_TIMEOUT_S, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                return null;
            }
            for (String line : Files.readAllLines(output.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith(SPEC_VERSION_PROPERTY)) {
                    return ExecutionModeUtils.parseJavaMajorVersion(
                            trimmed.substring(trimmed.indexOf('=') + 1).trim());
                }
            }
        } catch (IOException e) {
            logger.debug("Failed to determine the version of " + java + ": " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (output != null && !output.delete()) {
                output.deleteOnExit();
            }
        }
        return null;
    }

    private boolean runProcess(String baseDir, List<String> cmd, Map<String, String> environment) {

        if (baseDir == null) {
            baseDir = System.getProperty("user.dir");
        }

        logger.debug("Working directory: " + baseDir);
        if (logger.isDebugEnabled()) {
            logger.debug("Going to execute command: " + String.join(" ", cmd));
        }

        ProcessBuilder builder = new ProcessBuilder(cmd);
        builder.directory(new File(baseDir));
        builder.redirectErrorStream(true);
        builder.environment().putAll(environment);
        String toolchainHome = getToolchainJavaHome();
        if (toolchainHome != null) {
            // the jobs EvoSuite spawns take their java executable from JAVA_HOME first
            builder.environment().put("JAVA_HOME", toolchainHome);
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            logger.error("Failed to start EvoSuite: " + e.getMessage(), e);
            return false;
        }

        // a hook per run, removed again when the run ends, so that repeated executions
        // (eg one per module in a reactor build) do not accumulate hooks
        Thread killer = new Thread(process::destroy, "EvoSuite process killer");
        Runtime.getRuntime().addShutdownHook(killer);
        Thread reader = startOutputReader(process, logger);

        try {
            int exitCode = process.waitFor();
            // the process has ended, so the reader reaches end-of-stream and stops. Bounded, in
            // case a leftover descendant still holds the pipe open
            reader.join(OUTPUT_DRAIN_TIMEOUT_MS);

            if (exitCode != 0) {
                logger.error("Error in EvoSuite");
                return false;
            }
            logger.debug("EvoSuite terminated");
            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try {
                //be sure streamers are closed, otherwise process might hang on Windows
                process.getOutputStream().close();
                process.getInputStream().close();
                process.getErrorStream().close();
            } catch (Exception t) {
                logger.error("Failed to close process stream: " + t);
            }
            process.destroy();
            return false;
        } finally {
            removeShutdownHook(killer);
        }
    }

    private void removeShutdownHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // the JVM is already shutting down, and the hook will run anyway
        }
    }

    /**
     * Copy the process output to the Maven log until the process closes its output.
     */
    static Thread startOutputReader(final Process process, final Log logger) {

        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (!line.isEmpty()) {
                        logger.info(line);
                    }
                }
            } catch (IOException e) {
                logger.debug("Exception while reading spawn process output: " + e);
            }
        }, "EvoSuite output reader");
        reader.setDaemon(true);
        reader.start();
        logger.debug("Started thread to read spawn process output");
        return reader;
    }
}
