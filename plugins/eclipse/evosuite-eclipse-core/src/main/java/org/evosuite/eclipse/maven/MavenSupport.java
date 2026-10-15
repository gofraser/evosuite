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
package org.evosuite.eclipse.maven;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.evosuite.eclipse.Activator;

/** Access to Maven projects, if m2e is installed; the plug-in works without it. */
public final class MavenSupport {

    static final String M2E_BUNDLE = "org.eclipse.m2e.core";
    static final String MAVEN_NATURE = "org.eclipse.m2e.core.maven2Nature";

    private MavenSupport() {
    }

    /**
     * The EvoSuite Maven plug-in's configuration in {@code project}'s pom, or {@code null} if the
     * project is not a Maven project, does not use the plug-in, or m2e is not installed.
     */
    public static PomSettings pomSettings(IProject project, IProgressMonitor monitor) {
        try {
            if (Platform.getBundle(M2E_BUNDLE) == null || !project.hasNature(MAVEN_NATURE)) {
                return null;
            }
            // M2eReader links against m2e, so it must not be touched before the check above.
            return M2eReader.read(project, monitor);
        } catch (CoreException | RuntimeException | LinkageError e) {
            Activator.logError("Cannot read the EvoSuite configuration in the pom of " + project.getName(), e);
            return null;
        }
    }
}
