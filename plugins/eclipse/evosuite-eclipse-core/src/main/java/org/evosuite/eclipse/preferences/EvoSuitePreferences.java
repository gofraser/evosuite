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
import org.evosuite.eclipse.Activator;
import org.evosuite.eclipse.launch.GenerationOptions;
import org.evosuite.eclipse.maven.MavenSupport;
import org.evosuite.eclipse.maven.PomSettings;

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
    public static final String ASSERTION_STRATEGY = "assertionStrategy";
    public static final String TEST_NAMING = "testNaming";
    public static final String VARIABLE_NAMING = "variableNaming";
    public static final String TEST_COMMENTS = "testComments";
    public static final String TEST_TIMEOUT = "testTimeout";
    public static final String TEST_FORMAT = "testFormat";
    public static final String TEST_SUFFIX = "testSuffix";
    public static final String TEST_FOLDER = "testFolder";
    public static final String CLIENT_MEMORY = "clientMemory";
    public static final String EXTRA_ARGUMENTS = "extraArguments";
    public static final String JAVA_HOME = "javaHome";
    public static final String OPEN_GENERATED_TESTS = "openGeneratedTests";
    public static final String CONFIGURE_BUILD_PATH = "configureBuildPath";
    public static final String USE_POM_SETTINGS = "usePomSettings";
    public static final String LLM_PROVIDER = "llmProvider";
    public static final String LLM_MODEL = "llmModel";
    public static final String LLM_BASE_URL = "llmBaseUrl";
    public static final String LLM_APPROACH = "llmApproach";
    public static final String LLM_SEEDING = "llmSeeding";
    public static final String LLM_ON_STAGNATION = "llmOnStagnation";
    public static final String LLM_OPERATORS = "llmOperators";
    public static final String LLM_POSTPROCESSING = "llmPostprocessing";
    public static final String LLM_MAX_CALLS = "llmMaxCalls";

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
        node.put(ASSERTION_STRATEGY, d.assertionStrategy);
        node.put(TEST_NAMING, d.testNaming);
        node.put(VARIABLE_NAMING, d.variableNaming);
        node.putBoolean(TEST_COMMENTS, d.testComments);
        node.putInt(TEST_TIMEOUT, d.testTimeout);
        node.put(TEST_FORMAT, d.testFormat);
        node.put(TEST_SUFFIX, d.testSuffix);
        node.put(TEST_FOLDER, d.testFolder);
        node.putInt(CLIENT_MEMORY, d.clientMemory);
        node.put(EXTRA_ARGUMENTS, d.extraArguments);
        node.put(JAVA_HOME, d.javaHome);
        node.putBoolean(OPEN_GENERATED_TESTS, d.openGeneratedTests);
        node.putBoolean(CONFIGURE_BUILD_PATH, d.configureBuildPath);
        node.putBoolean(USE_POM_SETTINGS, d.usePomSettings);
        node.put(LLM_PROVIDER, d.llmProvider);
        node.put(LLM_MODEL, d.llmModel);
        node.put(LLM_BASE_URL, d.llmBaseUrl);
        node.put(LLM_APPROACH, d.llmApproach);
        node.put(LLM_SEEDING, d.llmSeeding);
        node.putBoolean(LLM_ON_STAGNATION, d.llmOnStagnation);
        node.putBoolean(LLM_OPERATORS, d.llmOperators);
        node.putBoolean(LLM_POSTPROCESSING, d.llmPostprocessing);
        node.putInt(LLM_MAX_CALLS, d.llmMaxCalls);
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

    /** The first of a list of preference nodes that has a value for a key. */
    private static final class Lookup {
        private final IEclipsePreferences[] nodes;

        Lookup(IEclipsePreferences... nodes) {
            this.nodes = nodes;
        }

        String getString(String key, String fallback) {
            for (IEclipsePreferences node : nodes) {
                String value = node.get(key, null);
                if (value != null) {
                    return value;
                }
            }
            return fallback;
        }

        int getInt(String key, int fallback) {
            try {
                return Integer.parseInt(getString(key, Integer.toString(fallback)).trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        boolean getBoolean(String key, boolean fallback) {
            return Boolean.parseBoolean(getString(key, Boolean.toString(fallback)).trim());
        }
    }

    /**
     * The settings that apply to a generation request in {@code project}. Project-specific
     * settings fall back to the defaults, not to the workspace: a preference store drops a value
     * equal to its default, so a project that sets the default explicitly must not silently pick
     * up a different workspace value instead.
     */
    public static GenerationOptions load(IProject project) {
        // Explicit nodes rather than IPreferencesService, which goes on to the remaining scopes
        // of its default order, the workspace among them, after the contexts it is given.
        Lookup prefs = usesProjectSettings(project)
                ? new Lookup(new ProjectScope(project).getNode(QUALIFIER), DefaultScope.INSTANCE.getNode(QUALIFIER))
                : new Lookup(InstanceScope.INSTANCE.getNode(QUALIFIER), DefaultScope.INSTANCE.getNode(QUALIFIER));
        GenerationOptions d = new GenerationOptions();
        GenerationOptions o = new GenerationOptions();
        o.searchBudget = prefs.getInt(SEARCH_BUDGET, d.searchBudget);
        o.criterion = prefs.getString(CRITERION, d.criterion);
        o.assertions = prefs.getBoolean(ASSERTIONS, d.assertions);
        o.minimize = prefs.getBoolean(MINIMIZE, d.minimize);
        o.assertionStrategy = prefs.getString(ASSERTION_STRATEGY, d.assertionStrategy);
        o.testNaming = prefs.getString(TEST_NAMING, d.testNaming);
        o.variableNaming = prefs.getString(VARIABLE_NAMING, d.variableNaming);
        o.testComments = prefs.getBoolean(TEST_COMMENTS, d.testComments);
        o.testTimeout = prefs.getInt(TEST_TIMEOUT, d.testTimeout);
        o.testFormat = prefs.getString(TEST_FORMAT, d.testFormat);
        o.testSuffix = prefs.getString(TEST_SUFFIX, d.testSuffix);
        o.testFolder = prefs.getString(TEST_FOLDER, d.testFolder);
        o.clientMemory = prefs.getInt(CLIENT_MEMORY, d.clientMemory);
        o.extraArguments = prefs.getString(EXTRA_ARGUMENTS, d.extraArguments);
        o.javaHome = prefs.getString(JAVA_HOME, d.javaHome);
        o.openGeneratedTests = prefs.getBoolean(OPEN_GENERATED_TESTS, d.openGeneratedTests);
        o.configureBuildPath = prefs.getBoolean(CONFIGURE_BUILD_PATH, d.configureBuildPath);
        o.usePomSettings = prefs.getBoolean(USE_POM_SETTINGS, d.usePomSettings);
        o.llmProvider = prefs.getString(LLM_PROVIDER, d.llmProvider);
        o.llmModel = prefs.getString(LLM_MODEL, d.llmModel).trim();
        o.llmBaseUrl = prefs.getString(LLM_BASE_URL, d.llmBaseUrl).trim();
        o.llmApproach = prefs.getString(LLM_APPROACH, d.llmApproach);
        o.llmSeeding = prefs.getString(LLM_SEEDING, d.llmSeeding);
        o.llmOnStagnation = prefs.getBoolean(LLM_ON_STAGNATION, d.llmOnStagnation);
        o.llmOperators = prefs.getBoolean(LLM_OPERATORS, d.llmOperators);
        o.llmPostprocessing = prefs.getBoolean(LLM_POSTPROCESSING, d.llmPostprocessing);
        o.llmMaxCalls = prefs.getInt(LLM_MAX_CALLS, d.llmMaxCalls);
        // Trimmed once here, so that every user of a setting sees the same value.
        o.criterion = o.criterion.trim();
        o.testSuffix = o.testSuffix.isBlank() ? d.testSuffix : o.testSuffix.trim();
        o.testFolder = o.testFolder.isBlank() ? d.testFolder : o.testFolder.trim();
        o.javaHome = o.javaHome.trim();
        return o;
    }
}
