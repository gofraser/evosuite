package org.evosuite.executionmode;

import org.apache.commons.lang3.SystemUtils;
import org.evosuite.ClientProcess;
import org.evosuite.Properties;
import org.evosuite.classpath.ClassPathHacker;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.utils.ExternalProcessGroupHandler;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Utility class for handling execution modes and command-line arguments.
 * <p>
 * This class provides static helper methods to configure the command-line arguments for
 * launching client processes, managing classpath configurations, and setting up
 * logging and system properties.
 * </p>
 */
final class ExecutionModeUtils {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ExecutionModeUtils() {
    }

    /**
     * Adds the process communication port property to the command line arguments.
     *
     * @param cmdLine The list of command line arguments to modify.
     * @param port    The port number to be used for process communication.
     */
    static void addProcessCommunicationPort(List<String> cmdLine, int port) {
        cmdLine.add("-Dprocess_communication_port=" + port);
    }

    /**
     * Adds the headless mode property to the command line arguments.
     *
     * @param cmdLine The list of command line arguments to modify.
     */
    static void addHeadlessMode(List<String> cmdLine) {
        cmdLine.add("-Djava.awt.headless=true");
    }

    /**
     * Adds the Logback configuration file property to the command line arguments.
     *
     * @param cmdLine The list of command line arguments to modify.
     */
    static void addLogbackConfiguration(List<String> cmdLine) {
        cmdLine.add("-Dlogback.configurationFile=" + LoggingUtils.getLogbackFileName());
    }

    /**
     * Adds the Java library path property to the command line arguments.
     *
     * @param cmdLine The list of command line arguments to modify.
     */
    static void addJavaLibraryPath(List<String> cmdLine) {
        cmdLine.add("-Djava.library.path=lib");
    }

    /**
     * Adds arguments from the source list to the command line, excluding the classpath property.
     *
     * @param cmdLine The list of command line arguments to modify.
     * @param args    The source list of arguments to filter and add.
     */
    static void addArgsExcludingCpProperty(List<String> cmdLine, List<String> args) {
        for (String arg : args) {
            if (!arg.startsWith("-DCP=")) {
                cmdLine.add(arg);
            }
        }
    }

    /**
     * Adds the classpath property to the command line arguments.
     *
     * @param cmdLine The list of command line arguments to modify.
     * @param cp      The classpath string to add.
     */
    static void addCpProperty(List<String> cmdLine, String cp) {
        cmdLine.add("-DCP=" + cp);
    }

    /**
     * Adds the target class property to the command line arguments.
     *
     * @param cmdLine     The list of command line arguments to modify.
     * @param targetClass The fully qualified name of the target class.
     */
    static void addTargetClassProperty(List<String> cmdLine, String targetClass) {
        cmdLine.add("-DTARGET_CLASS=" + targetClass);
    }

    /**
     * Adds the project prefix property to the command line arguments if it is present.
     *
     * @param cmdLine The list of command line arguments to modify.
     */
    static void addProjectPrefixPropertyIfPresent(List<String> cmdLine) {
        if (Properties.PROJECT_PREFIX != null) {
            cmdLine.add("-DPROJECT_PREFIX=" + Properties.PROJECT_PREFIX);
        }
    }

    /**
     * Adds the classloader property to the command line arguments.
     *
     * @param cmdLine The list of command line arguments to modify.
     */
    static void addClassloaderProperty(List<String> cmdLine) {
        cmdLine.add("-Dclassloader=true");
    }

    /**
     * Adds the client main class to the command line arguments.
     *
     * @param cmdLine The list of command line arguments to modify.
     */
    static void addClientMainClass(List<String> cmdLine) {
        cmdLine.add(ClientProcess.class.getName());
    }

    /**
     * Creates a handler for a single client process group.
     *
     * @return A new instance of {@link ExternalProcessGroupHandler}.
     */
    static ExternalProcessGroupHandler createSingleClientHandler() {
        return new ExternalProcessGroupHandler();
    }

    /**
     * Creates a handler for a parallel client process group with the specified number of clients.
     *
     * @param clients The number of client processes to handle.
     * @return A new instance of {@link ExternalProcessGroupHandler}.
     */
    static ExternalProcessGroupHandler createParallelClientHandler(int clients) {
        return new ExternalProcessGroupHandler(clients);
    }

    /**
     * Opens a server using the provided process group handler.
     *
     * @param handler The {@link ExternalProcessGroupHandler} to use for opening the server.
     * @return The port number on which the server was opened.
     */
    static int openServer(ExternalProcessGroupHandler handler) {
        return handler.openServer();
    }

    /**
     * Retrieves classpath information including EvoSuite classpath, project classpath, and the combined classpath.
     *
     * @return A {@link ClasspathInfo} object containing the classpath details.
     */
    static ClasspathInfo getClasspathInfo() {
        String evoSuiteClassPath = ClassPathHandler.getInstance().getEvoSuiteClassPath();
        String projectClasspath = ClassPathHandler.getInstance().getTargetProjectClasspath();
        String combinedClasspath = buildClassPathForTargetProject(evoSuiteClassPath, projectClasspath);
        return new ClasspathInfo(evoSuiteClassPath, projectClasspath, combinedClasspath);
    }

    /**
     * Builds the combined classpath string for the target project.
     *
     * @param evoSuiteClassPath The EvoSuite classpath.
     * @param projectClasspath  The target project classpath.
     * @return The combined classpath string, with elements separated by the system path separator.
     */
    static String buildClassPathForTargetProject(String evoSuiteClassPath, String projectClasspath) {
        if (evoSuiteClassPath == null || evoSuiteClassPath.isEmpty()) {
            return projectClasspath;
        }
        if (projectClasspath == null || projectClasspath.isEmpty()) {
            return evoSuiteClassPath;
        }
        return evoSuiteClassPath + File.pathSeparator + projectClasspath;
    }

    /**
     * Ensures that the target class belongs to a package that can be instrumented by EvoSuite.
     *
     * @param targetClass The fully qualified name of the target class.
     * @throws IllegalArgumentException if the target class belongs to an unhandled package.
     */
    static void ensureInstrumentableTargetClass(String targetClass) {
        if (!org.evosuite.instrumentation.BytecodeInstrumentation.checkIfCanInstrument(targetClass)) {
            throw new IllegalArgumentException(
                    "Cannot consider "
                            + targetClass
                            + " because it belongs to one of the packages EvoSuite cannot currently handle");
        }
    }

    /**
     * Applies the client properties for the target class and communication port.
     *
     * @param targetClass The fully qualified name of the target class.
     * @param port        The port number for process communication.
     */
    static void applyClientProperties(String targetClass, int port) {
        Properties.getInstance(); // should force the load, just to be sure
        Properties.TARGET_CLASS = targetClass;
        Properties.PROCESS_COMMUNICATION_PORT = port;
    }

    /**
     * Configures remote logging if needed and starts the log server.
     *
     * @param cmdLine The list of command line arguments to modify with logging properties.
     * @param logger  The logger instance to use for reporting errors.
     * @return The configured {@link LoggingUtils} instance, or {@code null} if remote logging is not needed
     *         or failed to start.
     */
    static LoggingUtils configureRemoteLoggingIfNeeded(List<String> cmdLine, Logger logger) {
        if (Properties.CLIENT_ON_THREAD) {
            return null;
        }
        LoggingUtils logUtils = new LoggingUtils();
        boolean logServerStarted = logUtils.startLogServer();
        if (!logServerStarted) {
            logger.error("Cannot start the log server");
            return null;
        }
        int logPort = logUtils.getLogServerPort();
        cmdLine.add(1, "-Dmaster_log_port=" + logPort);
        cmdLine.add(1, "-Devosuite.log.appender=CLIENT");
        return logUtils;
    }

    /**
     * Adds the classpath elements of the target project to the system class loader.
     */
    static void addTargetProjectClasspathElementsToSystem() {
        for (String entry : ClassPathHandler.getInstance().getClassPathElementsForTargetProject()) {
            try {
                ClassPathHacker.addFile(entry);
            } catch (IOException e) {
                LoggingUtils.getEvoLogger().info("* Error while adding classpath entry: " + entry);
            }
        }
    }

    /**
     * Adds module opens arguments for Java 9+ compatibility.
     *
     * @param cmdLine The list of command line arguments to modify.
     */
    static void addCommonModuleOpens(List<String> cmdLine) {
        if (getJavaMajorVersion() < 9) {
            return;
        }
        // Module access flags needed for Java 9+ to allow reflective access
        addOpen(cmdLine, "java.base/java.util=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/java.lang=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/java.net=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/java.util.regex=ALL-UNNAMED");
        addOpen(cmdLine, "java.desktop/java.awt=ALL-UNNAMED");
    }

    /**
     * Helper method to add the --add-opens argument to the command line.
     *
     * @param cmdLine The list of command line arguments to modify.
     * @param value   The value for the --add-opens argument.
     */
    private static void addOpen(List<String> cmdLine, String value) {
        cmdLine.add("--add-opens");
        cmdLine.add(value);
    }

    /**
     * Determines the major version of the running Java environment.
     *
     * @return The major Java version number (e.g., 8, 9, 11).
     */
    private static int getJavaMajorVersion() {
        String version = SystemUtils.JAVA_VERSION;
        if (version == null || version.isEmpty()) {
            return 8;
        }
        String normalized = version.startsWith("1.") ? version.substring(2) : version;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.length() == 0) {
            return 8;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    /**
     * Container class for classpath information.
     */
    static final class ClasspathInfo {
        /** The EvoSuite classpath. */
        final String evoSuiteClassPath;
        /** The target project classpath. */
        final String projectClasspath;
        /** The combined classpath. */
        final String combinedClasspath;

        /**
         * Constructs a new ClasspathInfo object.
         *
         * @param evoSuiteClassPath The EvoSuite classpath.
         * @param projectClasspath  The target project classpath.
         * @param combinedClasspath The combined classpath.
         */
        private ClasspathInfo(String evoSuiteClassPath, String projectClasspath, String combinedClasspath) {
            this.evoSuiteClassPath = evoSuiteClassPath;
            this.projectClasspath = projectClasspath;
            this.combinedClasspath = combinedClasspath;
        }
    }
}
