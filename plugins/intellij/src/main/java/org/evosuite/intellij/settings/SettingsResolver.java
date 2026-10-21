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
package org.evosuite.intellij.settings;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;

import org.evosuite.ide.*;
import org.evosuite.intellij.maven.MavenBridge;
import org.evosuite.intellij.run.IdeThread;
import org.evosuite.intellij.setup.ProjectSetupService;

import java.util.*;

public final class SettingsResolver {
    private SettingsResolver() {}

    public static GenerationOptions resolve(Module module) {
        Project project = module.getProject();
        EvoSuiteProjectSettings settings = EvoSuiteProjectSettings.getInstance(project);
        settings.migrate(project);
        var state = settings.getState();
        Map<String, String> values =
                new HashMap<>(
                        state.useProjectSettings
                                ? state.values
                                : EvoSuiteAppSettings.getInstance().getState().values);
        if (!values.containsKey("testFormat") || values.get("testFormat").isBlank()) {
            String format =
                    IdeThread.smartRead(
                            project,
                            () -> {
                                JavaPsiFacade psi = JavaPsiFacade.getInstance(project);
                                GlobalSearchScope scope =
                                        GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(
                                                module, true);
                                return SettingKeys.junitFormat(
                                        psi.findClass("org.junit.Test", scope) != null,
                                        psi.findClass("org.junit.jupiter.api.Test", scope) != null);
                            });
            values.put("testFormat", format);
        }
        GenerationOptions options = SettingKeys.resolve(values);
        if (options.usePomSettings) {
            PomSettings pom = MavenBridge.settings(module);
            if (pom != null)
                options.fromPom =
                        List.copyOf(
                                pom.applyTo(options, ProjectSetupService.baseDir(module).toFile()));
        }
        options.testFormat = options.effectiveTestFormat();
        SettingKeys.validateOutput(options);
        return options;
    }
}
