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
package org.evosuite.eclipse.launch;

import java.lang.reflect.InvocationTargetException;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.evosuite.eclipse.Activator;

/** Calls Buildship's public API when the optional Gradle integration is installed. */
final class GradleSync {
    private GradleSync() {}

    static void refresh(IProject project, IProgressMonitor monitor) throws CoreException {
        var bundle = Platform.getBundle(ManagedBuildSetup.BUILDSHIP);
        if (bundle == null) throw new CoreException(Activator.error("Buildship is required to refresh " + project.getName()));
        try {
            String api = "org.eclipse.buildship.core.";
            Object workspace = bundle.loadClass(api + "GradleCore").getMethod("getWorkspace").invoke(null);
            var build = (Optional<?>) bundle.loadClass(api + "GradleWorkspace").getMethod("getBuild", IProject.class)
                    .invoke(workspace, project);
            if (build.isEmpty()) throw new CoreException(Activator.error("No imported Gradle build for " + project.getName()));
            Object result = bundle.loadClass(api + "GradleBuild").getMethod("synchronize", IProgressMonitor.class)
                    .invoke(build.get(), monitor);
            IStatus status = (IStatus) bundle.loadClass(api + "SynchronizationResult").getMethod("getStatus")
                    .invoke(result);
            if (!status.isOK()) throw new CoreException(status);
        } catch (InvocationTargetException e) {
            throw new CoreException(Activator.error("Buildship could not refresh " + project.getName(), e.getCause()));
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new CoreException(Activator.error("Cannot access Buildship's synchronization API.", e));
        }
    }
}
