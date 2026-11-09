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

import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.evosuite.eclipse.Activator;
import org.evosuite.ide.GenerationOptions;
import org.evosuite.ide.SettingKeys;
import org.evosuite.eclipse.maven.MavenSupport;
import org.evosuite.ide.PomSettings;

/**
 * Preference keys, and how a project's settings are resolved.
 *
 * <p>Workspace preferences hold the defaults. A project that enables project-specific settings
 * keeps its own copy in {@code .settings/org.evosuite.plugins.eclipse.core.prefs}, which can be
 * committed so that a team shares one configuration.
 */
public final class EvoSuitePreferences {

    public static final String QUALIFIER = Activator.PLUGIN_ID;

    public static final String USE_PROJECT_SETTINGS = SettingKeys.USE_PROJECT_SETTINGS;
    public static final String SEARCH_BUDGET = SettingKeys.SEARCH_BUDGET;
    public static final String CRITERION = SettingKeys.CRITERION;
    public static final String ASSERTIONS = SettingKeys.ASSERTIONS;
    public static final String MINIMIZE = SettingKeys.MINIMIZE;
    public static final String ASSERTION_STRATEGY = SettingKeys.ASSERTION_STRATEGY;
    public static final String TEST_NAMING = SettingKeys.TEST_NAMING;
    public static final String VARIABLE_NAMING = SettingKeys.VARIABLE_NAMING;
    public static final String TEST_COMMENTS = SettingKeys.TEST_COMMENTS;
    public static final String TEST_TIMEOUT = SettingKeys.TEST_TIMEOUT;
    public static final String TEST_FORMAT = SettingKeys.TEST_FORMAT;
    public static final String TEST_SUFFIX = SettingKeys.TEST_SUFFIX;
    public static final String TEST_FOLDER = SettingKeys.TEST_FOLDER;
    public static final String CLIENT_MEMORY = SettingKeys.CLIENT_MEMORY;
    public static final String EXTRA_ARGUMENTS = SettingKeys.EXTRA_ARGUMENTS;
    public static final String JAVA_HOME = SettingKeys.JAVA_HOME;
    public static final String OPEN_GENERATED_TESTS = SettingKeys.OPEN_GENERATED_TESTS;
    public static final String CONFIGURE_BUILD_PATH = SettingKeys.CONFIGURE_BUILD_PATH;
    public static final String USE_POM_SETTINGS = SettingKeys.USE_POM_SETTINGS;
    public static final String LLM_PROVIDER = SettingKeys.LLM_PROVIDER;
    public static final String LLM_MODEL = SettingKeys.LLM_MODEL;
    public static final String LLM_BASE_URL = SettingKeys.LLM_BASE_URL;
    public static final String LLM_APPROACH = SettingKeys.LLM_APPROACH;
    public static final String LLM_SEEDING = SettingKeys.LLM_SEEDING;
    public static final String LLM_ON_STAGNATION = SettingKeys.LLM_ON_STAGNATION;
    public static final String LLM_OPERATORS = SettingKeys.LLM_OPERATORS;
    public static final String LLM_POSTPROCESSING = SettingKeys.LLM_POSTPROCESSING;
    public static final String LLM_MAX_CALLS = SettingKeys.LLM_MAX_CALLS;

    public static final String AUTO_JUNIT = "AUTO";
    public static final String RANDOM_SEED = "randomSeed";
    public static final String MINIMIZE_VALUES = SettingKeys.MINIMIZE_VALUES;
    public static final String NO_RUNTIME = "noRuntimeDependency";
    public static final String LLM_CONTEXT = "llmContextMode";
    public static final String LLM_TOKENS = "llmMaxTokens";
    public static final String LLM_TIMEOUT = "llmTimeoutSeconds";
    public static final String LLM_RETRIES = "llmRetryMaxAttempts";
    public static final String LLM_TEMPERATURE = "llmTemperature";

    private EvoSuitePreferences() {
    }

    public static void openLlmSettings() {
        if (!PlatformUI.isWorkbenchRunning()) return;
        var workbench = PlatformUI.getWorkbench();
        workbench.getDisplay().asyncExec(() -> {
            var window = workbench.getActiveWorkbenchWindow();
            if (window != null) PreferencesUtil.createPreferenceDialogOn(window.getShell(),
                    SettingsFields.Section.LLM.preferencePageId, SettingsFields.Section.preferencePageIds(), null).open();
        });
    }

    /** Writes {@link GenerationOptions}' defaults into the default scope. */
    static void initializeDefaults() {
        IEclipsePreferences node = DefaultScope.INSTANCE.getNode(QUALIFIER);
        node.putBoolean(USE_PROJECT_SETTINGS, false);
        GenerationOptions defaults = defaults();
        for (String key : SettingKeys.all()) {
            node.put(key, SettingKeys.value(defaults, key));
        }
    }

    public static boolean usesProjectSettings(IProject project) {
        return new ProjectScope(project).getNode(QUALIFIER).getBoolean(USE_PROJECT_SETTINGS, false);
    }

    /**
     * The settings for a generation request in {@code project}: its preferences, overridden by
     * the EvoSuite Maven plug-in's configuration if the project has one and the settings allow
     * it, so that the IDE and {@code mvn evosuite:generate} produce the same tests.
     */
    public static GenerationOptions resolve(IProject project, IProgressMonitor monitor) {
        GenerationOptions options = load(project);
        if (options.usePomSettings) {
            PomSettings pom = MavenSupport.pomSettings(project, monitor);
            if (pom != null) {
                applyPom(pom, options, project);
            }
        }
        if (AUTO_JUNIT.equals(options.testFormat)) {
            IJavaProject java = JavaCore.create(project);
            options.testFormat = GenerationOptions.JUNIT5;
            try {
                if (java.exists()) {
                    options.testFormat = SettingKeys.junitFormat(java.findType("org.junit.Test") != null,
                            java.findType("org.junit.jupiter.api.Test") != null);
                }
            } catch (JavaModelException e) {
                Activator.log(e.getStatus());
            }
        }
        return options;
    }

    private static void applyPom(PomSettings pom, GenerationOptions options, IProject project) {
        options.fromPom = List.copyOf(pom.applyTo(options, project.getLocation() == null ? null
                : project.getLocation().toFile()));
    }

    /**
     * What the project's settings page says about the pom, or {@code null} if it does not bear
     * on the settings. Called on the UI thread, so it only knows a pom that m2e has loaded.
     */
    static String pomNote(IProject project) {
        GenerationOptions options = load(project);
        PomSettings pom = options.usePomSettings ? MavenSupport.pomSettings(project, null) : null;
        if (pom == null) {
            return null;
        }
        if (!pom.isLoaded()) {
            return "m2e has not loaded pom.xml yet, so this page cannot show which settings of the EvoSuite "
                    + "Maven plugin take precedence over those below.";
        }
        applyPom(pom, options, project);
        return options.fromPom.isEmpty() ? null : "pom.xml configures the EvoSuite Maven plugin, and these take "
                + "precedence over the settings below: " + String.join("; ", options.fromPom) + ".";
    }

    private static GenerationOptions defaults() {
        GenerationOptions options = new GenerationOptions();
        options.testFormat = AUTO_JUNIT;
        return options;
    }

    /** Project preferences inherit EvoSuite defaults. Workspace values apply only in workspace mode. */
    public static GenerationOptions load(IProject project) {
        IEclipsePreferences preferences = usesProjectSettings(project)
                ? new ProjectScope(project).getNode(QUALIFIER) : InstanceScope.INSTANCE.getNode(QUALIFIER);
        GenerationOptions defaults = defaults();
        IEclipsePreferences defaultPreferences = DefaultScope.INSTANCE.getNode(QUALIFIER);
        GenerationOptions options = SettingKeys.read(defaults.copy(),
                key -> preferences.get(key, defaultPreferences.get(key, null)));
        if (options.testSuffix.isBlank()) options.testSuffix = defaults.testSuffix;
        if (options.testFolder.isBlank()) options.testFolder = defaults.testFolder;
        return options;
    }
}
