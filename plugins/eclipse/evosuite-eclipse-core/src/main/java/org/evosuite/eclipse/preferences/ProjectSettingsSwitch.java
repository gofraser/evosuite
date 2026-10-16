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
package org.evosuite.eclipse.preferences;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.swt.widgets.Shell;

/**
 * Whether a project uses its own settings, as the project's property pages show it before it is
 * stored. All EvoSuite property pages of one project in one dialog share it, so that ticking the
 * checkbox on one page ticks it on the others, and each page stores the same value on OK.
 */
final class ProjectSettingsSwitch {

    private boolean enabled;
    private final List<Consumer<Boolean>> listeners = new ArrayList<>();

    private ProjectSettingsSwitch(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * The switch for {@code project} in the dialog with {@code shell}. The first page to ask
     * creates it with {@code stored}, the value in the project's preferences; it goes away with
     * the dialog.
     */
    static ProjectSettingsSwitch of(Shell shell, IProject project, boolean stored) {
        String key = ProjectSettingsSwitch.class.getName() + ":" + project.getName();
        if (shell.getData(key) instanceof ProjectSettingsSwitch existing) {
            return existing;
        }
        ProjectSettingsSwitch created = new ProjectSettingsSwitch(stored);
        shell.setData(key, created);
        return created;
    }

    boolean isEnabled() {
        return enabled;
    }

    /** Changes the value and tells every page, if the value changes. */
    void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        for (Consumer<Boolean> listener : List.copyOf(listeners)) {
            listener.accept(enabled);
        }
    }

    void addListener(Consumer<Boolean> listener) {
        listeners.add(listener);
    }

    void removeListener(Consumer<Boolean> listener) {
        listeners.remove(listener);
    }
}
