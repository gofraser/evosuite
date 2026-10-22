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
package org.evosuite.intellij.run;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.junit.JUnitConfiguration;
import com.intellij.execution.junit.JUnitConfigurationType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;

/** Let IntelliJ select the generated class's actual test source-set module and JUnit runner. */
public final class GeneratedTestRunner {
    private GeneratedTestRunner() {}

    public static RunnerAndConfigurationSettings configuration(Project project, PsiClass type) {
        var settings =
                RunManager.getInstance(project)
                        .createConfiguration(
                                "EvoSuite: " + type.getName(),
                                JUnitConfigurationType.getInstance()
                                        .getConfigurationFactories()[0]);
        ((JUnitConfiguration) settings.getConfiguration()).beClassConfiguration(type);
        return settings;
    }
}
