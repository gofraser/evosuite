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
package org.evosuite.ide;

import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;

public final class SettingKeys {
    private SettingKeys() {}

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
    public static final List<String> MAIN =
            List.of(
                    "searchBudget",
                    "criterion",
                    "assertions",
                    "minimize",
                    "assertionStrategy",
                    "testFormat",
                    "testFolder",
                    "testSuffix",
                    "testNaming",
                    "variableNaming",
                    "testComments",
                    "testTimeout",
                    "openGeneratedTests");
    public static final List<String> LLM =
            List.of(
                    "llmProvider",
                    "llmModel",
                    "llmBaseUrl",
                    "llmApproach",
                    "llmSeeding",
                    "llmOnStagnation",
                    "llmOperators",
                    "llmPostprocessing",
                    "llmMaxCalls");
    public static final List<String> ADVANCED =
            List.of(
                    "javaHome",
                    "clientMemory",
                    "extraArguments",
                    "usePomSettings",
                    "configureBuildPath");

    public static List<String> all() {
        List<String> keys = new ArrayList<>(MAIN);
        keys.addAll(LLM);
        keys.addAll(ADVANCED);
        return List.copyOf(keys);
    }

    public static String defaultValue(String key) {
        return value(new GenerationOptions(), key);
    }

    public static String value(GenerationOptions options, String key) {
        try {
            return String.valueOf(GenerationOptions.class.getField(key).get(options));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalArgumentException(key, ex);
        }
    }

    public static GenerationOptions resolve(Map<String, String> values) {
        GenerationOptions options = new GenerationOptions();
        for (String key : all()) {
            String value = values.get(key);
            if (value == null || value.isBlank()) continue;
            try {
                Field field = GenerationOptions.class.getField(key);
                Object parsed =
                        field.getType() == int.class
                                ? Integer.valueOf(value.trim())
                                : field.getType() == boolean.class
                                        ? Boolean.valueOf(value)
                                        : value.trim();
                if (parsed instanceof Integer n && (n < 0 || key.equals("searchBudget") && n == 0))
                    throw new IllegalArgumentException(
                            key + " must be positive (or zero for defaults where allowed)");
                field.set(options, parsed);
            } catch (ReflectiveOperationException | NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid " + key + ": " + value, ex);
            }
        }
        if (EvoSuiteCommand.splitArguments(options.extraArguments).stream()
                .anyMatch(arg -> arg.startsWith("-Dllm_api_key=")))
            throw new IllegalArgumentException(
                    "Store the LLM API key in secure storage instead of Additional arguments.");
        validateOutput(options);
        return options;
    }

    public static void validateOutput(GenerationOptions options) {
        Path folder = Path.of(options.testFolder).normalize();
        if (folder.isAbsolute()
                || folder.startsWith("..")
                || folder.toString().isBlank()
                || folder.toString().equals("."))
            throw new IllegalArgumentException("Test folder must be a folder inside the module.");
        if (!options.testSuffix.matches("[A-Za-z_$][A-Za-z0-9_$]*"))
            throw new IllegalArgumentException("Test suffix must be a Java identifier.");
    }

    public static String junitFormat(boolean junit4, boolean jupiter) {
        return junit4 && !jupiter ? GenerationOptions.JUNIT4 : GenerationOptions.JUNIT5;
    }
}
