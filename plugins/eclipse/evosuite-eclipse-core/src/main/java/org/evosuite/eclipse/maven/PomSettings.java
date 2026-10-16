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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.evosuite.eclipse.launch.GenerationOptions;

/**
 * The configuration of the EvoSuite Maven plug-in in a project's pom, as it bears on generating
 * tests for single classes in the IDE. Applied over the IDE settings, so that a class gets the
 * same budget, criteria, arguments and test folder as with {@code mvn evosuite:generate}. Unset
 * parameters take the Maven plug-in's defaults for the same reason.
 *
 * <p>Each parameter is resolved as Maven resolves it for {@code mvn evosuite:generate}: the
 * configuration of the execution {@code default-cli}, then the plug-in's own configuration, then
 * the pom property {@code evosuite.<name>}, then the Maven plug-in's default. A deprecated
 * unprefixed property that the Maven plug-in still reads overrides all of these unless the pom
 * also sets {@code evosuite.<name>}, as the plug-in applies it after Maven has configured the
 * goal. Other executions of
 * the {@code generate} goal do not apply to a goal invoked by name. Where the goal would use its
 * default test folder, the folder of a {@code setup} execution is taken instead, since that is
 * the folder the build registers as a test source root.
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
    private final boolean junit4Only;
    private final boolean loaded;

    /**
     * Stands for a pom that m2e has not loaded yet, when loading it would block the UI thread.
     * Whether it configures the plug-in is unknown; it changes no settings.
     */
    public static final PomSettings NOT_LOADED = new PomSettings(Map.of(), Map.of(), Map.of(), Map.of(), false,
            false);

    /**
     * @param plugin     the plug-in's own configuration parameters, by element name
     * @param generate   the configuration of the execution {@code default-cli}, which
     *                   {@code mvn evosuite:generate} runs
     * @param setup      the configuration of executions of the {@code setup} goal
     * @param properties the pom's properties
     * @param junit4Only whether the project depends on junit:junit but not on junit-jupiter-api,
     *                   for which the Maven plug-in generates JUnit 4 tests
     */
    public PomSettings(Map<String, String> plugin, Map<String, String> generate, Map<String, String> setup,
                       Map<String, String> properties, boolean junit4Only) {
        this(plugin, generate, setup, properties, junit4Only, true);
    }

    private PomSettings(Map<String, String> plugin, Map<String, String> generate, Map<String, String> setup,
                        Map<String, String> properties, boolean junit4Only, boolean loaded) {
        this.loaded = loaded;
        this.plugin = canonical(plugin);
        this.generate = canonical(generate);
        this.setup = canonical(setup);
        this.properties = Map.copyOf(properties);
        this.junit4Only = junit4Only;
    }

    public PomSettings(Map<String, String> plugin, Map<String, String> generate, Map<String, String> setup,
                       Map<String, String> properties) {
        this(plugin, generate, setup, properties, false);
    }

    /** Only configured in the plug-in itself, with no executions or properties. */
    public PomSettings(Map<String, String> plugin) {
        this(plugin, Map.of(), Map.of(), Map.of());
    }

    /** Whether the pom was read; {@code false} only for {@link #NOT_LOADED}. */
    public boolean isLoaded() {
        return loaded;
    }

    /** The Maven plug-in's shortest time per class, from its GenerationSettings. */
    static final int MIN_SECONDS_PER_CLASS = 60;

    private static final Pattern DURATION = Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?");

    /**
     * A duration as the Maven plug-in's {@code timePerClass} takes it, eg {@code 90s}, {@code 2m},
     * {@code 1m30s} or {@code 1h}, in seconds; -1 if it is not one.
     */
    static int parseSeconds(String duration) {
        String value = duration == null ? "" : duration.trim().toLowerCase(Locale.ROOT);
        Matcher m = DURATION.matcher(value);
        if (value.isEmpty() || !m.matches()) {
            return -1;
        }
        long seconds = 0;
        if (m.group(1) != null) {
            seconds += 3600L * Long.parseLong(m.group(1));
        }
        if (m.group(2) != null) {
            seconds += 60L * Long.parseLong(m.group(2));
        }
        if (m.group(3) != null) {
            seconds += Long.parseLong(m.group(3));
        }
        return seconds > Integer.MAX_VALUE ? -1 : (int) seconds;
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
        if (!loaded) {
            return applied;
        }

        // timePerClass takes precedence; the Maven plug-in fails the build on a bad value, which
        // the IDE cannot usefully do, so it says so and falls back to timeInMinutesPerClass.
        String timePerClass = value("timePerClass", "");
        int seconds = timePerClass.isEmpty() ? -1 : parseSeconds(timePerClass);
        if (seconds >= MIN_SECONDS_PER_CLASS) {
            options.searchBudget = seconds;
            applied.add("search budget " + seconds + " s (timePerClass)");
        } else {
            if (!timePerClass.isEmpty()) {
                applied.add("ignored timePerClass=" + timePerClass + ", which is not a duration of at least "
                        + MIN_SECONDS_PER_CLASS + "s such as 90s, 2m or 1m30s");
            }
            Integer minutes = integer("timeInMinutesPerClass", applied);
            options.searchBudget = 60 * (minutes == null || minutes < 1 ? DEFAULT_MINUTES_PER_CLASS : minutes);
            applied.add("search budget " + options.searchBudget + " s (timeInMinutesPerClass)");
        }

        options.criterion = value("criterion", DEFAULT_CRITERION);
        applied.add("criteria " + options.criterion + " (criterion)");

        options.testFolder = relativeFolder(value("testFolder", DEFAULT_TEST_FOLDER), basedir);
        applied.add("test folder " + options.testFolder + " (testFolder)");

        Map<String, String> evosuiteProperties = evosuiteProperties();
        if (!evosuiteProperties.isEmpty()) {
            options.pomProperties = evosuiteProperties;
            applied.add("properties " + String.join(", ", evosuiteProperties.keySet()) + " (evosuiteProperties)");
        }
        // The format decides which JUnit library the IDE adds, so it must follow the pom too.
        String format = evosuiteProperties.get("test_format");
        if (format != null && !format.isBlank()) {
            options.testFormat = format.trim().toUpperCase(Locale.ROOT);
        } else if (junit4Only) {
            options.testFormat = GenerationOptions.JUNIT4;
            applied.add("JUnit 4 tests, as the project has JUnit 4 but no junit-jupiter-api");
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
     * {@code default-cli} execution's entries override the plug-in's and add to them. Values are
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
        // As the Maven plug-in's legacyProperty: the old name counts unless the new one is set,
        // whatever its value, and then replaces what Maven injected from the configuration.
        String legacy = LEGACY_PROPERTIES.get(name);
        if (legacy != null && !properties.containsKey("evosuite." + name)) {
            candidates.add(properties.get(legacy));
        }
        candidates.add(generate.get(name));
        candidates.add(plugin.get(name));
        candidates.add(properties.get("evosuite." + name));
        if ("testFolder".equals(name)) {
            candidates.add(setup.get(name));
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
