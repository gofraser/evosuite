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
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.postprocess.LlmPostProcessingProtocol;
import org.evosuite.llm.prompt.SystemPromptProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes reproducibility traces for LLM interactions in JSONL format.
 */
public class LlmTraceRecorder {

    private static final Logger logger = LoggerFactory.getLogger(LlmTraceRecorder.class);
    private static final Gson GSON = new Gson();
    private static final int TRACE_SCHEMA_VERSION = 3;
    private static final ThreadLocal<PostProcessingTraceContext> POST_PROCESSING_TRACE_CONTEXT =
            new ThreadLocal<>();

    private final LlmConfiguration configuration;
    private final Path traceFile;
    private boolean directoryCreated;

    /** Constructs a trace recorder for the given LLM configuration. */
    public LlmTraceRecorder(LlmConfiguration configuration) {
        this.configuration = configuration;
        this.traceFile = configuration.getTraceDir().resolve("llm-trace.jsonl");
    }

    public static final class CallRecord {
        private final LlmFeature feature;
        private final List<LlmMessage> messages;
        private final String responseText;
        private final int inputTokens;
        private final int outputTokens;
        private final long latencyMs;
        private final String parseStatus;
        private final int repairAttempt;
        private final boolean expansionAttempted;
        private final List<String> expandedClasses;
        private final String errorType;
        private final org.evosuite.Properties.LlmSutContextMode sutContextMode;
        private final boolean contextUnavailable;
        private final boolean contextTruncated;
        private final boolean contextCommentsStripped;
        private final boolean contextSelectivelyTruncated;
        private final boolean clusterSummaryTruncated;
        private final int clusterSummaryChars;
        private final PromptResult.DependencySummaryMetadata dependencySummaryMetadata;

        public static final class Builder {
            private LlmFeature feature;
            private List<LlmMessage> messages = Collections.emptyList();
            private String responseText;
            private int inputTokens;
            private int outputTokens;
            private long latencyMs;
            private String parseStatus;
            private int repairAttempt;
            private boolean expansionAttempted;
            private List<String> expandedClasses = Collections.emptyList();
            private String errorType = "";
            private org.evosuite.Properties.LlmSutContextMode sutContextMode;
            private boolean contextUnavailable;
            private boolean contextTruncated;
            private boolean contextCommentsStripped;
            private boolean contextSelectivelyTruncated;
            private boolean clusterSummaryTruncated;
            private int clusterSummaryChars;
            private PromptResult.DependencySummaryMetadata dependencySummaryMetadata =
                    PromptResult.DependencySummaryMetadata.empty();

            public Builder feature(LlmFeature feature) {
                this.feature = feature;
                return this;
            }

            public Builder messages(List<LlmMessage> messages) {
                this.messages = messages;
                return this;
            }

            public Builder responseText(String responseText) {
                this.responseText = responseText;
                return this;
            }

            public Builder inputTokens(int inputTokens) {
                this.inputTokens = inputTokens;
                return this;
            }

            public Builder outputTokens(int outputTokens) {
                this.outputTokens = outputTokens;
                return this;
            }

            public Builder latencyMs(long latencyMs) {
                this.latencyMs = latencyMs;
                return this;
            }

            public Builder parseStatus(String parseStatus) {
                this.parseStatus = parseStatus;
                return this;
            }

            public Builder repairAttempt(int repairAttempt) {
                this.repairAttempt = repairAttempt;
                return this;
            }

            public Builder expansionAttempted(boolean expansionAttempted) {
                this.expansionAttempted = expansionAttempted;
                return this;
            }

            public Builder expandedClasses(List<String> expandedClasses) {
                this.expandedClasses = expandedClasses;
                return this;
            }

            public Builder errorType(String errorType) {
                this.errorType = errorType;
                return this;
            }

            public Builder sutContextMode(org.evosuite.Properties.LlmSutContextMode sutContextMode) {
                this.sutContextMode = sutContextMode;
                return this;
            }

            public Builder contextUnavailable(boolean contextUnavailable) {
                this.contextUnavailable = contextUnavailable;
                return this;
            }

            public Builder contextTruncated(boolean contextTruncated) {
                this.contextTruncated = contextTruncated;
                return this;
            }

            public Builder contextCommentsStripped(boolean contextCommentsStripped) {
                this.contextCommentsStripped = contextCommentsStripped;
                return this;
            }

            public Builder contextSelectivelyTruncated(boolean contextSelectivelyTruncated) {
                this.contextSelectivelyTruncated = contextSelectivelyTruncated;
                return this;
            }

            public Builder clusterSummaryTruncated(boolean clusterSummaryTruncated) {
                this.clusterSummaryTruncated = clusterSummaryTruncated;
                return this;
            }

            public Builder clusterSummaryChars(int clusterSummaryChars) {
                this.clusterSummaryChars = clusterSummaryChars;
                return this;
            }

            public Builder dependencySummaryMetadata(PromptResult.DependencySummaryMetadata dependencySummaryMetadata) {
                this.dependencySummaryMetadata = dependencySummaryMetadata;
                return this;
            }

            public CallRecord build() {
                return new CallRecord(this);
            }
        }

        private CallRecord(Builder builder) {
            this.feature = Objects.requireNonNull(builder.feature, "feature");
            this.messages = builder.messages == null ? Collections.<LlmMessage>emptyList() : builder.messages;
            this.responseText = builder.responseText;
            this.inputTokens = builder.inputTokens;
            this.outputTokens = builder.outputTokens;
            this.latencyMs = builder.latencyMs;
            this.parseStatus = builder.parseStatus;
            this.repairAttempt = builder.repairAttempt;
            this.expansionAttempted = builder.expansionAttempted;
            this.expandedClasses = builder.expandedClasses == null
                    ? Collections.<String>emptyList() : builder.expandedClasses;
            this.errorType = builder.errorType == null ? "" : builder.errorType;
            this.sutContextMode = builder.sutContextMode;
            this.contextUnavailable = builder.contextUnavailable;
            this.contextTruncated = builder.contextTruncated;
            this.contextCommentsStripped = builder.contextCommentsStripped;
            this.contextSelectivelyTruncated = builder.contextSelectivelyTruncated;
            this.clusterSummaryTruncated = builder.clusterSummaryTruncated;
            this.clusterSummaryChars = builder.clusterSummaryChars;
            this.dependencySummaryMetadata = builder.dependencySummaryMetadata == null
                    ? PromptResult.DependencySummaryMetadata.empty() : builder.dependencySummaryMetadata;
        }
    }

    public static final class PostProcessingTraceContext {
        private final String minimizationStatus;
        private final String minimizationStopCause;
        private final int testIndex;

        private PostProcessingTraceContext(String minimizationStatus, String minimizationStopCause,
                                           int testIndex) {
            this.minimizationStatus = minimizationStatus == null ? "" : minimizationStatus;
            this.minimizationStopCause = minimizationStopCause == null ? "" : minimizationStopCause;
            this.testIndex = testIndex;
        }
    }

    public static final class PostProcessingAssertionDiagnosticRecord {
        private final int testIndex;
        private final String minimizationStatus;
        private final String minimizationStopCause;
        private final String callKind;
        private final String diagnosticSource;
        private final String diagnosticCode;
        private final String diagnosticPath;
        private final String diagnosticMessage;
        private final String assertionId;
        private final String assertionKind;
        private final String actual;
        private final String expected;
        private final String delta;
        private final String intent;
        private final String placementAfterStatementId;
        private final String purpose;
        private final String assertionSource;
        private final String candidateId;
        private final Map<String, Object> assertionJson;

        public PostProcessingAssertionDiagnosticRecord(int testIndex,
                                                       String minimizationStatus,
                                                       String minimizationStopCause,
                                                       String callKind,
                                                       String diagnosticSource,
                                                       String diagnosticCode,
                                                       String diagnosticPath,
                                                       String diagnosticMessage,
                                                       String assertionId,
                                                       String assertionKind,
                                                       String actual,
                                                       String expected,
                                                       String delta,
                                                       String intent,
                                                       String placementAfterStatementId,
                                                       String purpose,
                                                       Map<String, Object> assertionJson) {
            this(testIndex, minimizationStatus, minimizationStopCause, callKind,
                    diagnosticSource, diagnosticCode, diagnosticPath, diagnosticMessage,
                    assertionId, assertionKind, actual, expected, delta, intent,
                    placementAfterStatementId, purpose, "", "", assertionJson);
        }

        public PostProcessingAssertionDiagnosticRecord(int testIndex,
                                                       String minimizationStatus,
                                                       String minimizationStopCause,
                                                       String callKind,
                                                       String diagnosticSource,
                                                       String diagnosticCode,
                                                       String diagnosticPath,
                                                       String diagnosticMessage,
                                                       String assertionId,
                                                       String assertionKind,
                                                       String actual,
                                                       String expected,
                                                       String delta,
                                                       String intent,
                                                       String placementAfterStatementId,
                                                       String purpose,
                                                       String assertionSource,
                                                       String candidateId,
                                                       Map<String, Object> assertionJson) {
            this.testIndex = testIndex;
            this.minimizationStatus = minimizationStatus == null ? "" : minimizationStatus;
            this.minimizationStopCause = minimizationStopCause == null ? "" : minimizationStopCause;
            this.callKind = callKind == null ? "" : callKind;
            this.diagnosticSource = diagnosticSource == null ? "" : diagnosticSource;
            this.diagnosticCode = diagnosticCode == null ? "" : diagnosticCode;
            this.diagnosticPath = diagnosticPath == null ? "" : diagnosticPath;
            this.diagnosticMessage = diagnosticMessage == null ? "" : diagnosticMessage;
            this.assertionId = assertionId == null ? "" : assertionId;
            this.assertionKind = assertionKind == null ? "" : assertionKind;
            this.actual = actual == null ? "" : actual;
            this.expected = expected == null ? "" : expected;
            this.delta = delta == null ? "" : delta;
            this.intent = intent == null ? "" : intent;
            this.placementAfterStatementId = placementAfterStatementId == null ? "" : placementAfterStatementId;
            this.purpose = purpose == null ? "" : purpose;
            this.assertionSource = assertionSource == null ? "" : assertionSource;
            this.candidateId = candidateId == null ? "" : candidateId;
            this.assertionJson = assertionJson == null
                    ? Collections.<String, Object>emptyMap()
                    : new LinkedHashMap<>(assertionJson);
        }
    }

    /** Structured lifecycle event for an assertion accepted by post-processing. */
    public static final class PostProcessingAssertionLifecycleRecord {
        private final int testIndex;
        private final String minimizationStatus;
        private final String minimizationStopCause;
        private final String lifecycleState;
        private final String callKind;
        private final String assertionId;
        private final String assertionKind;
        private final String actual;
        private final String expected;
        private final String delta;
        private final String intent;
        private final String placementAfterStatementId;
        private final String placementSite;
        private final String placementExceptionId;
        private final String purpose;
        private final String assertionSource;
        private final String candidateId;

        public PostProcessingAssertionLifecycleRecord(int testIndex,
                                                      String minimizationStatus,
                                                      String minimizationStopCause,
                                                      String lifecycleState,
                                                      String callKind,
                                                      String assertionId,
                                                      String assertionKind,
                                                      String actual,
                                                      String expected,
                                                      String delta,
                                                      String intent,
                                                      String placementAfterStatementId,
                                                      String purpose) {
            this(testIndex, minimizationStatus, minimizationStopCause, lifecycleState, callKind,
                    assertionId, assertionKind, actual, expected, delta, intent,
                    placementAfterStatementId, purpose, "", "", "", "");
        }

        public PostProcessingAssertionLifecycleRecord(int testIndex,
                                                      String minimizationStatus,
                                                      String minimizationStopCause,
                                                      String lifecycleState,
                                                      String callKind,
                                                      String assertionId,
                                                      String assertionKind,
                                                      String actual,
                                                      String expected,
                                                      String delta,
                                                      String intent,
                                                      String placementAfterStatementId,
                                                      String purpose,
                                                      String assertionSource,
                                                      String candidateId) {
            this(testIndex, minimizationStatus, minimizationStopCause, lifecycleState, callKind,
                    assertionId, assertionKind, actual, expected, delta, intent,
                    placementAfterStatementId, purpose, assertionSource, candidateId, "", "");
        }

        public PostProcessingAssertionLifecycleRecord(int testIndex,
                                                      String minimizationStatus,
                                                      String minimizationStopCause,
                                                      String lifecycleState,
                                                      String callKind,
                                                      String assertionId,
                                                      String assertionKind,
                                                      String actual,
                                                      String expected,
                                                      String delta,
                                                      String intent,
                                                      String placementAfterStatementId,
                                                      String purpose,
                                                      String assertionSource,
                                                      String candidateId,
                                                      String placementSite,
                                                      String placementExceptionId) {
            this.testIndex = testIndex;
            this.minimizationStatus = minimizationStatus == null ? "" : minimizationStatus;
            this.minimizationStopCause = minimizationStopCause == null ? "" : minimizationStopCause;
            this.lifecycleState = lifecycleState == null ? "" : lifecycleState;
            this.callKind = callKind == null ? "" : callKind;
            this.assertionId = assertionId == null ? "" : assertionId;
            this.assertionKind = assertionKind == null ? "" : assertionKind;
            this.actual = actual == null ? "" : actual;
            this.expected = expected == null ? "" : expected;
            this.delta = delta == null ? "" : delta;
            this.intent = intent == null ? "" : intent;
            this.placementAfterStatementId = placementAfterStatementId == null
                    ? "" : placementAfterStatementId;
            this.placementSite = placementSite == null ? "" : placementSite;
            this.placementExceptionId = placementExceptionId == null ? "" : placementExceptionId;
            this.purpose = purpose == null ? "" : purpose;
            this.assertionSource = assertionSource == null ? "" : assertionSource;
            this.candidateId = candidateId == null ? "" : candidateId;
        }
    }

    public static void setPostProcessingTraceContext(String minimizationStatus, String minimizationStopCause,
                                                     int testIndex) {
        POST_PROCESSING_TRACE_CONTEXT.set(new PostProcessingTraceContext(minimizationStatus,
                minimizationStopCause, testIndex));
    }

    public static void clearPostProcessingTraceContext() {
        POST_PROCESSING_TRACE_CONTEXT.remove();
    }

    /** Records a call with all metadata including dependency summary telemetry. */
    public void recordCall(CallRecord record) {
        if (!configuration.isTraceEnabled()) {
            return;
        }

        List<LlmMessage> safeMessages = record.messages;
        List<String> expanded = record.expandedClasses;
        PostProcessingTraceContext postProcessingContext = POST_PROCESSING_TRACE_CONTEXT.get();
        Map<String, Object> traceRecord = new LinkedHashMap<>();
        traceRecord.put("schema_version", TRACE_SCHEMA_VERSION);
        traceRecord.put("event_type", "llm_call");
        traceRecord.put("run_id", configuration.getRunId());
        traceRecord.put("target_class", Properties.TARGET_CLASS == null ? "" : Properties.TARGET_CLASS);
        traceRecord.put("timestamp", Instant.now().toString());
        traceRecord.put("feature", record.feature.name());
        traceRecord.put("postprocessing_call_kind",
                record.feature == LlmFeature.POST_PROCESSING
                        ? (record.repairAttempt > 1 ? "repair" : "initial")
                        : "");
        traceRecord.put("postprocessing_test_index",
                postProcessingContext == null ? -1 : postProcessingContext.testIndex);
        traceRecord.put("postprocessing_minimization_status",
                postProcessingContext == null ? "" : postProcessingContext.minimizationStatus);
        traceRecord.put("postprocessing_minimization_stop_cause",
                postProcessingContext == null ? "" : postProcessingContext.minimizationStopCause);
        traceRecord.put("provider", configuration.getProvider().name());
        traceRecord.put("model", configuration.getModel());
        traceRecord.put("effective_temperature", configuration.getTemperature());
        traceRecord.put("effective_max_tokens", configuration.getMaxTokens());
        traceRecord.put("effective_timeout_seconds", configuration.getTimeoutSeconds());
        traceRecord.put("effective_retry_max_attempts", configuration.getRetryMaxAttempts());
        traceRecord.put("effective_retry_base_delay_ms", configuration.getRetryBaseDelayMs());
        traceRecord.put("prompt_hash", deterministicPromptHash(safeMessages));
        traceRecord.put("system_prompt_hash", messageContentHash(safeMessages, LlmMessage.Role.SYSTEM));
        traceRecord.put("user_prompt_hash", messageContentHash(safeMessages, LlmMessage.Role.USER));
        traceRecord.put("postprocessing_prompt_version",
                record.feature == LlmFeature.POST_PROCESSING
                        ? LlmPostProcessingProtocol.promptVersion() : "");
        traceRecord.put("postprocessing_response_schema_version",
                record.feature == LlmFeature.POST_PROCESSING
                        ? LlmPostProcessingProtocol.responseSchemaVersion() : 0);
        traceRecord.put("postprocessing_parser_version",
                record.feature == LlmFeature.POST_PROCESSING
                        ? LlmPostProcessingProtocol.PARSER_VERSION : "");
        traceRecord.put("postprocessing_prompt_variant",
                record.feature == LlmFeature.POST_PROCESSING
                        ? LlmPostProcessingProtocol.promptVersion() : "");
        traceRecord.put("postprocessing_capabilities",
                record.feature == LlmFeature.POST_PROCESSING
                        ? Collections.emptyList()
                        : Collections.emptyList());
        traceRecord.put("postprocessing_internal_context_truncated",
                record.feature == LlmFeature.POST_PROCESSING
                        && hasInternalContextTruncation(safeMessages));
        traceRecord.put("postprocessing_repair_policy",
                record.feature == LlmFeature.POST_PROCESSING
                        ? Properties.LlmPostProcessingRepairPolicy.TARGETED_ONE.name() : "");
        traceRecord.put("messages", toSerializableMessages(safeMessages));
        traceRecord.put("response_text", record.responseText);
        traceRecord.put("parse_status", record.parseStatus);
        traceRecord.put("repair_attempt", record.repairAttempt);
        traceRecord.put("expansion_attempted", record.expansionAttempted);
        traceRecord.put("expanded_classes", expanded);
        traceRecord.put("input_tokens", record.inputTokens);
        traceRecord.put("output_tokens", record.outputTokens);
        traceRecord.put("latency_ms", record.latencyMs);
        traceRecord.put("error_type", record.errorType);
        traceRecord.put("sut_context_mode", record.sutContextMode == null ? "" : record.sutContextMode.name());
        traceRecord.put("context_unavailable", record.contextUnavailable);
        traceRecord.put("context_truncated", record.contextTruncated);
        traceRecord.put("context_comments_stripped", record.contextCommentsStripped);
        traceRecord.put("context_selectively_truncated", record.contextSelectivelyTruncated);
        traceRecord.put("cluster_summary_truncated", record.clusterSummaryTruncated);
        traceRecord.put("cluster_summary_chars", record.clusterSummaryChars);
        PromptResult.DependencySummaryMetadata dep = record.dependencySummaryMetadata;
        traceRecord.put("cluster_summary_budget_chars", dep.getBudgetChars());
        traceRecord.put("cluster_summary_per_class_cap_chars", dep.getPerClassSoftCapChars());
        traceRecord.put("cluster_summary_per_class_cap_auto", dep.isPerClassSoftCapAuto());
        traceRecord.put("cluster_summary_compact_signatures", dep.isCompactSignatures());
        traceRecord.put("cluster_summary_budget_mode", dep.getBudgetMode());
        traceRecord.put("cluster_summary_candidate_classes", dep.getCandidateClasses());
        traceRecord.put("cluster_summary_emitted_classes", dep.getEmittedClasses());
        traceRecord.put("cluster_summary_emitted_tier1_classes", dep.getEmittedTier1Classes());
        traceRecord.put("cluster_summary_emitted_tier2_classes", dep.getEmittedTier2Classes());
        traceRecord.put("cluster_summary_emitted_tier3_classes", dep.getEmittedTier3Classes());
        traceRecord.put("cluster_summary_emitted_instantiators", dep.getEmittedInstantiators());
        traceRecord.put("cluster_summary_emitted_modifiers", dep.getEmittedModifiers());
        traceRecord.put("cluster_summary_dropped_per_class_cap", dep.getDroppedByPerClassCap());
        traceRecord.put("cluster_summary_dropped_global_budget", dep.getDroppedByGlobalBudget());
        String json = GSON.toJson(traceRecord);
        try {
            writeJsonLine(json);
        } catch (Throwable writeFailure) {
            logger.warn("Failed writing LLM trace: {}", writeFailure.getMessage());
            logger.debug("LLM trace write failure details", writeFailure);
        }
    }

    public void recordPostProcessingAssertionDiagnostic(PostProcessingAssertionDiagnosticRecord record) {
        if (!configuration.isTraceEnabled() || record == null) {
            return;
        }

        Map<String, Object> traceRecord = new LinkedHashMap<>();
        traceRecord.put("schema_version", TRACE_SCHEMA_VERSION);
        traceRecord.put("event_type", "postprocessing_assertion_rejection");
        traceRecord.put("run_id", configuration.getRunId());
        traceRecord.put("target_class", Properties.TARGET_CLASS == null ? "" : Properties.TARGET_CLASS);
        traceRecord.put("timestamp", Instant.now().toString());
        traceRecord.put("feature", LlmFeature.POST_PROCESSING.name());
        addPostProcessingProtocolMetadata(traceRecord);
        traceRecord.put("postprocessing_call_kind", record.callKind);
        traceRecord.put("postprocessing_test_index", record.testIndex);
        traceRecord.put("postprocessing_minimization_status", record.minimizationStatus);
        traceRecord.put("postprocessing_minimization_stop_cause", record.minimizationStopCause);
        traceRecord.put("diagnostic_source", record.diagnosticSource);
        traceRecord.put("diagnostic_code", record.diagnosticCode);
        traceRecord.put("diagnostic_path", record.diagnosticPath);
        traceRecord.put("diagnostic_message", record.diagnosticMessage);
        traceRecord.put("assertion_id", record.assertionId);
        traceRecord.put("assertion_kind", record.assertionKind);
        traceRecord.put("assertion_actual", record.actual);
        traceRecord.put("assertion_expected", record.expected);
        traceRecord.put("assertion_delta", record.delta);
        traceRecord.put("assertion_intent", record.intent);
        traceRecord.put("assertion_placement_after_statement_id", record.placementAfterStatementId);
        traceRecord.put("assertion_placement_site",
                placementJsonField(record.assertionJson, "site"));
        traceRecord.put("assertion_placement_exception_id",
                placementJsonField(record.assertionJson, "exceptionId"));
        traceRecord.put("assertion_purpose", record.purpose);
        traceRecord.put("assertion_source", record.assertionSource);
        traceRecord.put("candidate_id", record.candidateId);
        traceRecord.put("assertion_json", record.assertionJson);

        String json = GSON.toJson(traceRecord);
        try {
            writeJsonLine(json);
        } catch (Throwable writeFailure) {
            logger.warn("Failed writing LLM post-processing diagnostic trace: {}", writeFailure.getMessage());
            logger.debug("LLM post-processing diagnostic trace write failure details", writeFailure);
        }
    }

    /** Records one transition in the accepted assertion's lifecycle. */
    public void recordPostProcessingAssertionLifecycle(PostProcessingAssertionLifecycleRecord record) {
        if (!configuration.isTraceEnabled() || record == null) {
            return;
        }

        Map<String, Object> traceRecord = new LinkedHashMap<>();
        traceRecord.put("schema_version", TRACE_SCHEMA_VERSION);
        traceRecord.put("event_type", "postprocessing_assertion_lifecycle");
        traceRecord.put("run_id", configuration.getRunId());
        traceRecord.put("target_class", Properties.TARGET_CLASS == null ? "" : Properties.TARGET_CLASS);
        traceRecord.put("timestamp", Instant.now().toString());
        traceRecord.put("feature", LlmFeature.POST_PROCESSING.name());
        addPostProcessingProtocolMetadata(traceRecord);
        traceRecord.put("postprocessing_test_index", record.testIndex);
        traceRecord.put("postprocessing_minimization_status", record.minimizationStatus);
        traceRecord.put("postprocessing_minimization_stop_cause", record.minimizationStopCause);
        traceRecord.put("lifecycle_state", record.lifecycleState);
        traceRecord.put("postprocessing_call_kind", record.callKind);
        traceRecord.put("assertion_id", record.assertionId);
        traceRecord.put("assertion_kind", record.assertionKind);
        traceRecord.put("assertion_actual", record.actual);
        traceRecord.put("assertion_expected", record.expected);
        traceRecord.put("assertion_delta", record.delta);
        traceRecord.put("assertion_intent", record.intent);
        traceRecord.put("assertion_placement_after_statement_id", record.placementAfterStatementId);
        traceRecord.put("assertion_placement_site", record.placementSite);
        traceRecord.put("assertion_placement_exception_id", record.placementExceptionId);
        traceRecord.put("assertion_purpose", record.purpose);
        traceRecord.put("assertion_source", record.assertionSource);
        traceRecord.put("candidate_id", record.candidateId);

        try {
            writeJsonLine(GSON.toJson(traceRecord));
        } catch (Throwable writeFailure) {
            logger.warn("Failed writing LLM post-processing lifecycle trace: {}", writeFailure.getMessage());
            logger.debug("LLM post-processing lifecycle trace write failure details", writeFailure);
        }
    }

    String deterministicPromptHash(List<LlmMessage> messages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (LlmMessage message : messages) {
                if (message == null) {
                    continue;
                }
                digest.update(message.getRole().name().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1F);
                digest.update(message.getContent().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1E);
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String messageContentHash(List<LlmMessage> messages, LlmMessage.Role role) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            boolean found = false;
            for (LlmMessage message : messages) {
                if (message != null && message.getRole() == role) {
                    digest.update(message.getContent().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0x1E);
                    found = true;
                }
            }
            return found ? toHex(digest.digest()) : "";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static boolean hasInternalContextTruncation(List<LlmMessage> messages) {
        for (LlmMessage message : messages) {
            if (message == null || message.getRole() != LlmMessage.Role.USER) {
                continue;
            }
            String content = message.getContent();
            if (content != null && (content.contains("truncated=true")
                    || content.contains("truncatedCandidates=")
                    || content.contains("truncatedCallableTypes=")
                    || content.contains("droppedMethods=")
                    || content.contains("droppedRelationalOpportunities="))) {
                return true;
            }
        }
        return false;
    }

    private static String placementJsonField(Map<String, Object> assertionJson, String field) {
        if (assertionJson == null) {
            return "";
        }
        Object placement = assertionJson.get("placement");
        if (!(placement instanceof Map<?, ?>)) {
            return "";
        }
        Object value = ((Map<?, ?>) placement).get(field);
        return value == null ? "" : String.valueOf(value);
    }

    private void addPostProcessingProtocolMetadata(Map<String, Object> traceRecord) {
        traceRecord.put("provider", configuration.getProvider().name());
        traceRecord.put("model", configuration.getModel());
        traceRecord.put("effective_temperature", configuration.getTemperature());
        traceRecord.put("effective_max_tokens", configuration.getMaxTokens());
        traceRecord.put("effective_timeout_seconds", configuration.getTimeoutSeconds());
        traceRecord.put("effective_retry_max_attempts", configuration.getRetryMaxAttempts());
        traceRecord.put("effective_retry_base_delay_ms", configuration.getRetryBaseDelayMs());
        traceRecord.put("postprocessing_prompt_version", LlmPostProcessingProtocol.promptVersion());
        traceRecord.put("postprocessing_response_schema_version",
                LlmPostProcessingProtocol.responseSchemaVersion());
        traceRecord.put("postprocessing_parser_version", LlmPostProcessingProtocol.PARSER_VERSION);
        traceRecord.put("postprocessing_prompt_variant",
                LlmPostProcessingProtocol.promptVersion());
        traceRecord.put("postprocessing_capabilities",
                Collections.emptyList());
        traceRecord.put("postprocessing_repair_policy",
                Properties.LlmPostProcessingRepairPolicy.TARGETED_ONE.name());
        traceRecord.put("system_prompt_hash", contentHash(
                new SystemPromptProvider().getPostProcessingSystemPrompt()));
    }

    private String contentHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0x1E);
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public Path getTraceFile() {
        return traceFile;
    }

    private List<Map<String, String>> toSerializableMessages(List<LlmMessage> messages) {
        List<Map<String, String>> serialized = new ArrayList<>();
        for (LlmMessage message : messages) {
            if (message == null) {
                continue;
            }
            Map<String, String> jsonMessage = new LinkedHashMap<>();
            jsonMessage.put("role", message.getRole().name());
            jsonMessage.put("content", message.getContent());
            serialized.add(jsonMessage);
        }
        return serialized;
    }

    private String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte datum : data) {
            builder.append(String.format("%02x", datum));
        }
        return builder.toString();
    }

    private void writeJsonLine(String json) throws IOException {
        synchronized (this) {
            if (!directoryCreated) {
                Files.createDirectories(traceFile.getParent());
                directoryCreated = true;
            }
            Files.write(traceFile,
                    Collections.singleton(json),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }
    }

    public void close() {
        // No-op: writes are synchronous.
    }
}
