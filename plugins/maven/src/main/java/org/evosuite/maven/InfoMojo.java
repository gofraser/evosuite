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
import org.evosuite.maven.util.EvoSuiteRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * Obtain info of generated tests so far.
 */
@Mojo(name = "info", threadSafe = true)
public class InfoMojo extends AbstractEvoSuiteMojo {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("Going to query EvoSuite info on current project");

        List<String> params = new ArrayList<>();
        params.add("-continuous");
        params.add("info");

        EvoSuiteRunner runner = createRunner();
        boolean ok = runner.runEvoSuite(project.getBasedir().toString(), params);

        if (!ok) {
            throw new MojoFailureException("Failed to correctly execute EvoSuite");
        }
    }
}
