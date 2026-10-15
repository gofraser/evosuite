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
package org.evosuite.eclipse.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.evosuite.eclipse.launch.GenerationOptions;

/**
 * The configuration of the EvoSuite Maven plug-in in a project's pom, as it bears on generating
 * tests for single classes in the IDE. Applied over the IDE settings, so that a class gets the
 * same budget, criteria, arguments and test folder as with {@code mvn evosuite:generate}. Unset
 * parameters take the Maven plug-in's defaults for the same reason.
 *
 * <p>Each parameter is resolved as Maven resolves it for the {@code generate} goal: the
 * configuration of a {@code generate} execution, then the plug-in's own configuration, then the
 * pom property {@code evosuite.<name>}, then the deprecated unprefixed property the Maven plug-in
 * still reads, then the Maven plug-in's default. The test folder also looks at a {@code setup}
 * execution, which is where the folder is registered as a test source root.
 *
 * <p>Parameters about whole-module runs (cores apart from dividing memory, the project budget,
 * the schedule, class selection) have no counterpart for a single class and are not used.
 */
public final class PomSettings {

    public static final String GROUP_ID = "org.evosuite.plugins";
    public static final String ARTIFACT_ID = "evosuite-maven-plugin";

    /** The Maven plug-in's defaults, from its GenerateMojo. */
    static final int DEFAULT_MINUTES_PER_CLASS = 2;
    static final String DEFAULT_CRITERION = "LINE:BRANCH:EXCEPTION:WEAKMUTATION:OUTPUT:METHOD:METHODNOEXCEPTION:CBRANCH";
    static final String DEFAULT_TEST_FOLDER = "src/test/evosuite";

    /** Deprecated property names that the Maven plug-in still reads, by parameter. */
    private static final Map<String, String> LEGACY_PROPERTIES = Map.of(
            "memoryInMB", "memoryInMB", "cores", "cores", "timeInMinutesPerClass", "timeInMinutesPerClass",
            "criterion", "criterion", "extraArgs", "extraArgs", "testFolder", "targetFolder");

    /**
     * How entries of the {@code evosuiteProperties} map appear among the configuration values:
     * {@code evosuiteProperties.llm_provider} for {@code <evosuiteProperties><llm_provider>}.
     */
    public static final String PROPERTY_PREFIX = "evosuiteProperties.";

    private final Map<String, String> plugin;
    private final Map<String, String> generate;
    private final Map<String, String> setup;
    private final Map<String, String> properties;

    /**
     * @param plugin     the plug-in's own configuration parameters, by element name
     * @param generate   the configuration of executions of the {@code generate} goal
     * @param setup      the configuration of executions of the {@code setup} goal
     * @param properties the pom's properties
     */
    public PomSettings(Map<String, String> plugin, Map<String, String> generate, Map<String, String> setup,
                       Map<String, String> properties) {
        this.plugin = canonical(plugin);
        this.generate = canonical(generate);
        this.setup = canonical(setup);
        this.properties = Map.copyOf(properties);
    }

    /** Only configured in the plug-in itself, with no executions or properties. */
    public PomSettings(Map<String, String> plugin) {
        this(plugin, Map.of(), Map.of(), Map.of());
    }

    /** The cores parameter is named numberOfCores in the Maven plug-in, with cores as an alias. */
    private static Map<String, String> canonical(Map<String, String> configuration) {
        Map<String, String> result = new HashMap<>(configuration);
        String cores = result.remove("numberOfCores");
        if (cores != null) {
            result.putIfAbsent("cores", cores);
        }
        return result;
    }

    /**
     * Overrides {@code options} with what the pom determines.
     *
     * @param basedir the module's base directory, against which the test folder resolves
     * @return one line per setting taken from the pom, for the user
     */
    public List<String> applyTo(GenerationOptions options, File basedir) {
        List<String> applied = new ArrayList<>();

        Integer minutes = integer("timeInMinutesPerClass", applied);
        options.searchBudget = 60 * (minutes == null || minutes < 1 ? DEFAULT_MINUTES_PER_CLASS : minutes);
        applied.add("search budget " + options.searchBudget + " s (timeInMinutesPerClass)");

        options.criterion = value("criterion", DEFAULT_CRITERION);
        applied.add("criteria " + options.criterion + " (criterion)");

        options.testFolder = relativeFolder(value("testFolder", DEFAULT_TEST_FOLDER), basedir);
        applied.add("test folder " + options.testFolder + " (testFolder)");

        Map<String, String> evosuiteProperties = evosuiteProperties();
        if (!evosuiteProperties.isEmpty()) {
            options.pomProperties = evosuiteProperties;
            applied.add("properties " + String.join(", ", evosuiteProperties.keySet()) + " (evosuiteProperties)");
        }

        String extraArgs = value("extraArgs", "");
        if (!extraArgs.isEmpty()) {
            // After the IDE's own arguments, so that the pom's win where both set a property.
            options.pomExtraArguments = extraArgs;
            applied.add("arguments " + extraArgs + " (extraArgs)");
        }

        String serverId = value("llmApiKeyServerId", "");
        if (!serverId.isEmpty()) {
            options.llmApiKeyServerId = serverId;
            applied.add("LLM API key of server " + serverId + " in Maven's settings.xml (llmApiKeyServerId)");
        }

        // Only when the pom sets it: the Maven plug-in's default of 800 MB is the budget of a
        // whole-module run, too little for the heap of a single client.
        Integer memory = integer("memoryInMB", applied);
        if (memory != null && memory > 0) {
            // The Maven plug-in shares this memory among its cores; one class runs on one.
            Integer cores = integer("cores", applied);
            options.clientMemory = memory / Math.max(1, cores == null ? 1 : cores);
            applied.add("client heap " + options.clientMemory + " MB (memoryInMB / cores)");
        }
        return applied;
    }

    /**
     * The {@code evosuiteProperties} map. Maven merges a map parameter entry by entry, so a
     * {@code generate} execution's entries override the plug-in's and add to them. Values are
     * not trimmed apart from their ends, since they may contain spaces.
     */
    private Map<String, String> evosuiteProperties() {
        Map<String, String> merged = new LinkedHashMap<>();
        for (Map<String, String> source : List.of(plugin, generate)) {
            source.forEach((key, value) -> {
                if (key.startsWith(PROPERTY_PREFIX) && key.length() > PROPERTY_PREFIX.length()) {
                    merged.put(key.substring(PROPERTY_PREFIX.length()), value == null ? "" : value.trim());
                }
            });
        }
        return merged;
    }

    private String value(String name, String fallback) {
        String value = lookup(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** The parameter's value, or {@code null} if nothing sets it. */
    private String lookup(String name) {
        List<String> candidates = new ArrayList<>();
        candidates.add(generate.get(name));
        candidates.add(plugin.get(name));
        if ("testFolder".equals(name)) {
            candidates.add(setup.get(name));
        }
        candidates.add(properties.get("evosuite." + name));
        String legacy = LEGACY_PROPERTIES.get(name);
        if (legacy != null) {
            candidates.add(properties.get(legacy));
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private Integer integer(String name, List<String> applied) {
        String value = lookup(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            applied.add("ignored " + name + "=" + value.trim() + ", which is not a number");
            return null;
        }
    }

    /** The Maven plug-in also accepts an absolute folder; the IDE needs one inside the project. */
    private static String relativeFolder(String folder, File basedir) {
        Path path = Path.of(folder);
        if (path.isAbsolute() && basedir != null) {
            Path base = basedir.toPath().toAbsolutePath().normalize();
            Path normalized = path.normalize();
            if (normalized.startsWith(base)) {
                return base.relativize(normalized).toString().replace(File.separatorChar, '/');
            }
        }
        return folder;
    }
}
