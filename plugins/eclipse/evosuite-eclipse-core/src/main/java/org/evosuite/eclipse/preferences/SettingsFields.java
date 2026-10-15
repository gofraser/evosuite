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

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.DirectoryFieldEditor;
import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.widgets.Composite;
import org.evosuite.eclipse.launch.GenerationOptions;

/**
 * The settings editors, shared by the workspace preference page and the project property page
 * so that the two cannot drift apart.
 */
final class SettingsFields {

    private SettingsFields() {
    }

    static List<FieldEditor> create(Composite parent, Consumer<FieldEditor> add) {
        List<FieldEditor> editors = new ArrayList<>();

        IntegerFieldEditor budget = new IntegerFieldEditor(EvoSuitePreferences.SEARCH_BUDGET,
                "Search &budget per class (seconds):", parent);
        budget.setValidRange(1, 24 * 60 * 60);
        editors.add(budget);

        editors.add(new StringFieldEditor(EvoSuitePreferences.CRITERION,
                "Coverage &criteria (empty for EvoSuite's default):", parent));
        editors.add(new BooleanFieldEditor(EvoSuitePreferences.ASSERTIONS,
                "Generate &assertions", parent));
        editors.add(new BooleanFieldEditor(EvoSuitePreferences.MINIMIZE,
                "&Minimize tests", parent));
        editors.add(new ComboFieldEditor(EvoSuitePreferences.TEST_FORMAT, "Test &format:",
                new String[][]{{"JUnit 5", GenerationOptions.JUNIT5}, {"JUnit 4", GenerationOptions.JUNIT4}},
                parent));
        editors.add(new StringFieldEditor(EvoSuitePreferences.TEST_FOLDER,
                "Test &source folder (project-relative):", parent));
        editors.add(new StringFieldEditor(EvoSuitePreferences.TEST_SUFFIX,
                "Test class &suffix:", parent));
        editors.add(new BooleanFieldEditor(EvoSuitePreferences.CONFIGURE_BUILD_PATH,
                "Add the test folder, JUnit and the EvoSuite runtime to the build &path", parent));
        editors.add(new BooleanFieldEditor(EvoSuitePreferences.OPEN_GENERATED_TESTS,
                "&Open generated tests in an editor", parent));

        IntegerFieldEditor memory = new IntegerFieldEditor(EvoSuitePreferences.CLIENT_MEMORY,
                "Client &heap in MB (0 for EvoSuite's default):", parent);
        memory.setValidRange(0, 1024 * 1024);
        editors.add(memory);

        DirectoryFieldEditor javaHome = new DirectoryFieldEditor(EvoSuitePreferences.JAVA_HOME,
                "&Java home (empty for the project's JRE):", parent);
        javaHome.setEmptyStringAllowed(true);
        editors.add(javaHome);

        editors.add(new StringFieldEditor(EvoSuitePreferences.EXTRA_ARGUMENTS,
                "Additional &EvoSuite arguments:", parent));

        editors.forEach(add);
        return editors;
    }
}
