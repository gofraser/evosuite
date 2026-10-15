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

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IPreferencesService;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.evosuite.eclipse.Activator;
import org.evosuite.eclipse.launch.GenerationOptions;

/**
 * Preference keys, and how a project's settings are resolved.
 *
 * <p>Workspace preferences hold the defaults. A project that enables project-specific settings
 * keeps its own copy in {@code .settings/org.evosuite.plugins.eclipse.core.prefs}, which can be
 * committed so that a team shares one configuration.
 */
public final class EvoSuitePreferences {

    public static final String QUALIFIER = Activator.PLUGIN_ID;

    public static final String USE_PROJECT_SETTINGS = "useProjectSettings";
    public static final String SEARCH_BUDGET = "searchBudget";
    public static final String CRITERION = "criterion";
    public static final String ASSERTIONS = "assertions";
    public static final String MINIMIZE = "minimize";
    public static final String TEST_FORMAT = "testFormat";
    public static final String TEST_SUFFIX = "testSuffix";
    public static final String TEST_FOLDER = "testFolder";
    public static final String CLIENT_MEMORY = "clientMemory";
    public static final String EXTRA_ARGUMENTS = "extraArguments";
    public static final String JAVA_HOME = "javaHome";
    public static final String OPEN_GENERATED_TESTS = "openGeneratedTests";
    public static final String CONFIGURE_BUILD_PATH = "configureBuildPath";

    private EvoSuitePreferences() {
    }

    /** Writes {@link GenerationOptions}' defaults into the default scope. */
    static void initializeDefaults() {
        GenerationOptions d = new GenerationOptions();
        IEclipsePreferences node = DefaultScope.INSTANCE.getNode(QUALIFIER);
        node.putBoolean(USE_PROJECT_SETTINGS, false);
        node.putInt(SEARCH_BUDGET, d.searchBudget);
        node.put(CRITERION, d.criterion);
        node.putBoolean(ASSERTIONS, d.assertions);
        node.putBoolean(MINIMIZE, d.minimize);
        node.put(TEST_FORMAT, d.testFormat);
        node.put(TEST_SUFFIX, d.testSuffix);
        node.put(TEST_FOLDER, d.testFolder);
        node.putInt(CLIENT_MEMORY, d.clientMemory);
        node.put(EXTRA_ARGUMENTS, d.extraArguments);
        node.put(JAVA_HOME, d.javaHome);
        node.putBoolean(OPEN_GENERATED_TESTS, d.openGeneratedTests);
        node.putBoolean(CONFIGURE_BUILD_PATH, d.configureBuildPath);
    }

    public static boolean usesProjectSettings(IProject project) {
        return new ProjectScope(project).getNode(QUALIFIER).getBoolean(USE_PROJECT_SETTINGS, false);
    }

    /**
     * The settings that apply to a generation request in {@code project}. Project-specific
     * settings fall back to the defaults, not to the workspace: a preference store drops a value
     * equal to its default, so a project that sets the default explicitly must not silently pick
     * up a different workspace value instead.
     */
    public static GenerationOptions load(IProject project) {
        IScopeContext[] contexts = usesProjectSettings(project)
                ? new IScopeContext[]{new ProjectScope(project), DefaultScope.INSTANCE}
                : new IScopeContext[]{InstanceScope.INSTANCE, DefaultScope.INSTANCE};
        IPreferencesService prefs = Platform.getPreferencesService();
        GenerationOptions d = new GenerationOptions();
        GenerationOptions o = new GenerationOptions();
        o.searchBudget = prefs.getInt(QUALIFIER, SEARCH_BUDGET, d.searchBudget, contexts);
        o.criterion = prefs.getString(QUALIFIER, CRITERION, d.criterion, contexts);
        o.assertions = prefs.getBoolean(QUALIFIER, ASSERTIONS, d.assertions, contexts);
        o.minimize = prefs.getBoolean(QUALIFIER, MINIMIZE, d.minimize, contexts);
        o.testFormat = prefs.getString(QUALIFIER, TEST_FORMAT, d.testFormat, contexts);
        o.testSuffix = prefs.getString(QUALIFIER, TEST_SUFFIX, d.testSuffix, contexts);
        o.testFolder = prefs.getString(QUALIFIER, TEST_FOLDER, d.testFolder, contexts);
        o.clientMemory = prefs.getInt(QUALIFIER, CLIENT_MEMORY, d.clientMemory, contexts);
        o.extraArguments = prefs.getString(QUALIFIER, EXTRA_ARGUMENTS, d.extraArguments, contexts);
        o.javaHome = prefs.getString(QUALIFIER, JAVA_HOME, d.javaHome, contexts);
        o.openGeneratedTests = prefs.getBoolean(QUALIFIER, OPEN_GENERATED_TESTS, d.openGeneratedTests, contexts);
        o.configureBuildPath = prefs.getBoolean(QUALIFIER, CONFIGURE_BUILD_PATH, d.configureBuildPath, contexts);
        // Trimmed once here, so that every user of a setting sees the same value.
        o.criterion = o.criterion.trim();
        o.testSuffix = o.testSuffix.isBlank() ? d.testSuffix : o.testSuffix.trim();
        o.testFolder = o.testFolder.isBlank() ? d.testFolder : o.testFolder.trim();
        o.javaHome = o.javaHome.trim();
        return o;
    }
}
