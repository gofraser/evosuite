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

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.evosuite.Properties;
import org.evosuite.rmi.service.ClientNodeRemote;
import org.evosuite.utils.LoggingUtils;

import java.util.List;

public class PrintStats {

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
        ExecutionModeUtils.runSingleClient(classpathInfo.combinedClasspath, classpathInfo.projectClasspath, true,
                args, targetClass, null, ClientNodeRemote::printClassStatistics,
                (Properties.GLOBAL_TIMEOUT + Properties.MINIMIZATION_TIMEOUT + Properties.EXTRA_TIMEOUT) * 1000,
                null);
    }

}
