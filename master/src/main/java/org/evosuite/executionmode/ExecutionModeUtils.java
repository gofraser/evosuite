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
package org.evosuite.executionmode;

import org.apache.commons.lang3.SystemUtils;
import org.evosuite.ClientProcess;
import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.classpath.ClassPathHacker;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.rmi.MasterServices;
import org.evosuite.rmi.service.ClientNodeRemote;
import org.evosuite.rmi.service.MasterNodeLocal;
import org.evosuite.runtime.ReflectionAccess;
import org.evosuite.runtime.util.JavaExecCmdUtil;
import org.evosuite.utils.ExternalProcessGroupHandler;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Utility class for handling execution modes and command-line arguments.
 * <p>
 * This class provides static helper methods to configure the command-line arguments for
 * launching client processes, managing classpath configurations, and setting up
 * logging and system properties.
 * </p>
 */
public final class ExecutionModeUtils {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionModeUtils.class);

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ExecutionModeUtils() {
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
     * Forwards the log folder of the master to the client, if one is set (it is for example used in
     * logback-ctg.xml).
     *
     * @param cmdLine The list of command line arguments to modify.
     */
    static void addLogFolderPropertyIfPresent(List<String> cmdLine) {
        String logDir = System.getProperty("evosuite.log.folder");
        if (logDir != null) {
            cmdLine.add("-Devosuite.log.folder=" + logDir);
        }
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
     * Starts the command line of a client JVM: the java binary, the class path and the options
     * every client gets (communication port, headless mode, logging configuration, library path
     * and the module access flags).
     *
     * @param classPath The class path of the client process.
     * @param port      The port number for process communication.
     * @param headless  Whether to start the client in headless mode.
     * @return The command line, to be completed with the client's properties and main class.
     */
    static List<String> startClientCommand(String classPath, int port, boolean headless) {
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(JavaExecCmdUtil.getJavaBinExecutablePath(true)/*EvoSuite.JAVA_CMD*/);
        cmdLine.add("-cp");
        cmdLine.add(classPath);
        cmdLine.add("-Dprocess_communication_port=" + port);
        if (headless) {
            cmdLine.add("-Djava.awt.headless=true");
        }
        cmdLine.add("-Dlogback.configurationFile=" + LoggingUtils.getLogbackFileName());
        cmdLine.add("-Djava.library.path=lib");
        addCommonModuleOpens(cmdLine);
        return cmdLine;
    }

    /**
     * Builds the command line shared by the single-client launchers (dependency writing, statistics
     * printing and coverage measurement) and publishes the target class and port to this process.
     *
     * @param classPath     The class path of the client process.
     * @param cpProperty    The value of the {@code CP} property handed to the client.
     * @param headless      Whether to start the client in headless mode.
     * @param args          The master's java options, forwarded except for the {@code CP} property.
     * @param targetClass   The fully qualified name of the target class (or prefix).
     * @param extraProperty An additional {@code -D} property placed after the target class, or {@code null}.
     * @param port          The port number for process communication.
     * @return The command line, ready for remote logging to be appended.
     */
    static List<String> buildStandardClientCommand(String classPath, String cpProperty, boolean headless,
                                                   List<String> args, String targetClass,
                                                   String extraProperty, int port) {
        List<String> cmdLine = startClientCommand(classPath, port, headless);
        cmdLine.add("-DCP=" + cpProperty);

        addArgsExcludingCpProperty(cmdLine, args);

        addTargetClassProperty(cmdLine, targetClass);
        if (extraProperty != null) {
            cmdLine.add(extraProperty);
        }
        addProjectPrefixPropertyIfPresent(cmdLine);
        cmdLine.add("-Dclassloader=true");
        addClientMainClass(cmdLine);

        /*
         * TODO: here we start the client with several properties that are set through -D. These properties are not
         * visible to the master process (ie this process), when we access the Properties file. At the moment, we only
         * need few parameters, so we can hack them
         */
        applyClientProperties(targetClass, port);
        return cmdLine;
    }

    /**
     * A call made to every connected client once it has started.
     */
    interface ClientCall {
        void run(ClientNodeRemote client) throws RemoteException;
    }

    /**
     * Runs a single-client launcher (dependency writing, statistics printing or coverage
     * measurement): starts one client process, calls it, waits for it to finish and shuts the
     * client, the RMI server and the log server down again.
     *
     * @param classPath     The class path of the client process.
     * @param cpProperty    The value of the {@code CP} property handed to the client.
     * @param headless      Whether to start the client in headless mode.
     * @param args          The master's java options, forwarded except for the {@code CP} property.
     * @param targetClass   The fully qualified name of the target class (or prefix).
     * @param extraProperty An additional {@code -D} property placed after the target class, or {@code null}.
     * @param call          The call made to every connected client.
     * @param waitMillis    How long to wait for the clients to finish.
     * @param afterWait     Run once the wait is over and before the client is killed, or {@code null}.
     */
    static void runSingleClient(String classPath, String cpProperty, boolean headless, List<String> args,
                                String targetClass, String extraProperty, ClientCall call, int waitMillis,
                                Runnable afterWait) {
        ExternalProcessGroupHandler handler = new ExternalProcessGroupHandler();
        handler.setAllowDoneAsFinished(true);
        int port = handler.openServer();
        List<String> cmdLine = buildStandardClientCommand(classPath, cpProperty, headless, args, targetClass,
                extraProperty, port);

        LoggingUtils logUtils = configureRemoteLoggingIfNeeded(cmdLine, logger);
        if (!Properties.CLIENT_ON_THREAD && logUtils == null) {
            return;
        }

        String[] newArgs = cmdLine.toArray(new String[0]);
        addTargetProjectClasspathElementsToSystem();

        handler.setBaseDir(EvoSuite.base_dir_path);
        if (handler.startProcess(newArgs)) {
            Set<ClientNodeRemote> clients = null;
            try {
                MasterNodeLocal master = MasterServices.getInstance().getMasterNode();
                if (master == null) {
                    logger.error("Master node is not available");
                } else {
                    Map<String, ClientNodeRemote> clientMap = master.getClientsOnceAllConnected(10000);
                    if (clientMap != null) {
                        clients = new CopyOnWriteArraySet<>(clientMap.values());
                    }
                }
            } catch (InterruptedException e) {
                // ignored
            }
            if (clients == null) {
                logger.error("Not possible to access to clients");
            } else {
                /*
                 * The clients have started, and connected back to Master.
                 * So now we just need to tell them to start their work
                 */
                for (ClientNodeRemote client : clients) {
                    try {
                        call.run(client);
                    } catch (RemoteException e) {
                        logger.error("Error in starting clients", e);
                    }
                }
                handler.waitForResult(waitMillis);
            }
            sleepBriefly();
            if (afterWait != null) {
                afterWait.run();
            }
            handler.killProcess();
        } else {
            LoggingUtils.getEvoLogger().info("* Could not connect to client process");
        }

        handler.closeServer();

        if (logUtils != null) {
            sleepBriefly();
            logUtils.closeLogServer();
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // ignored
        }
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
     * Adds module opens and JDK-compatibility arguments for Java 9+.
     *
     * @param cmdLine The list of command line arguments to modify.
     */
    public static void addCommonModuleOpens(List<String> cmdLine) {
        addCommonModuleOpens(cmdLine, getJavaMajorVersion());
    }

    /**
     * Adds module opens and JDK-compatibility arguments for a JVM other than the running one,
     * eg one selected by a build tool's toolchain.
     *
     * @param cmdLine          The list of command line arguments to modify.
     * @param javaMajorVersion The major Java version of the JVM that will be launched.
     */
    public static void addCommonModuleOpens(List<String> cmdLine, int javaMajorVersion) {
        if (javaMajorVersion < 9) {
            return;
        }
        // Module access flags needed for Java 9+ to allow reflective access
        addOpen(cmdLine, "java.base/java.util=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/java.lang=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/java.lang.invoke=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/java.lang.reflect=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/java.lang.ref=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/java.io=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/java.nio=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/java.nio.channels.spi=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/java.net=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/java.security=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/java.text=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/java.util.random=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/java.util.regex=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/java.util.concurrent=ALL-UNNAMED");
        addOpen(cmdLine, "java.logging/java.util.logging=ALL-UNNAMED");
        addOpen(cmdLine, "java.prefs/java.util.prefs=ALL-UNNAMED");
        addOpen(cmdLine, "java.desktop/java.awt=ALL-UNNAMED");
        addOpen(cmdLine, "java.desktop/java.awt.font=ALL-UNNAMED");
        addOpen(cmdLine, "java.desktop/javax.imageio.metadata=ALL-UNNAMED");
        addOpen(cmdLine, "java.desktop/javax.swing.border=ALL-UNNAMED");
        addOpen(cmdLine, "java.desktop/javax.swing=ALL-UNNAMED");
        addOpen(cmdLine, "java.desktop/sun.java2d=ALL-UNNAMED");
        addOpen(cmdLine, "java.desktop/sun.awt=ALL-UNNAMED");
        // GuiSupport's PeerCreationGuardToolkit implements sun.awt.ComponentFactory
        // so that GUI peer-creation calls (createWindow/createFrame/createDialog/...)
        // throw a clean HeadlessException instead of reaching a real toolkit whose
        // native initialization never ran. Resolving that "implements" clause needs
        // the package exported, not just opened -- --add-opens alone is not
        // sufficient on every JDK build (verified empirically to vary by patch
        // version). GuiSupport degrades gracefully if this export is missing
        // (falls back to the pre-existing unwrapped-real-toolkit behavior), but the
        // guard is inert without it.
        addExport(cmdLine, "java.desktop/sun.awt=ALL-UNNAMED");
        // Internal JDK packages sometimes used by legacy dependencies
        addExport(cmdLine, "java.xml.crypto/org.jcp.xml.dsig.internal.dom=ALL-UNNAMED");
        addExport(cmdLine, "jdk.javadoc/jdk.javadoc.internal.api=ALL-UNNAMED");
        // Mockito/ByteBuddy can end up mocking javac internals when they flow through
        // CUT APIs; pre-export/open this package to avoid runtime module rewrite failures.
        addExport(cmdLine, "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED");
        addOpen(cmdLine, "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/sun.nio.ch=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/sun.security.util=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/sun.net=ALL-UNNAMED");
        addOpen(cmdLine, "jdk.unsupported/sun.misc=ALL-UNNAMED");

        // Enable ByteBuddy's experimental support for newer JDK versions.
        // Without this, ByteBuddy may generate classes with an unsupported class
        // file version or fail to handle module system changes on JDK 25+.
        // This matches the flag already set in the Maven test configuration.
        cmdLine.add("-Dnet.bytebuddy.experimental=true");

        // The SUT shares the unnamed module with the runtime, so it would inherit every
        // package opened above. ReflectionAccess hides those from the SUT's setAccessible
        // calls, but only those: whatever module access the user gave EvoSuite's own JVM
        // (on its command line or through JDK_JAVA_OPTIONS) is meant for the SUT, is
        // forwarded to the client and stays visible. A SUT that needs java.base/java.util
        // on JDK 17 in production needs it here too; a user who runs EvoSuite with
        // that --add-opens gets the same behaviour in the search and in the generated tests.
        List<String> userArgs = userModuleAccessArguments();
        Set<String> userGranted = userGrantedModuleAccess(userArgs);
        cmdLine.addAll(userArgs);
        Set<String> privateAccess = new LinkedHashSet<>();
        for (int i = 0; i + 1 < cmdLine.size(); i++) {
            if ("--add-opens".equals(cmdLine.get(i)) || "--add-exports".equals(cmdLine.get(i))) {
                String pair = modulePackage(cmdLine.get(i + 1));
                if (pair != null && !userGranted.contains(pair)) {
                    privateAccess.add(pair);
                }
            }
        }
        cmdLine.add("-D" + ReflectionAccess.PRIVATE_ACCESS_PROPERTY + "=" + String.join(",", privateAccess));
    }

    /**
     * The {@code module/package} pairs the user opened or exported to the unnamed module
     * when launching EvoSuite itself (command line or {@code JDK_JAVA_OPTIONS}, both of
     * which the launcher reports as input arguments).
     */
    static Set<String> userGrantedModuleAccess() {
        return userGrantedModuleAccess(userModuleAccessArguments());
    }

    private static Set<String> userGrantedModuleAccess(List<String> args) {
        Set<String> pairs = new LinkedHashSet<>();
        for (int i = 0; i + 1 < args.size(); i += 2) {
            String pair = modulePackage(args.get(i + 1));
            if (pair != null) {
                pairs.add(pair);
            }
        }
        return pairs;
    }

    /**
     * The {@code --add-opens}/{@code --add-exports} arguments this JVM was started with,
     * as flag/value pairs, restricted to those targeting the unnamed module.
     */
    static List<String> userModuleAccessArguments() {
        List<String> result = new ArrayList<>();
        List<String> input;
        try {
            input = ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (RuntimeException e) {
            return result;
        }
        // A master spawned by another EvoSuite process (continuous test generation,
        // coverage measurement) was started with EvoSuite's own flags; the list it
        // inherited says which of them are not the user's.
        Set<String> inheritedPrivate = new LinkedHashSet<>();
        String inherited = System.getProperty(ReflectionAccess.PRIVATE_ACCESS_PROPERTY, "");
        for (String pair : inherited.split(",")) {
            if (!pair.trim().isEmpty()) {
                inheritedPrivate.add(pair.trim());
            }
        }
        for (int i = 0; i < input.size(); i++) {
            String arg = input.get(i);
            String flag = null;
            String value = null;
            if ("--add-opens".equals(arg) || "--add-exports".equals(arg)) {
                if (i + 1 < input.size()) {
                    flag = arg;
                    value = input.get(++i);
                }
            } else if (arg.startsWith("--add-opens=") || arg.startsWith("--add-exports=")) {
                int eq = arg.indexOf('=');
                flag = arg.substring(0, eq);
                value = arg.substring(eq + 1);
            }
            if (flag != null && value.endsWith("=ALL-UNNAMED")
                    && !inheritedPrivate.contains(modulePackage(value))) {
                result.add(flag);
                result.add(value);
            }
        }
        return result;
    }

    /**
     * Maps {@code java.base/java.util=ALL-UNNAMED} to {@code java.base/java.util}, or null.
     */
    private static String modulePackage(String value) {
        int eq = value.indexOf('=');
        if (eq <= 0 || !value.substring(0, eq).contains("/")) {
            return null;
        }
        return value.substring(0, eq);
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
     * Helper method to add the --add-exports argument to the command line.
     *
     * @param cmdLine The list of command line arguments to modify.
     * @param value   The value for the --add-exports argument.
     */
    private static void addExport(List<String> cmdLine, String value) {
        cmdLine.add("--add-exports");
        cmdLine.add(value);
    }

    /**
     * Determines the major version of the running Java environment.
     *
     * @return The major Java version number (e.g., 8, 9, 11).
     */
    private static int getJavaMajorVersion() {
        return parseJavaMajorVersion(SystemUtils.JAVA_VERSION);
    }

    /**
     * Determines the major version from a Java version string, such as the value of the
     * {@code java.version} or {@code java.specification.version} property.
     *
     * @param version The version string (e.g., "1.8.0_352", "17.0.5", "21").
     * @return The major Java version number, or 8 if it cannot be determined.
     */
    public static int parseJavaMajorVersion(String version) {
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
