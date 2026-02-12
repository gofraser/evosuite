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

package org.evosuite.junit;

import org.evosuite.Properties;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.platform.engine.discovery.ClassNameFilter.includeClassNamePatterns;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

/**
 * JUnitRunner class.
 *
 * @author José Campos
 */
public class JUnitRunner {

    private static final Logger logger = LoggerFactory.getLogger(JUnitRunner.class);

    private final List<JUnitResult> testResults;


    private final Class<?> junitClass;


    public JUnitRunner(Class<?> junitClass) {
        this.testResults = new ArrayList<>();
        this.junitClass = junitClass;
    }

    /**
     * Executes the JUnit tests using the configured test format (JUnit 4 or 5).
     */
    public void run() {

        if (Properties.TEST_FORMAT == Properties.OutputFormat.JUNIT4) {
            Request request = Request.aClass(this.junitClass);
            logger.warn("Running Junit 4 test");
            JUnitCore junit = new JUnitCore();
            junit.addListener(new JUnit4RunListener(this));
            junit.run(request);
        } else if (Properties.TEST_FORMAT == Properties.OutputFormat.JUNIT5) {
            logger.warn("Running Junit 5 test");

            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectPackage("com.baeldung.junit5.runfromjava"))
                    .filters(includeClassNamePatterns(".*Test"))
                    .build();
            Launcher launcher = LauncherFactory.create();
            TestPlan testPlan = launcher.discover(request);
            launcher.registerTestExecutionListeners(new JUnit5RunListener(this));

            launcher.execute(request);
        } else {
            logger.warn("Can't run junit test with test format: {}", Properties.TEST_FORMAT);
        }
    }

    /**
     * Add result.
     *
     * @param testResult the result to add
     */
    public void addResult(JUnitResult testResult) {
        this.testResults.add(testResult);
    }

    /**
     * Get test results.
     *
     * @return list of test results
     */
    public List<JUnitResult> getTestResults() {
        return this.testResults;
    }

    /**
     * Get JUnit class.
     *
     * @return the junit class
     */
    public Class<?> getJUnitClass() {
        return this.junitClass;
    }
}
