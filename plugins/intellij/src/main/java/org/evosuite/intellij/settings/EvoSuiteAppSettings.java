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

import com.intellij.openapi.components.*;

import org.jetbrains.annotations.NotNull;

import java.util.*;

@Service(Service.Level.APP)
@State(name = "EvoSuiteApplication", storages = @Storage("evosuite.xml"))
public final class EvoSuiteAppSettings
        implements PersistentStateComponent<EvoSuiteAppSettings.State> {
    public static final class State {
        public String legacyApiKeyProvider = "";
        public Map<String, String> values = new TreeMap<>();
    }

    private State state = new State();

    public static EvoSuiteAppSettings getInstance() {
        return service(EvoSuiteAppSettings.class);
    }

    private static <T> T service(Class<T> type) {
        return com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(type);
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }
}
