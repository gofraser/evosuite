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
package org.evosuite.eclipse.ui;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.junit.launcher.JUnitLaunchShortcut;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jdt.launching.IVMInstall2;
import org.eclipse.jdt.launching.JavaRuntime;
import org.evosuite.eclipse.Activator;
import org.evosuite.ide.GenerationOptions;

public final class SuiteJUnitLaunch extends JUnitLaunchShortcut {
    public void run(IFile suite, GenerationOptions options) throws CoreException {
        var unit = JavaCore.createCompilationUnitFrom(suite);
        var type = unit == null ? null : unit.findPrimaryType();
        if (type == null || !type.exists() || !unit.getJavaProject().isOnClasspath(unit)) {
            throw new CoreException(Activator.error(suite.getFullPath() + " is outside the Java test source path."));
        }
        var config = createLaunchConfiguration(type);
        if (GenerationOptions.JUNIT4.equals(options.effectiveTestFormat())) {
            config.setAttribute("org.eclipse.jdt.junit.TEST_KIND", "org.eclipse.jdt.junit.loader.junit4");
        }
        if (!options.effectiveNoRuntimeDependency()) {
            var vm = JavaRuntime.getVMInstall(type.getJavaProject());
            if (vm == null) vm = JavaRuntime.getDefaultVMInstall();
            if (vm instanceof IVMInstall2 versioned) {
                String version = versioned.getJavaVersion();
                int release = version != null && version.matches("^[1-9][0-9](?:[._+-].*)?$")
                        ? Integer.parseInt(version.substring(0, 2)) : 0;
                if (release >= 18 && release < 24) {
                    config.setAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS,
                            "-Djava.security.manager=allow");
                }
            }
        }
        DebugUITools.launch(config, ILaunchManager.RUN_MODE);
    }
}
