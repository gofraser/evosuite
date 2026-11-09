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
package org.evosuite.intellij.settings;

import org.evosuite.ide.*;

import java.util.*;

/** A redacted, ordered explanation of the values passed to the engine. */
public final class EffectiveConfiguration {
    public record Value(String setting, String property, String value, String source) {}

    private static final Map<String, String> PROPERTIES =
            Map.ofEntries(
                    Map.entry("searchBudget", "search_budget"),
                    Map.entry("randomSeed", "random_seed"),
                    Map.entry("criterion", "criterion"),
                    Map.entry("minimizeValues", "minimize_values"),
                    Map.entry("noRuntimeDependency", "no_runtime_dependency"),
                    Map.entry("testFormat", "test_format"),
                    Map.entry("testFolder", "test_dir"),
                    Map.entry("testSuffix", "junit_suffix"),
                    Map.entry("assertionStrategy", "assertion_strategy"),
                    Map.entry("testNaming", "test_naming_strategy"),
                    Map.entry("variableNaming", "variable_naming_strategy"),
                    Map.entry("testComments", "test_comments"),
                    Map.entry("testTimeout", "timeout"),
                    Map.entry("llmProvider", "llm_provider"),
                    Map.entry("llmModel", "llm_model"),
                    Map.entry("llmBaseUrl", "llm_base_url"),
                    Map.entry("llmContextMode", "llm_sut_context_mode"),
                    Map.entry("llmMaxTokens", "llm_max_tokens"),
                    Map.entry("llmTimeoutSeconds", "llm_timeout_seconds"),
                    Map.entry("llmRetryMaxAttempts", "llm_retry_max_attempts"),
                    Map.entry("llmTemperature", "llm_temperature"),
                    Map.entry("llmMaxCalls", "llm_max_calls"),
                    Map.entry("llmSeeding", "llm_seeding_profile"),
                    Map.entry("llmOnStagnation", "llm_on_stagnation"),
                    Map.entry("llmOperators", "llm_operator_mode"),
                    Map.entry("llmPostprocessing", "llm_postprocessing_enabled"));

    private EffectiveConfiguration() {}

    private static boolean credentialProperty(String property) {
        String normalized = property.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return !normalized.equals("llmmaxtokens")
                && normalized.matches(".*(key|password|secret|token).*");
    }

    private static void properties(Map<String, Value> rows, List<String> arguments, String source) {
        for (String arg : arguments) {
            if (!arg.startsWith("-D") || !arg.contains("=")) continue;
            int split = arg.indexOf('=');
            String property = arg.substring(2, split);
            if (credentialProperty(property)) continue;
            String setting =
                    PROPERTIES.entrySet().stream()
                            .filter(e -> e.getValue().equals(property))
                            .map(Map.Entry::getKey)
                            .findFirst()
                            .orElse(property);
            rows.put(property, new Value(setting, property, arg.substring(split + 1), source));
        }
    }

    public static List<Value> describe(
            GenerationOptions options, Map<String, String> stored, String scope) {
        Map<String, Value> rows = new LinkedHashMap<>();
        for (String key : SettingKeys.all()) {
            if (key.equals("extraArguments")) continue;
            String property = PROPERTIES.getOrDefault(key, key);
            String source =
                    stored.containsKey(key) && !stored.get(key).isBlank()
                            ? scope
                            : "EvoSuite default";
            if (!options.fromPom.isEmpty()
                    && (key.equals("searchBudget")
                            || key.equals("criterion")
                            || key.equals("testFolder"))) source = "Maven configuration/default";
            rows.put(
                    property,
                    new Value(
                            key,
                            property,
                            key.equals("llmOperators")
                                    ? options.llmOperators ? "SYNC" : "OFF"
                                    : SettingKeys.value(options, key),
                            source));
        }
        properties(
                rows,
                EvoSuiteCommand.splitArguments(options.extraArguments),
                "Additional arguments");
        properties(
                rows,
                options.pomProperties.entrySet().stream()
                        .map(e -> "-D" + e.getKey() + "=" + e.getValue())
                        .toList(),
                "Maven properties");
        properties(
                rows,
                EvoSuiteCommand.splitArguments(options.pomExtraArguments),
                "Maven additional arguments");
        // Export locations and Search-only are deliberately pinned after overrides.
        rows.put(
                "test_dir",
                new Value(
                        "testFolder",
                        "test_dir",
                        options.testFolder,
                        "IDE output location (pinned)"));
        rows.put(
                "junit_suffix",
                new Value(
                        "testSuffix",
                        "junit_suffix",
                        options.testSuffix,
                        "IDE output name (pinned)"));
        if (options.llmSwitchedOff())
            rows.put(
                    "llm_provider",
                    new Value("llmProvider", "llm_provider", "NONE", "Search-only policy"));
        Value model = rows.get("llm_model");
        if (model != null && model.value().isBlank()) {
            String env = System.getenv("EVOSUITE_LLM_MODEL");
            rows.put(
                    "llm_model",
                    new Value(
                            "llmModel",
                            "llm_model",
                            env == null || env.isBlank() ? "Provider default" : env,
                            env == null || env.isBlank()
                                    ? "Provider default"
                                    : "EVOSUITE_LLM_MODEL"));
        }
        return List.copyOf(rows.values());
    }

    public static Map<String, String> savedValues(GenerationOptions options) {
        Map<String, String> result = new TreeMap<>();
        for (String key : SettingKeys.all()) result.put(key, SettingKeys.value(options, key));
        // Keep only overrides here. SettingKeys already saves the typed values.
        var args = EvoSuiteCommand.additionalArguments(options).stream()
                        .filter(
                                a ->
                                        !a.matches(
                                                "-D(?:test_dir|junit_suffix|report_dir|llm_trace_dir)=.*"))
                        .filter(
                                a ->
                                        !a.startsWith("-D")
                                                || !a.contains("=")
                                                || !credentialProperty(
                                                        a.substring(2, a.indexOf('='))))
                        .toList();
        result.put(
                "extraArguments",
                args.stream()
                        .map(a -> "\"" + a.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                        .collect(java.util.stream.Collectors.joining(" ")));
        result.put("usePomSettings", "false");
        return result;
    }
}
