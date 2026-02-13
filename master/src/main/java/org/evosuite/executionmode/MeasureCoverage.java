/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.executionmode;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.TimeController;
import org.evosuite.classpath.ResourceList;
import org.evosuite.instrumentation.BytecodeInstrumentation;
import org.evosuite.rmi.MasterServices;
import org.evosuite.rmi.service.ClientNodeRemote;
import org.evosuite.rmi.service.MasterNodeLocal;
import org.evosuite.runtime.util.JavaExecCmdUtil;
import org.evosuite.statistics.SearchStatistics;
import org.evosuite.utils.ExternalProcessGroupHandler;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Measure coverage on existing test cases.
 */
public class MeasureCoverage {

    private static final Logger logger = LoggerFactory.getLogger(MeasureCoverage.class);

    public static final String NAME = "measureCoverage";

    /**
     * Returns the command line option for this execution mode.
     *
     * @return the command line option
     */
    public static Option getOption() {
        return new Option(NAME, "measure coverage on existing test cases");
    }

    /**
     * Executes the coverage measurement mode.
     *
     * @param options the command line options
     * @param javaOpts the java options
     * @param line the command line
     * @return the search statistics instance
     */
    public static Object execute(Options options, List<String> javaOpts,
                                 CommandLine line) {
        if (line.hasOption("class")) {
            measureCoverageClass(line.getOptionValue("class"), javaOpts);
        } else if (line.hasOption("target")) {
            measureCoverageTarget(line.getOptionValue("target"), javaOpts);
        } else {
            LoggingUtils.getEvoLogger().error("Please specify target class ('-class' option)");
            Help.execute(options);
        }
        return SearchStatistics.getInstance();
    }

    /**
     * Measures coverage for a single class.
     *
     * @param targetClass the target class name
     * @param args the command line arguments
     */
    private static void measureCoverageClass(String targetClass, List<String> args) {

        if (!ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                .hasClass(targetClass)) {
            LoggingUtils.getEvoLogger().error("* Unknown class: " + targetClass
                    + ". Be sure its full qualifying name is correct and the classpath is properly set "
                    + "with '-projectCP'");
        }

        if (!BytecodeInstrumentation.checkIfCanInstrument(targetClass)) {
            throw new IllegalArgumentException(
                    "Cannot consider "
                            + targetClass
                            + " because it belongs to one of the packages EvoSuite cannot currently handle");
        }

        measureCoverage(targetClass, args);
    }

    /**
     * Measures coverage for a target (class or package).
     *
     * @param target the target name
     * @param args the command line arguments
     */
    private static void measureCoverageTarget(String target, List<String> args) {

        Set<String> classes = ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                .getAllClasses(target, false);
        LoggingUtils.getEvoLogger().info("* Found " + classes.size() + " matching classes in target " + target);

        measureCoverage(target, args);
    }

    /**
     * Helper to measure coverage.
     *
     * @param targetClass the target class or prefix
     * @param args the command line arguments
     */
    private static void measureCoverage(String targetClass, List<String> args) {

        ExecutionModeUtils.ClasspathInfo classpathInfo = ExecutionModeUtils.getClasspathInfo();
        String classPath = classpathInfo.combinedClasspath;
        String projectCP = classpathInfo.projectClasspath;

        ExternalProcessGroupHandler handler = ExecutionModeUtils.createSingleClientHandler();
        handler.setAllowDoneAsFinished(true);
        int port = ExecutionModeUtils.openServer(handler);
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(JavaExecCmdUtil.getJavaBinExecutablePath(true)/*EvoSuite.JAVA_CMD*/);
        cmdLine.add("-cp");
        cmdLine.add(classPath);
        ExecutionModeUtils.addProcessCommunicationPort(cmdLine, port);
        if (Properties.HEADLESS_MODE) {
            ExecutionModeUtils.addHeadlessMode(cmdLine);
        }
        ExecutionModeUtils.addLogbackConfiguration(cmdLine);
        ExecutionModeUtils.addJavaLibraryPath(cmdLine);
        ExecutionModeUtils.addCommonModuleOpens(cmdLine);
        ExecutionModeUtils.addCpProperty(cmdLine, projectCP.isEmpty() ? classPath : projectCP);

        ExecutionModeUtils.addArgsExcludingCpProperty(cmdLine, args);

        ExecutionModeUtils.addTargetClassProperty(cmdLine, targetClass);
        cmdLine.add("-Djunit=" + Properties.JUNIT);
        ExecutionModeUtils.addProjectPrefixPropertyIfPresent(cmdLine);
        ExecutionModeUtils.addClassloaderProperty(cmdLine);
        ExecutionModeUtils.addClientMainClass(cmdLine);

        /*
         * TODO: here we start the client with several properties that are set through -D. These properties are not
         * visible to the master process (ie this process), when we access the Properties file. At the moment, we only
         * need few parameters, so we can hack them
         */
        ExecutionModeUtils.applyClientProperties(targetClass, port);

        LoggingUtils logUtils = ExecutionModeUtils.configureRemoteLoggingIfNeeded(cmdLine, logger);
        if (!Properties.CLIENT_ON_THREAD && logUtils == null) {
            return;
        }

        String[] newArgs = cmdLine.toArray(new String[cmdLine.size()]);
        ExecutionModeUtils.addTargetProjectClasspathElementsToSystem();

        handler.setBaseDir(EvoSuite.base_dir_path);
        if (handler.startProcess(newArgs)) {
            Set<ClientNodeRemote> clients = null;
            try {
                MasterNodeLocal master = MasterServices.getInstance().getMasterNode();
                if (master == null) {
                    logger.error("Master node is not available");
                } else {
                    Map<String, ClientNodeRemote> clientMap = master.getClientsOnceAllConnected(10000);
                    if (clientMap == null) {
                        logger.error("Not possible to access to clients");
                    } else {
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
                 * So now we just need to tell them to start a search
                 */
                for (ClientNodeRemote client : clients) {
                    try {
                        client.doCoverageAnalysis();
                    } catch (RemoteException e) {
                        logger.error("Error in starting clients", e);
                    }
                }
                int time = TimeController.getInstance().calculateForHowLongClientWillRunInSeconds();
                handler.waitForResult(time * 1000);
            }
            // timeout plus
            // 100 seconds?
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // ignored
            }
            if (Properties.NEW_STATISTICS) {
                if (MasterServices.getInstance().getMasterNode() == null) {
                    logger.error("Cannot write results as RMI master node is not running");
                } else {
                    LoggingUtils.getEvoLogger().info("* Writing statistics");

                    SearchStatistics.getInstance().writeStatisticsForAnalysis();
                }
            }
            handler.killProcess();

        } else {
            LoggingUtils.getEvoLogger().info("* Could not connect to client process");
        }

        handler.closeServer();

        if (logUtils != null) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // ignored
            }
            logUtils.closeLogServer();
        }

    }
}
