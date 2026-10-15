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
package org.evosuite.eclipse.launch;

import java.util.List;
import java.util.Map;

/**
 * The settings of one generation request, resolved from the project's or the workspace's
 * preferences. Plain data with no Eclipse types, so that building the command line can be tested
 * without a workbench.
 */
public final class GenerationOptions {

    public static final String JUNIT5 = "JUNIT5";
    public static final String JUNIT4 = "JUNIT4";

    /** {@link #llmProvider} values, as EvoSuite's {@code llm_provider} names them. */
    public static final String LLM_NONE = "NONE";
    public static final String LLM_OPENAI = "OPENAI";
    public static final String LLM_ANTHROPIC = "ANTHROPIC";
    public static final String LLM_OLLAMA = "OLLAMA";

    /** {@link #llmApproach} values. */
    public static final String APPROACH_SEARCH_ONLY = "SEARCH_ONLY";
    public static final String APPROACH_SEARCH = "SEARCH";
    public static final String APPROACH_LLM_SINGLE_PROMPT = "LLM_SINGLE_PROMPT";
    public static final String APPROACH_LLM_ITERATIVE = "LLM_ITERATIVE";

    /** Where a local Ollama server listens unless told otherwise. */
    public static final String OLLAMA_DEFAULT_URL = "http://localhost:11434";

    /** Seconds of search per class. */
    public int searchBudget = 60;
    /** Colon-separated coverage criteria; empty means EvoSuite's default set. */
    public String criterion = "";
    public boolean assertions = true;
    public boolean minimize = true;
    /** EvoSuite's {@code assertion_strategy}: MUTATION keeps those that detect faults, ALL keeps all. */
    public String assertionStrategy = "MUTATION";
    /** EvoSuite's {@code test_naming_strategy}: NUMBERED (test0, ...) or COVERAGE (from what a test covers). */
    public String testNaming = "NUMBERED";
    /** EvoSuite's {@code variable_naming_strategy}: TYPE_BASED (int0, ...) or HEURISTICS_BASED. */
    public String variableNaming = "TYPE_BASED";
    /** Comment each test with the coverage goals it covers. */
    public boolean testComments = false;
    /** Milliseconds a generated test may run; 0 leaves EvoSuite's default of 3000. */
    public int testTimeout = 0;
    /** {@link #JUNIT5} or {@link #JUNIT4}. */
    public String testFormat = JUNIT5;
    public String testSuffix = "_ESTest";
    /** Project-relative folder that receives the generated tests. */
    public String testFolder = "src/test/evosuite";
    /** Heap of the EvoSuite client JVM in megabytes; 0 leaves EvoSuite's default. */
    public int clientMemory = 0;
    /** Further EvoSuite arguments, e.g. {@code -Dsandbox=false}, split like a shell would. */
    public String extraArguments = "";
    /** Java installation that runs EvoSuite; empty means the project's JRE. */
    public String javaHome = "";
    public boolean openGeneratedTests = true;
    /** Add the test folder, JUnit and the EvoSuite runtime to the build path when missing. */
    public boolean configureBuildPath = true;
    /** Let the EvoSuite Maven plug-in's configuration in the pom override these settings. */
    public boolean usePomSettings = true;
    /** Not a setting: which values came from the pom, one line each, for the user. */
    public List<String> fromPom = List.of();
    /** From the pom's {@code evosuiteProperties}: EvoSuite properties, values may contain spaces. */
    public Map<String, String> pomProperties = Map.of();
    /** From the pom's {@code extraArgs}; they win over everything else, as with Maven. */
    public String pomExtraArguments = "";
    /**
     * From the pom's {@code llmApiKeyServerId}: the server in Maven's settings.xml whose password
     * is the LLM API key. Only the id is kept here; the key is looked up when a run starts.
     */
    public String llmApiKeyServerId = "";

    // LLM support. The API key is not a setting: it lives in secure storage (see LlmApiKey).

    /** {@link #LLM_NONE} turns every LLM feature off, whatever the settings below say. */
    public String llmProvider = LLM_NONE;
    /** Empty means EvoSuite's default model, which only suits OpenAI. */
    public String llmModel = "";
    /** Empty means the provider's public endpoint, or {@link #OLLAMA_DEFAULT_URL} for Ollama. */
    public String llmBaseUrl = "";
    /** Search with LLM assistance, or generate with the LLM alone. */
    public String llmApproach = APPROACH_SEARCH;
    /** EvoSuite's {@code llm_seeding_profile}: OFF, MIN, STANDARD or FULL. */
    public String llmSeeding = "OFF";
    /** Ask the LLM for tests when the search stops improving. */
    public boolean llmOnStagnation = false;
    /** LLM-based mutation and crossover in the many-objective search. */
    public boolean llmOperators = false;
    /** Let the LLM improve names, comments and assertions of the final tests. */
    public boolean llmPostprocessing = false;
    /** LLM calls per class; 0 means unlimited. */
    public int llmMaxCalls = 0;

    /** Whether the LLM settings of the IDE switch it on; see {@link #effectiveLlmProvider}. */
    public boolean usesLlm() {
        return !LLM_NONE.equals(llmProvider) && !llmSwitchedOff();
    }

    /**
     * Whether the approach is search only: the LLM stays off whatever else is configured, the
     * pom and the additional arguments included, so that the switch can be relied on to keep
     * the project's code from being sent to a model. The LLM settings themselves are kept.
     */
    public boolean llmSwitchedOff() {
        return APPROACH_SEARCH_ONLY.equals(llmApproach);
    }

    /**
     * The LLM provider EvoSuite will use: arguments and pom properties can set {@code llm_provider}
     * too, and the last one on the command line wins.
     */
    public String effectiveLlmProvider() {
        if (llmSwitchedOff()) {
            return LLM_NONE;
        }
        return effective("llm_provider", llmProvider).toUpperCase(java.util.Locale.ROOT);
    }

    /** The model EvoSuite will use, decided like {@link #effectiveLlmProvider}. */
    public String effectiveLlmModel() {
        return effective("llm_model", llmModel);
    }

    /** The value EvoSuite ends up with for {@code property}, in command-line order. */
    private String effective(String property, String fromSettings) {
        String value = fromSettings;
        String prefix = "-D" + property + "=";
        for (String argument : EvoSuiteCommand.splitArguments(extraArguments)) {
            if (argument.startsWith(prefix)) {
                value = argument.substring(prefix.length());
            }
        }
        if (pomProperties.containsKey(property)) {
            value = pomProperties.get(property);
        }
        for (String argument : EvoSuiteCommand.splitArguments(pomExtraArguments)) {
            if (argument.startsWith(prefix)) {
                value = argument.substring(prefix.length());
            }
        }
        return value.trim();
    }
}
