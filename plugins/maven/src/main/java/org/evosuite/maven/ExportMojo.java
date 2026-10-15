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
import org.evosuite.continuous.ContinuousTestGeneration;
import org.evosuite.maven.util.GeneratedTests;

import java.io.File;
import java.io.IOException;

/**
 * <p>
 * When run, EvoSuite generate tests in a specific folder.
 * New runs of EvoSuite can exploit the tests in such folder, and/or modify them.
 * </p>
 *
 * <p>
 * With "export" we copy all generated tests to the folder of generated tests, {@code src/test/evosuite} by
 * default, which the {@code setup} goal adds to the build. {@code generate} already does this at the end of
 * each run, so this goal is only needed to restore that folder from EvoSuite's own copy.
 * </p>
 */
@Mojo(name = "export", threadSafe = true)
public class ExportMojo extends AbstractEvoSuiteMojo {

    /**
     * Folder to copy the generated tests to, relative to the module's base directory.
     */
    @Parameter(property = "evosuite.testFolder", defaultValue = GeneratedTests.DEFAULT_FOLDER)
    private String testFolder;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        String legacy = legacyProperty("evosuite.testFolder", "targetFolder");
        if (legacy != null) {
            testFolder = legacy;
        }

        getLog().info("Exporting tests");

        File basedir = project.getBasedir();

        try {
            boolean exported = ContinuousTestGeneration.exportToFolder(basedir.getAbsolutePath(), testFolder);
            if (!exported) {
                getLog().info("Nothing to export");
                return;
            }
        } catch (IOException e) {
            String msg = "Error while exporting tests: " + e.getMessage();
            getLog().error(msg);
            throw new MojoFailureException(msg);
        }

        File target = ContinuousTestGeneration.resolveExportFolder(basedir.getAbsolutePath(), testFolder);
        getLog().info("Exported tests to " + target);
    }

}
