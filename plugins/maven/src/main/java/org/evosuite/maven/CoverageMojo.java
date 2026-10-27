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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.evosuite.maven.util.EvoSuiteRunner;
import org.evosuite.maven.util.ProjectClasspath;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Execute the manually written test suites (usually located under src/test/java)
 * and return the coverage of each class.
 *
 * @author José Campos
 */
@Mojo(name = "coverage", requiresProject = true, requiresDependencyResolution = ResolutionScope.TEST,
        requiresDependencyCollection = ResolutionScope.TEST, threadSafe = true)
public class CoverageMojo extends AbstractEvoSuiteMojo {

    /**
     * Coverage criterion. Can define more than one criterion by using a ':' separated list.
     */
    // FIXME would be nice to have the value of Properties.CRITERION but seems to be not possible
    // FIXME OUTPUT:METHOD:METHODNOEXCEPTION relies on Observers, to have coverage of these criteria
    // JUnit test cases have to be converted to some format that EvoSuite can understand
    // and there is no point of using EXCEPTION if we don't know how many exceptions we could have
    @Parameter(property = "evosuite.criterion", defaultValue = "LINE:BRANCH:CBRANCH:WEAKMUTATION:METHODTRACE")
    private String criterion;

    /**
     * Maximum seconds allowed.
     */
    @Parameter(property = "evosuite.globalTimeout", defaultValue = "120")
    private int globalTimeout;

    @Parameter(property = "evosuite.outputVariables", defaultValue = "TARGET_CLASS,criterion,Coverage,Total_Goals,Covered_Goals"
            + ",LineCoverage,LineCoverageBitString"
            + ",BranchCoverage,BranchCoverageBitString"
            + ",CBranchCoverage,CBranchCoverageBitString"
            + ",WeakMutationScore,WeakMutationCoverageBitString"
            + ",MethodTraceCoverage,MethodTraceCoverageBitString")
    private String outputVariables;

    /**
     * A colon(:) separated list of JUnit suites to execute. Can be a prefix (i.e., package name),
     * a directory, a jar file, or the full name of a JUnit suite.
     */
    @Parameter(property = "evosuite.junit")
    private String junit;

    private void applyLegacyProperties() throws MojoExecutionException {
        String value;
        if ((value = legacyProperty("evosuite.criterion", "criterion")) != null) {
            criterion = value;
        }
        if ((value = legacyProperty("evosuite.globalTimeout", "global_timeout")) != null) {
            try {
                globalTimeout = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new MojoExecutionException("Invalid number: " + value, e);
            }
        }
        if ((value = legacyProperty("evosuite.outputVariables", "output_variables")) != null) {
            outputVariables = value;
        }
        if ((value = legacyProperty("evosuite.junit", "junit")) != null) {
            junit = value;
        }
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        applyLegacyProperties();
        getLog().info("Going to measure the coverage of manually written test cases with EvoSuite");

        ProjectClasspath classpath = ProjectClasspath.of(this.project, getLog());
        if (classpath.isEmpty()) {
            getLog().info("Nothing to measure coverage!");
            return;
        }

        List<String> params = new ArrayList<>();
        params.add("-measureCoverage");
        params.add("-target");
        params.add(classpath.getTarget());
        try {
            params.add("-DCP_file_path=" + classpath.writeClasspathToFile().getAbsolutePath());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write the classpath: " + e.getMessage(), e);
        }
        if (this.junit != null) {
            params.add("-Djunit=" + this.junit);
        }

        params.add("-Dcriterion=" + this.criterion);
        params.add("-Doutput_variables=" + this.outputVariables);
        params.add("-Dglobal_timeout=" + this.globalTimeout);
        // in theory should be safe to execute source-code
        params.add("-Dsandbox=false");
        params.add("-Dvirtual_fs=false");
        params.add("-Dvirtual_net=false");
        params.add("-Dreplace_calls=false");
        params.add("-Dreplace_system_in=false");

        getLog().info("Params:");
        for (String s : params) {
            getLog().info("  " + s);
        }

        EvoSuiteRunner runner = createRunner();
        boolean ok = runner.runEvoSuite(this.project.getBasedir().toString(), params);

        if (!ok) {
            throw new MojoFailureException("Failed to correctly execute EvoSuite");
        }
    }
}
