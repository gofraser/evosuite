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
package org.evosuite.llm;

import com.google.gson.Gson;
import org.evosuite.Properties;
import org.evosuite.llm.prompt.CoverageGoalFormatter;
import org.evosuite.llm.prompt.PromptBuilder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.prompt.SutContextProviderFactory;
import org.evosuite.llm.prompt.SystemPromptProvider;
import org.evosuite.llm.prompt.TestCaseFormatter;
import org.evosuite.llm.prompt.TestClusterSummarizer;
import org.evosuite.setup.TestCluster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the prompt-context fields of the trace record written for a request, with the values the
 * prompt carried when it was built. The external analysis scripts read these keys.
 */
class LlmTraceContextMetadataTest {

    @TempDir
    Path tempDir;

    private Map<String, Object> traceOf(PromptResult prompt) throws Exception {
        LlmConfiguration config = new LlmConfiguration(
                Properties.LlmProvider.OPENAI,
                "mock-model", "", "", 0.0, 256, 30, 0, 1,
                true, tempDir, "run-ctx-fields");
        LlmTraceRecorder recorder = new LlmTraceRecorder(config);
        LlmService service = new LlmService((messages, feature) -> new LlmResponse("ok", 1, 1),
                LlmBudgetCoordinator.of(5), config, new LlmStatistics(), recorder);
        try {
            service.query(LlmRequest.of(prompt, LlmFeature.SEEDING));
        } finally {
            service.close();
        }
        List<String> lines = Files.readAllLines(recorder.getTraceFile(), StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> record = new Gson().fromJson(lines.get(lines.size() - 1), Map.class);
        return record;
    }

    @Test
    void everyContextFieldReachesTheTraceRecord() throws Exception {
        PromptResult.DependencySummaryMetadata metadata = new PromptResult.DependencySummaryMetadata(
                12000, 800, true, true, "dynamic_scaled",
                40, 12, 5, 4, 3,
                18, 24, 7, 1);
        PromptResult prompt = new PromptResult.Builder()
                .messages(Arrays.asList(LlmMessage.system("sys"), LlmMessage.user("usr")))
                .sutContextMode(Properties.LlmSutContextMode.DECOMPILED_SOURCE)
                .contextUnavailable(true)
                .contextTruncated(true)
                .contextCommentsStripped(true)
                .contextSelectivelyTruncated(true)
                .clusterSummaryTruncated(true)
                .clusterSummaryChars(4321)
                .dependencySummaryMetadata(metadata)
                .build();

        Map<String, Object> record = traceOf(prompt);

        assertEquals("DECOMPILED_SOURCE", record.get("sut_context_mode"));
        assertEquals(true, record.get("context_unavailable"));
        assertEquals(true, record.get("context_truncated"));
        assertEquals(true, record.get("context_comments_stripped"));
        assertEquals(true, record.get("context_selectively_truncated"));
        assertEquals(true, record.get("cluster_summary_truncated"));
        assertEquals(4321.0, record.get("cluster_summary_chars"));
        assertEquals(12000.0, record.get("cluster_summary_budget_chars"));
        assertEquals(800.0, record.get("cluster_summary_per_class_cap_chars"));
        assertEquals(true, record.get("cluster_summary_per_class_cap_auto"));
        assertEquals(true, record.get("cluster_summary_compact_signatures"));
        assertEquals("dynamic_scaled", record.get("cluster_summary_budget_mode"));
        assertEquals(40.0, record.get("cluster_summary_candidate_classes"));
        assertEquals(12.0, record.get("cluster_summary_emitted_classes"));
        assertEquals(5.0, record.get("cluster_summary_emitted_tier1_classes"));
        assertEquals(4.0, record.get("cluster_summary_emitted_tier2_classes"));
        assertEquals(3.0, record.get("cluster_summary_emitted_tier3_classes"));
        assertEquals(18.0, record.get("cluster_summary_emitted_instantiators"));
        assertEquals(24.0, record.get("cluster_summary_emitted_modifiers"));
        assertEquals(7.0, record.get("cluster_summary_dropped_per_class_cap"));
        assertEquals(1.0, record.get("cluster_summary_dropped_global_budget"));
    }

    @Test
    void sutContextFlagsSurviveFromTheContextFactoryToTheTraceRecord() throws Exception {
        SutContextProviderFactory factory = new SutContextProviderFactory() {
            @Override
            public ContextResult getContext(String className, TestCluster cluster) {
                return new ContextResult("class Foo {}", Properties.LlmSutContextMode.BYTECODE_DISASSEMBLED,
                        false, true, true, true);
            }
        };
        PromptResult prompt = new PromptBuilder(new SystemPromptProvider(), new TestClusterSummarizer(),
                new CoverageGoalFormatter(), new TestCaseFormatter(), factory)
                .withSystemPrompt()
                .withSutContext("com.example.Foo", null)
                .buildWithMetadata();

        Map<String, Object> record = traceOf(prompt);

        assertEquals("BYTECODE_DISASSEMBLED", record.get("sut_context_mode"));
        assertEquals(false, record.get("context_unavailable"));
        assertEquals(true, record.get("context_truncated"));
        assertEquals(true, record.get("context_comments_stripped"));
        assertEquals(true, record.get("context_selectively_truncated"));
        assertEquals(false, record.get("cluster_summary_truncated"));
        assertEquals(0.0, record.get("cluster_summary_chars"));
        assertEquals("none", record.get("cluster_summary_budget_mode"));
        assertEquals(0.0, record.get("cluster_summary_budget_chars"));
    }
}
