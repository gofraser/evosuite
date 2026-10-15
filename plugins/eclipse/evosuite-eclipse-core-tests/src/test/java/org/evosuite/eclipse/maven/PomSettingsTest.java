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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.evosuite.eclipse.launch.GenerationOptions;
import org.junit.jupiter.api.Test;

public class PomSettingsTest {

    private static final File BASEDIR = new File("/work/module");

    private static GenerationOptions apply(Map<String, String> configuration, GenerationOptions options) {
        new PomSettings(configuration).applyTo(options, BASEDIR);
        return options;
    }

    @Test
    public void unsetParametersTakeTheMavenPluginsDefaults() {
        GenerationOptions ide = new GenerationOptions();
        ide.searchBudget = 30;
        ide.criterion = "LINE";
        ide.testFolder = "generated";
        ide.clientMemory = 4000;
        GenerationOptions options = apply(Map.of(), ide);
        assertEquals(120, options.searchBudget);
        assertEquals(PomSettings.DEFAULT_CRITERION, options.criterion);
        assertEquals("src/test/evosuite", options.testFolder);
        // Memory has no per-class meaning unless the pom sets it.
        assertEquals(4000, options.clientMemory);
    }

    @Test
    public void explicitParametersOverrideTheIdeSettings() {
        GenerationOptions ide = new GenerationOptions();
        ide.extraArguments = "-Dsandbox=false -Dllm_provider=OLLAMA";
        GenerationOptions options = apply(Map.of("timeInMinutesPerClass", "3", "criterion", "BRANCH",
                "testFolder", "src/test/generated", "extraArgs", "-Dllm_provider=OPENAI",
                "memoryInMB", "3000", "cores", "2"), ide);
        assertEquals(180, options.searchBudget);
        assertEquals("BRANCH", options.criterion);
        assertEquals("src/test/generated", options.testFolder);
        // Kept apart from the IDE's, so that the command line can put them last.
        assertEquals("-Dsandbox=false -Dllm_provider=OLLAMA", options.extraArguments);
        assertEquals("-Dllm_provider=OPENAI", options.pomExtraArguments);
        assertEquals(1500, options.clientMemory);
    }

    @Test
    public void numberOfCoresIsTheParameterAndCoresItsAlias() {
        assertEquals(1000, apply(Map.of("memoryInMB", "3000", "numberOfCores", "3"), new GenerationOptions()).clientMemory);
        assertEquals(1500, apply(Map.of("memoryInMB", "3000", "cores", "2"), new GenerationOptions()).clientMemory);
    }

    @Test
    public void resolvesParametersInMavensOrder() {
        Map<String, String> plugin = Map.of("criterion", "LINE", "testFolder", "plugin-folder");
        Map<String, String> generate = Map.of("criterion", "BRANCH");
        Map<String, String> setup = Map.of("testFolder", "setup-folder", "criterion", "IGNORED");
        Map<String, String> properties = Map.of("evosuite.criterion", "METHOD", "evosuite.timeInMinutesPerClass", "4",
                "evosuite.extraArgs", "-Dx=1", "extraArgs", "-Dold=1", "memoryInMB", "1200");
        GenerationOptions options = new GenerationOptions();
        new PomSettings(plugin, generate, setup, properties).applyTo(options, BASEDIR);
        assertEquals("BRANCH", options.criterion, "a generate execution first");
        assertEquals("plugin-folder", options.testFolder, "then the plug-in, before a setup execution");
        assertEquals(240, options.searchBudget, "then evosuite.* properties");
        assertEquals("-Dx=1", options.pomExtraArguments, "before the deprecated names");
        assertEquals(1200, options.clientMemory, "which still count when nothing else is set");
    }

    @Test
    public void aTestFolderSetOnlyForSetupIsUsed() {
        GenerationOptions options = new GenerationOptions();
        new PomSettings(Map.of(), Map.of(), Map.of("testFolder", "src/test/gen"), Map.of()).applyTo(options, BASEDIR);
        assertEquals("src/test/gen", options.testFolder);
        new PomSettings(Map.of(), Map.of(), Map.of(), Map.of("targetFolder", "legacy")).applyTo(options, BASEDIR);
        assertEquals("legacy", options.testFolder);
    }

    @Test
    public void mergesEvosuitePropertiesEntryByEntry() {
        Map<String, String> plugin = Map.of("evosuiteProperties.llm_provider", "OLLAMA",
                "evosuiteProperties.llm_model", "qwen3-coder", "llmApiKeyServerId", "plugin-server");
        Map<String, String> generate = Map.of("evosuiteProperties.llm_model", " big model ",
                "evosuiteProperties.llm_on_stagnation", "true");
        GenerationOptions options = new GenerationOptions();
        List<String> applied = new PomSettings(plugin, generate, Map.of(),
                Map.of("evosuite.llmApiKeyServerId", "property-server", "llmApiKeyServerId", "not-a-legacy-name"))
                .applyTo(options, BASEDIR);
        assertEquals(Map.of("llm_provider", "OLLAMA", "llm_model", "big model", "llm_on_stagnation", "true"),
                options.pomProperties);
        assertEquals("plugin-server", options.llmApiKeyServerId);
        assertTrue(applied.stream().anyMatch(a -> a.endsWith("(evosuiteProperties)")), applied.toString());

        GenerationOptions fromProperty = new GenerationOptions();
        new PomSettings(Map.of(), Map.of(), Map.of(), Map.of("evosuite.llmApiKeyServerId", "property-server"))
                .applyTo(fromProperty, BASEDIR);
        assertEquals("property-server", fromProperty.llmApiKeyServerId);
        GenerationOptions legacy = new GenerationOptions();
        new PomSettings(Map.of(), Map.of(), Map.of(), Map.of("llmApiKeyServerId", "x")).applyTo(legacy, BASEDIR);
        assertEquals("", legacy.llmApiKeyServerId, "the Maven plug-in has no unprefixed property for it");
    }

    @Test
    public void reportsWhatItTook() {
        List<String> applied = new PomSettings(Map.of("timeInMinutesPerClass", "soon", "extraArgs", "-Dx=1"))
                .applyTo(new GenerationOptions(), BASEDIR);
        assertTrue(applied.contains("ignored timeInMinutesPerClass=soon, which is not a number"), applied.toString());
        assertTrue(applied.contains("search budget 120 s (timeInMinutesPerClass)"), applied.toString());
        assertTrue(applied.contains("arguments -Dx=1 (extraArgs)"), applied.toString());
    }

    @Test
    public void anAbsoluteTestFolderInsideTheModuleBecomesRelative() {
        String inside = new File(BASEDIR, "src/test/gen").getAbsolutePath();
        assertEquals("src/test/gen", apply(Map.of("testFolder", inside), new GenerationOptions()).testFolder);
        String outside = new File("/elsewhere/tests").getAbsolutePath();
        assertEquals(outside, apply(Map.of("testFolder", outside), new GenerationOptions()).testFolder);
    }
}
