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
import org.evosuite.TestGenerationContext;
import org.evosuite.TimeController;
import org.evosuite.classpath.ResourceList;
import org.evosuite.instrumentation.BytecodeInstrumentation;
import org.evosuite.rmi.MasterServices;
import org.evosuite.rmi.service.ClientNodeRemote;
import org.evosuite.statistics.SearchStatistics;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

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

        ExecutionModeUtils.runSingleClient(classPath, projectCP.isEmpty() ? classPath : projectCP,
                Properties.HEADLESS_MODE, args, targetClass, "-Djunit=" + Properties.JUNIT,
                ClientNodeRemote::doCoverageAnalysis,
                TimeController.getInstance().calculateForHowLongClientWillRunInSeconds() * 1000, () -> {
                    if (Properties.NEW_STATISTICS) {
                        if (MasterServices.getInstance().getMasterNode() == null) {
                            logger.error("Cannot write results as RMI master node is not running");
                        } else {
                            LoggingUtils.getEvoLogger().info("* Writing statistics");

                            SearchStatistics.getInstance().writeStatisticsForAnalysis();
                        }
                    }
                });
    }
}
