package org.evosuite.executionmode;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.apache.commons.lang3.SystemUtils;
import org.evosuite.ClientProcess;
import org.evosuite.Properties;
import org.evosuite.classpath.ClassPathHacker;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.ExternalProcessGroupHandler;
import org.slf4j.Logger;

final class ExecutionModeUtils {

    private ExecutionModeUtils() {
    }

    static void addProcessCommunicationPort(List<String> cmdLine, int port) {
        cmdLine.add("-Dprocess_communication_port=" + port);
    }

    static void addHeadlessMode(List<String> cmdLine) {
        cmdLine.add("-Djava.awt.headless=true");
    }

    static void addLogbackConfiguration(List<String> cmdLine) {
        cmdLine.add("-Dlogback.configurationFile=" + LoggingUtils.getLogbackFileName());
    }

    static void addJavaLibraryPath(List<String> cmdLine) {
        cmdLine.add("-Djava.library.path=lib");
    }

    static void addArgsExcludingCpProperty(List<String> cmdLine, List<String> args) {
        for (String arg : args) {
            if (!arg.startsWith("-DCP=")) {
                cmdLine.add(arg);
            }
        }
    }

    static void addCpProperty(List<String> cmdLine, String cp) {
        cmdLine.add("-DCP=" + cp);
    }

    static void addTargetClassProperty(List<String> cmdLine, String targetClass) {
        cmdLine.add("-DTARGET_CLASS=" + targetClass);
    }

    static void addProjectPrefixPropertyIfPresent(List<String> cmdLine) {
        if (Properties.PROJECT_PREFIX != null) {
            cmdLine.add("-DPROJECT_PREFIX=" + Properties.PROJECT_PREFIX);
        }
    }

    static void addClassloaderProperty(List<String> cmdLine) {
        cmdLine.add("-Dclassloader=true");
    }

    static void addClientMainClass(List<String> cmdLine) {
        cmdLine.add(ClientProcess.class.getName());
    }

    static ExternalProcessGroupHandler createSingleClientHandler() {
        return new ExternalProcessGroupHandler();
    }

    static ExternalProcessGroupHandler createParallelClientHandler(int clients) {
        return new ExternalProcessGroupHandler(clients);
    }

    static int openServer(ExternalProcessGroupHandler handler) {
        return handler.openServer();
    }

    static ClasspathInfo getClasspathInfo() {
        String evoSuiteClassPath = ClassPathHandler.getInstance().getEvoSuiteClassPath();
        String projectClasspath = ClassPathHandler.getInstance().getTargetProjectClasspath();
        String combinedClasspath = buildClassPathForTargetProject(evoSuiteClassPath, projectClasspath);
        return new ClasspathInfo(evoSuiteClassPath, projectClasspath, combinedClasspath);
    }

    static String buildClassPathForTargetProject(String evoSuiteClassPath, String projectClasspath) {
        if (evoSuiteClassPath == null || evoSuiteClassPath.isEmpty()) {
            return projectClasspath;
        }
        if (projectClasspath == null || projectClasspath.isEmpty()) {
            return evoSuiteClassPath;
        }
        return evoSuiteClassPath + File.pathSeparator + projectClasspath;
    }

    static void ensureInstrumentableTargetClass(String targetClass) {
        if (!org.evosuite.instrumentation.BytecodeInstrumentation.checkIfCanInstrument(targetClass)) {
            throw new IllegalArgumentException(
                    "Cannot consider "
                            + targetClass
                            + " because it belongs to one of the packages EvoSuite cannot currently handle");
        }
    }

    static void applyClientProperties(String targetClass, int port) {
        Properties.getInstance(); // should force the load, just to be sure
        Properties.TARGET_CLASS = targetClass;
        Properties.PROCESS_COMMUNICATION_PORT = port;
    }

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

    static void addTargetProjectClasspathElementsToSystem() {
        for (String entry : ClassPathHandler.getInstance().getClassPathElementsForTargetProject()) {
            try {
                ClassPathHacker.addFile(entry);
            } catch (IOException e) {
                LoggingUtils.getEvoLogger().info("* Error while adding classpath entry: " + entry);
            }
        }
    }

    static void addCommonModuleOpens(List<String> cmdLine) {
        if (getJavaMajorVersion() < 9) {
            return;
        }
        // Module access flags needed for Java 9+ to allow reflective access
        addOpen(cmdLine, "java.base/java.util=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/java.lang=ALL-UNNAMED");
        addOpen(cmdLine, "java.base/java.net=ALL-UNNAMED");
        addOpen(cmdLine, "java.desktop/java.awt=ALL-UNNAMED");
    }

    private static void addOpen(List<String> cmdLine, String value) {
        cmdLine.add("--add-opens");
        cmdLine.add(value);
    }

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

    static final class ClasspathInfo {
        final String evoSuiteClassPath;
        final String projectClasspath;
        final String combinedClasspath;

        private ClasspathInfo(String evoSuiteClassPath, String projectClasspath, String combinedClasspath) {
            this.evoSuiteClassPath = evoSuiteClassPath;
            this.projectClasspath = projectClasspath;
            this.combinedClasspath = combinedClasspath;
        }
    }
}
