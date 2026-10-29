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

import java.lang.reflect.Field;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.evosuite.eclipse.Activator;
import org.evosuite.eclipse.launch.GenerationOptions;
import org.evosuite.eclipse.launch.SettingKeys;
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

    private EvoSuitePreferences() {
    }

    /** Writes {@link GenerationOptions}' defaults into the default scope. */
    static void initializeDefaults() {
        IEclipsePreferences node = DefaultScope.INSTANCE.getNode(QUALIFIER);
        node.putBoolean(USE_PROJECT_SETTINGS, false);
        for (String key : SettingKeys.all()) {
            node.put(key, SettingKeys.defaultValue(key));
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
        for (String key : SettingKeys.all()) {
            try {
                Field field = GenerationOptions.class.getField(key);
                if (field.getType() == int.class) {
                    field.setInt(o, prefs.getInt(key, field.getInt(d)));
                } else if (field.getType() == boolean.class) {
                    field.setBoolean(o, prefs.getBoolean(key, field.getBoolean(d)));
                } else {
                    field.set(o, prefs.getString(key, (String) field.get(d)));
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(key, e);
            }
        }
        o.llmModel = o.llmModel.trim();
        o.llmBaseUrl = o.llmBaseUrl.trim();
        // Trimmed once here, so that every user of a setting sees the same value.
        o.criterion = o.criterion.trim();
        o.randomSeed = o.randomSeed.trim();
        o.testSuffix = o.testSuffix.isBlank() ? d.testSuffix : o.testSuffix.trim();
        o.testFolder = o.testFolder.isBlank() ? d.testFolder : o.testFolder.trim();
        o.javaHome = o.javaHome.trim();
        return o;
    }
}
