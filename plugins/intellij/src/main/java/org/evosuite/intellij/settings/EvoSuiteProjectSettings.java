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

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;

import org.jetbrains.annotations.NotNull;

import java.util.*;

@Service(Service.Level.PROJECT)
@State(name = "EvoSuiteProject", storages = @Storage("evosuite.xml"))
public final class EvoSuiteProjectSettings
        implements PersistentStateComponent<EvoSuiteProjectSettings.State> {
    public static final class State {
        public boolean useProjectSettings;
        public boolean legacyImported;
        public boolean delegateToMaven;
        public Map<String, String> values = new TreeMap<>();
    }

    private State state = new State();

    public static EvoSuiteProjectSettings getInstance(Project project) {
        return project.getService(EvoSuiteProjectSettings.class);
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    public synchronized void migrate(Project project) {
        if (state.legacyImported) return;
        PropertiesComponent old = PropertiesComponent.getInstance(project);
        String time = old.getValue("time_evosuite_param");
        if (time != null) {
            try {
                int seconds = Math.multiplyExact(Integer.parseInt(time), 60);
                if (seconds > 0) {
                    state.values.put("searchBudget", Integer.toString(seconds));
                    state.useProjectSettings = true;
                    old.unsetValue("time_evosuite_param");
                }
            } catch (IllegalArgumentException | ArithmeticException ignored) {
                /* Invalid legacy values retain engine defaults. */
            }
        }
        String folder = old.getValue("target_folder_evosuite_param");
        if (folder != null && !folder.isBlank() && !folder.equals("src/evo")) {
            state.values.put("testFolder", folder);
            state.useProjectSettings = true;
            old.unsetValue("target_folder_evosuite_param");
        }
        state.legacyImported = true;
    }
}
