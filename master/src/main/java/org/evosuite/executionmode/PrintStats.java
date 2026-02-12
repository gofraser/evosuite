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
import org.evosuite.rmi.MasterServices;
import org.evosuite.rmi.service.ClientNodeRemote;
import org.evosuite.runtime.util.JavaExecCmdUtil;
import org.evosuite.utils.ExternalProcessGroupHandler;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class PrintStats {

    private static final Logger logger = LoggerFactory.getLogger(PrintStats.class);

    public static final String NAME = "printStats";

    public static Option getOption() {
        return new Option(NAME, "print class information (coverable goals)");
    }

    /**
     * Executes the statistics printing mode.
     *
     * @param options the command line options
     * @param javaOpts the java options
     * @param line the command line
     * @return null
     */
    public static Object execute(Options options, List<String> javaOpts,
                                 CommandLine line) {
        if (line.hasOption("class")) {
            printStats(line.getOptionValue("class"), javaOpts);
        } else {
            LoggingUtils.getEvoLogger().error("Please specify target class ('-class' option) to list class "
                    + "statistics");
            Help.execute(options);
        }
        return null;
    }

    private static void printStats(String targetClass, List<String> args) {
        ExecutionModeUtils.ensureInstrumentableTargetClass(targetClass);
        ExecutionModeUtils.ClasspathInfo classpathInfo = ExecutionModeUtils.getClasspathInfo();
        String classPath = classpathInfo.combinedClasspath;
        String cp = classpathInfo.projectClasspath;

        ExternalProcessGroupHandler handler = ExecutionModeUtils.createSingleClientHandler();
        handler.setAllowDoneAsFinished(true);
        int port = ExecutionModeUtils.openServer(handler);
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(JavaExecCmdUtil.getJavaBinExecutablePath(true)/*EvoSuite.JAVA_CMD*/);
        cmdLine.add("-cp");
        cmdLine.add(classPath);
        ExecutionModeUtils.addProcessCommunicationPort(cmdLine, port);
        ExecutionModeUtils.addHeadlessMode(cmdLine);
        ExecutionModeUtils.addLogbackConfiguration(cmdLine);
        ExecutionModeUtils.addJavaLibraryPath(cmdLine);
        ExecutionModeUtils.addCommonModuleOpens(cmdLine);
        ExecutionModeUtils.addCpProperty(cmdLine, cp);
        // cmdLine.add("-Dminimize_values=true");

        ExecutionModeUtils.addArgsExcludingCpProperty(cmdLine, args);

        ExecutionModeUtils.addTargetClassProperty(cmdLine, targetClass);
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
                clients = new CopyOnWriteArraySet<>(MasterServices.getInstance().getMasterNode()
                        .getClientsOnceAllConnected(10000).values());
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
                        client.printClassStatistics();
                    } catch (RemoteException e) {
                        logger.error("Error in starting clients", e);
                    }
                }

                handler.waitForResult((Properties.GLOBAL_TIMEOUT
                        + Properties.MINIMIZATION_TIMEOUT + Properties.EXTRA_TIMEOUT) * 1000); // FIXXME: search
            }
            // timeout plus
            // 100 seconds?
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // ignored
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
