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
package org.evosuite.intellij.maven;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;

import org.evosuite.ide.PomSettings;

public final class MavenBridge {
    private MavenBridge() {}

    public static MavenIntegration get(Project project) {
        // Registered only by the optional Maven descriptor; absent services resolve to null.
        return project.getService(MavenIntegration.class);
    }

    public static PomSettings settings(Module module) {
        var support = get(module.getProject());
        return support == null ? null : support.settings(module);
    }
}
