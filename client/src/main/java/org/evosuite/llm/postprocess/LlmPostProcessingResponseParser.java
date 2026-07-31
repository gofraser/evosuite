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
package org.evosuite.llm.postprocess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import org.evosuite.assertion.CanonicalAssertionRenderer;
import org.evosuite.assertion.TemplateAssertionKind;

import javax.lang.model.SourceVersion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.evosuite.llm.postprocess.LlmPostProcessingParseResult.Diagnostic;
import static org.evosuite.llm.postprocess.LlmPostProcessingParseResult.DiagnosticCode;

/**
 * Parser for the versioned unified LLM post-processing edit-plan schema.
 */
public final class LlmPostProcessingResponseParser {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final Set<String> ROOT_FIELDS = allowedFields("schemaVersion", "testName", "variableNames",
            "comments", "sectionBreaksAfter", "assertions", "assertionDecision", "noAssertionReason");
    private static final Set<String> ASSERTION_DECISIONS = allowedFields("PROPOSED", "NO_SAFE_ORACLE");
    private static final Set<String> NO_ASSERTION_REASONS = allowedFields(
            "NO_STABLE_OBSERVATION", "NO_LEGAL_CALLABLE", "THROWING_TEST", "CANDIDATE_REDUNDANCY",
            "ONLY_SETUP_VALUES", "TRUNCATED_OBSERVATION", "OTHER");
    private static final Set<String> ASSERTION_FIELDS = allowedFields("assertionId", "kind", "expected", "actual",
            "delta", "purpose", "intent", "placement", "container", "element", "target", "size", "map", "key",
            "candidateId", "value", "expression");
    private static final Set<String> BUILT_IN_PURE_STATIC_METHODS = allowedFields(
            "java.lang.Math#*",
            "Math#*",
            "java.util.Arrays#asList",
            "Arrays#asList",
            "java.lang.Boolean#valueOf",
            "Boolean#valueOf",
            "java.lang.Byte#valueOf",
            "Byte#valueOf",
            "java.lang.Byte#parseByte",
            "Byte#parseByte",
            "java.lang.Short#valueOf",
            "Short#valueOf",
            "java.lang.Short#parseShort",
            "Short#parseShort",
            "java.lang.Integer#valueOf",
            "Integer#valueOf",
            "java.lang.Integer#parseInt",
            "Integer#parseInt",
            "java.lang.Long#valueOf",
            "Long#valueOf",
            "java.lang.Long#parseLong",
            "Long#parseLong",
            "java.lang.Float#valueOf",
            "Float#valueOf",
            "java.lang.Float#parseFloat",
            "Float#parseFloat",
            "java.lang.Double#valueOf",
            "Double#valueOf",
            "java.lang.Double#parseDouble",
            "Double#parseDouble",
            "java.math.BigInteger#valueOf",
            "BigInteger#valueOf",
            "java.math.BigDecimal#valueOf",
            "BigDecimal#valueOf",
            "java.util.Optional#empty",
            "Optional#empty",
            "java.util.Optional#of",
            "Optional#of",
            "java.util.Optional#ofNullable",
            "Optional#ofNullable");
    private static final Set<String> BUILT_IN_IMMUTABLE_TYPES = allowedFields(
            "java.lang.String", "String",
            "java.lang.Boolean", "Boolean",
            "java.lang.Byte", "Byte",
            "java.lang.Short", "Short",
            "java.lang.Character", "Character",
            "java.lang.Integer", "Integer",
            "java.lang.Long", "Long",
            "java.lang.Float", "Float",
            "java.lang.Double", "Double",
            "java.math.BigInteger", "BigInteger",
            "java.math.BigDecimal", "BigDecimal",
            "java.util.UUID", "UUID",
            "java.time.LocalDate", "LocalDate",
            "java.time.LocalTime", "LocalTime",
            "java.time.LocalDateTime", "LocalDateTime",
            "java.time.Instant", "Instant",
            "java.time.Duration", "Duration",
            "java.time.Period", "Period");
    private static final Set<String> DENIED_STATIC_OWNERS = allowedFields(
            "java.lang.System", "System",
            "java.lang.Runtime", "Runtime",
            "java.lang.Thread", "Thread",
            "java.lang.ProcessBuilder", "ProcessBuilder",
            "java.io.File", "File",
            "java.nio.file.Files", "Files",
            "java.nio.file.Paths", "Paths",
            "java.nio.file.FileSystems", "FileSystems",
            "java.lang.Class", "Class");
    private static final Set<String> DENIED_STATIC_METHODS = allowedFields(
            "java.lang.Math#random",
            "Math#random");
    private static final Set<String> DENIED_INSTANCE_METHODS = allowedFields(
            "wait",
            "notify",
            "notifyAll",
            "getClass",
            "hashCode",
            "finalize");

    private LlmPostProcessingResponseParser() {
        // Utility class.
    }

    public static ParseContext context(Set<String> statementIds, Set<String> variableIds) {
        return new ParseContext(statementIds, variableIds, Collections.<CallableMethod>emptySet(),
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String, String>emptyMap(), Collections.<String, SelectableCandidate>emptyMap());
    }

    public static ParseContext context(Set<String> statementIds, Set<String> variableIds,
                                       Set<CallableMethod> callableMethods) {
        return new ParseContext(statementIds, variableIds, callableMethods, Collections.<String>emptySet(),
                Collections.<String>emptySet(), Collections.<String, String>emptyMap(),
                Collections.<String, SelectableCandidate>emptyMap());
    }

    public static ParseContext context(Set<String> statementIds, Set<String> variableIds,
                                       Set<CallableMethod> callableMethods,
                                       Set<String> observedCandidateKeys) {
        return new ParseContext(statementIds, variableIds, callableMethods, observedCandidateKeys,
                Collections.<String>emptySet(), Collections.<String, String>emptyMap(),
                Collections.<String, SelectableCandidate>emptyMap());
    }

    public static ParseContext context(Set<String> statementIds, Set<String> variableIds,
                                       Set<CallableMethod> callableMethods,
                                       Set<String> observedCandidateKeys,
                                       Set<String> setupInputVariableIds) {
        return new ParseContext(statementIds, variableIds, callableMethods, observedCandidateKeys,
                setupInputVariableIds, Collections.<String, String>emptyMap(),
                Collections.<String, SelectableCandidate>emptyMap());
    }

    public static ParseContext context(Set<String> statementIds, Set<String> variableIds,
                                       Set<CallableMethod> callableMethods,
                                       Set<String> observedCandidateKeys,
                                       Set<String> setupInputVariableIds,
                                       Map<String, String> variableTypes) {
        return new ParseContext(statementIds, variableIds, callableMethods, observedCandidateKeys,
                setupInputVariableIds, variableTypes, Collections.<String, SelectableCandidate>emptyMap());
    }

    public static ParseContext context(Set<String> statementIds, Set<String> variableIds,
                                       Set<CallableMethod> callableMethods,
                                       Set<String> observedCandidateKeys,
                                       Set<String> setupInputVariableIds,
                                       Map<String, String> variableTypes,
                                       Map<String, SelectableCandidate> selectableCandidates) {
        return new ParseContext(statementIds, variableIds, callableMethods, observedCandidateKeys,
                setupInputVariableIds, variableTypes, selectableCandidates);
    }

    public static ParseContext context(Set<String> statementIds, Set<String> variableIds,
                                       Set<CallableMethod> callableMethods,
                                       Set<String> observedCandidateKeys,
                                       Set<String> setupInputVariableIds,
                                       Map<String, String> variableTypes,
                                       Map<String, SelectableCandidate> selectableCandidates,
                                       String throwingStatementId) {
        return new ParseContext(statementIds, variableIds, callableMethods, observedCandidateKeys,
                setupInputVariableIds, variableTypes, selectableCandidates, throwingStatementId);
    }

    public static LlmPostProcessingParseResult parse(String response, ParseContext context) {
        PostProcessingResponseDecoder.DecodeResult decoded =
                PostProcessingResponseDecoder.decode(response);
        if (!decoded.isSuccess()) {
            return LlmPostProcessingParseResult.infrastructureFailure(decoded.getFailureReason());
        }
        JsonNode root = decoded.getRoot();
        int schemaVersion = decoded.getSchemaVersion();
        ParserState state = new ParserState(context == null ? ParseContext.empty() : context,
                new LlmPostProcessingResponse(schemaVersion));
        state.reportUnknownFields(root, "", ROOT_FIELDS);
        state.parseBasicEdits(root);
        state.parseAssertions(root.get("assertions"));
        state.parseAssertionDecision(root.get("assertionDecision"), root.get("noAssertionReason"));
        return LlmPostProcessingParseResult.success(state.response, state.diagnostics,
                proposedCounts(root), rawAssertions(root));
    }

    private static PostProcessingCounts proposedCounts(JsonNode root) {
        return new PostProcessingCounts(
                root.hasNonNull("testName") ? 1 : 0,
                sizeIfObject(root.get("variableNames")),
                sizeIfArray(root.get("comments")),
                sizeIfArray(root.get("sectionBreaksAfter")),
                sizeIfArray(root.get("assertions")));
    }

    private static List<LlmPostProcessingParseResult.RawAssertion> rawAssertions(JsonNode root) {
        List<LlmPostProcessingParseResult.RawAssertion> result = new ArrayList<>();
        JsonNode assertions = root == null ? null : root.get("assertions");
        if (assertions == null || !assertions.isArray()) {
            return result;
        }
        for (int index = 0; index < assertions.size(); index++) {
            JsonNode assertion = assertions.get(index);
            if (assertion == null || !assertion.isObject()) {
                continue;
            }
            String assertionId = textValue(assertion.get("assertionId"));
            if (assertionId != null && !assertionId.trim().isEmpty()) {
                result.add(new LlmPostProcessingParseResult.RawAssertion(
                        index, assertionId.trim(), assertion.toString(),
                        JSON_MAPPER.convertValue(assertion, LinkedHashMap.class)));
            }
        }
        return result;
    }

    private static int sizeIfObject(JsonNode node) {
        return node != null && node.isObject() ? node.size() : 0;
    }

    private static int sizeIfArray(JsonNode node) {
        return node != null && node.isArray() ? node.size() : 0;
    }

    private static String textValue(JsonNode node) {
        return node == null || node.isNull() || !node.isTextual() ? null : node.asText();
    }

    static String normalizeJsonResponse(String response) {
        return PostProcessingResponseDecoder.normalizeJsonResponse(response);
    }

    private static Set<String> allowedFields(String... fields) {
        Set<String> result = new HashSet<>();
        Collections.addAll(result, fields);
        return Collections.unmodifiableSet(result);
    }

    public static final class ParseContext {
        private final Set<String> statementIds;
        private final Set<String> variableIds;
        private final Set<CallableMethod> callableMethods;
        private final Set<String> observedCandidateKeys;
        private final Set<String> setupInputVariableIds;
        private final Map<String, String> variableTypes;
        private final Map<String, SelectableCandidate> selectableCandidates;
        private final String throwingStatementId;
        private PostProcessingOptions options;

        private ParseContext(Set<String> statementIds, Set<String> variableIds,
                             Set<CallableMethod> callableMethods,
                             Set<String> observedCandidateKeys,
                             Set<String> setupInputVariableIds,
                             Map<String, String> variableTypes,
                             Map<String, SelectableCandidate> selectableCandidates) {
            this(statementIds, variableIds, callableMethods, observedCandidateKeys,
                    setupInputVariableIds, variableTypes, selectableCandidates, null);
        }

        private ParseContext(Set<String> statementIds, Set<String> variableIds,
                             Set<CallableMethod> callableMethods,
                             Set<String> observedCandidateKeys,
                             Set<String> setupInputVariableIds,
                             Map<String, String> variableTypes,
                             Map<String, SelectableCandidate> selectableCandidates,
                             String throwingStatementId) {
            this.statementIds = copy(statementIds);
            this.variableIds = copy(variableIds);
            this.callableMethods = copy(callableMethods);
            this.observedCandidateKeys = copy(observedCandidateKeys);
            this.setupInputVariableIds = copy(setupInputVariableIds);
            this.variableTypes = copyMap(variableTypes);
            this.selectableCandidates = copyMap(selectableCandidates);
            this.throwingStatementId = throwingStatementId;
            this.options = null;
        }

        ParseContext withOptions(PostProcessingOptions options) {
            ParseContext copy = new ParseContext(statementIds, variableIds, callableMethods,
                    observedCandidateKeys, setupInputVariableIds, variableTypes,
                    selectableCandidates, throwingStatementId);
            copy.options = options;
            return copy;
        }

        public static ParseContext empty() {
            return new ParseContext(Collections.<String>emptySet(), Collections.<String>emptySet(),
                    Collections.<CallableMethod>emptySet(), Collections.<String>emptySet(),
                    Collections.<String>emptySet(), Collections.<String, String>emptyMap(),
                    Collections.<String, SelectableCandidate>emptyMap());
        }

        private static <T> Set<T> copy(Set<T> values) {
            if (values == null || values.isEmpty()) {
                return Collections.emptySet();
            }
            return Collections.unmodifiableSet(new HashSet<>(values));
        }

        private static <K, V> Map<K, V> copyMap(Map<K, V> values) {
            if (values == null || values.isEmpty()) {
                return Collections.emptyMap();
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        boolean knowsStatementIds() {
            return !statementIds.isEmpty();
        }

        boolean knowsVariableIds() {
            return !variableIds.isEmpty();
        }

        boolean hasStatementId(String id) {
            return statementIds.contains(id);
        }

        boolean hasVariableId(String id) {
            return variableIds.contains(id);
        }

        String variableType(String variableId) {
            return variableTypes.get(variableId);
        }

        boolean hasObservedCandidateKey(String key) {
            return observedCandidateKeys.contains(key);
        }

        boolean isSetupInputVariableId(String id) {
            return setupInputVariableIds.contains(id);
        }

        boolean hasThrowingStatement() {
            return throwingStatementId != null;
        }

        String throwingStatementId() {
            return throwingStatementId;
        }

        PostProcessingOptions options() {
            return options;
        }
    }

    public static final class SelectableCandidate {
        private final LlmPostProcessingResponse.AssertionKind kind;
        private final String expected;
        private final String actual;
        private final String delta;
        private final LlmPostProcessingResponse.AssertionSite defaultSite;
        private final String defaultAfterStatementId;
        private final String defaultExceptionId;

        public SelectableCandidate(LlmPostProcessingResponse.AssertionKind kind, String expected,
                                   String actual, String delta) {
            this(kind, expected, actual, delta, null, null, null);
        }

        private SelectableCandidate(LlmPostProcessingResponse.AssertionKind kind, String expected,
                                    String actual, String delta,
                                    LlmPostProcessingResponse.AssertionSite defaultSite,
                                    String defaultAfterStatementId, String defaultExceptionId) {
            this.kind = kind;
            this.expected = expected;
            this.actual = actual;
            this.delta = delta;
            this.defaultSite = defaultSite;
            this.defaultAfterStatementId = defaultAfterStatementId;
            this.defaultExceptionId = defaultExceptionId;
        }

        SelectableCandidate withDefaultPlacement(
                LlmPostProcessingResponse.AssertionSite site,
                String afterStatementId, String exceptionId) {
            return new SelectableCandidate(kind, expected, actual, delta,
                    site, afterStatementId, exceptionId);
        }

        public LlmPostProcessingResponse.AssertionKind getKind() {
            return kind;
        }

        public String getExpected() {
            return expected;
        }

        public String getActual() {
            return actual;
        }

        public String getDelta() {
            return delta;
        }

        LlmPostProcessingResponse.AssertionSite getDefaultSite() {
            return defaultSite;
        }

        String getDefaultAfterStatementId() {
            return defaultAfterStatementId;
        }

        String getDefaultExceptionId() {
            return defaultExceptionId;
        }
    }

    public static final class CallableMethod {
        private final String receiverId;
        private final String ownerType;
        private final String methodName;
        private final int argumentCount;
        private final JvmMethodDescriptor signature;
        private final String returnType;

        public CallableMethod(String receiverId, String methodName, int argumentCount) {
            this(receiverId, null, methodName, argumentCount, null);
        }

        public CallableMethod(String receiverId, String methodName, int argumentCount, String returnType) {
            this(receiverId, null, methodName, argumentCount, returnType);
        }

        public CallableMethod(String receiverId, String ownerType, String methodName, int argumentCount,
                              String returnType) {
            this.receiverId = receiverId;
            this.ownerType = ownerType;
            this.methodName = methodName;
            this.argumentCount = argumentCount;
            this.signature = null;
            this.returnType = returnType;
        }

        public CallableMethod(String receiverId, String ownerType, String methodName, String descriptor,
                              String returnType) {
            this.receiverId = receiverId;
            this.ownerType = ownerType;
            this.methodName = methodName;
            this.signature = JvmMethodDescriptor.parse(descriptor);
            this.argumentCount = signature == null ? 0 : signature.argumentCount();
            this.returnType = returnType;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CallableMethod)) {
                return false;
            }
            CallableMethod that = (CallableMethod) other;
            return argumentCount == that.argumentCount
                    && java.util.Objects.equals(receiverId, that.receiverId)
                    && java.util.Objects.equals(canonicalOwnerType(ownerType), canonicalOwnerType(that.ownerType))
                    && java.util.Objects.equals(methodName, that.methodName)
                    && java.util.Objects.equals(signatureText(), that.signatureText());
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(receiverId, canonicalOwnerType(ownerType), methodName, argumentCount,
                    signatureText());
        }

        private String signatureText() {
            return signature == null ? null : signature.descriptor();
        }

        private JvmMethodDescriptor signature() {
            return signature;
        }

        private static String canonicalOwnerType(String typeName) {
            if (typeName == null) {
                return null;
            }
            String trimmed = typeName.trim();
            int genericStart = trimmed.indexOf('<');
            return genericStart < 0 ? trimmed : trimmed.substring(0, genericStart);
        }
    }

    private static final class ParserState {
        private final ParseContext context;
        private final LlmPostProcessingResponse response;
        private final java.util.List<Diagnostic> diagnostics = new java.util.ArrayList<>();
        private int currentAssertionIndex = -1;
        private String currentAssertionId;

        private ParserState(ParseContext context, LlmPostProcessingResponse response) {
            this.context = context;
            this.response = response;
        }

        private PostProcessingOptions options() {
            return context.options() == null ? PostProcessingOptions.fromProperties() : context.options();
        }

        private void parseBasicEdits(JsonNode root) {
            PostProcessingEditValidator.validate(root, context, response, diagnostics);
        }

        private void parseAssertions(JsonNode node) {
            if (node == null || node.isNull()) {
                return;
            }
            if (!node.isArray()) {
                diagnostic(DiagnosticCode.INVALID_FIELD, "assertions", "assertions must be an array");
                return;
            }
            Set<String> assertionIds = new LinkedHashSet<>();
            Set<String> assertionKeys = new LinkedHashSet<>();
            int accepted = 0;
            for (int i = 0; i < node.size(); i++) {
                currentAssertionIndex = i;
                currentAssertionId = null;
                String path = "assertions[" + i + "]";
                if (accepted >= options().assertionPolicy().maxAssertions()) {
                    diagnostic(DiagnosticCode.LIMIT_EXCEEDED, path, "Assertion limit exceeded");
                    break;
                }
                JsonNode entry = node.get(i);
                if (entry == null || !entry.isObject()) {
                    diagnostic(DiagnosticCode.INVALID_FIELD, path, "Assertion entry must be an object");
                    continue;
                }
                entry = normalizeAssertionEntry(entry);
                reportUnknownFields(entry, path, ASSERTION_FIELDS);
                String assertionId = text(entry.get("assertionId"));
                if (assertionId == null || assertionId.trim().isEmpty()) {
                    diagnostic(DiagnosticCode.INVALID_FIELD, path + ".assertionId",
                            "assertionId must be a non-empty string");
                    continue;
                }
                assertionId = assertionId.trim();
                currentAssertionId = assertionId;
                if (!assertionIds.add(assertionId)) {
                    diagnostic(DiagnosticCode.DUPLICATE, path + ".assertionId",
                            "Duplicate assertion ID: " + assertionId);
                    continue;
                }
                String candidateId = text(entry.get("candidateId"));
                boolean selectedCandidate = candidateId != null && !candidateId.trim().isEmpty();
                SelectableCandidate candidate = null;
                if (selectedCandidate) {
                    candidate = context.selectableCandidates.get(candidateId.trim());
                    if (candidate == null) {
                        diagnostic(DiagnosticCode.UNKNOWN_ID, path + ".candidateId",
                                "Unknown candidate ID: " + candidateId.trim());
                        continue;
                    }
                }
                PostProcessingPlacementValidator.PlacementValue placement =
                        PostProcessingPlacementValidator.parse(
                                entry.get("placement"), path + ".placement", response,
                                context, candidate, currentAssertionIndex, currentAssertionId,
                                diagnostics);
                if (placement == null) {
                    continue;
                }
                if (selectedCandidate) {
                    if (!PostProcessingPlacementValidator.referencesAreAvailable(
                            candidate.expected, candidate.actual, candidate.delta,
                            placement, context, path, currentAssertionIndex, currentAssertionId,
                            diagnostics)) {
                        continue;
                    }
                    if (!hasCompatibleOperands(candidate.kind, candidate.expected, candidate.actual,
                            candidate.delta, path)
                            || !canRenderCanonicalAssertion(candidate.kind, candidate.expected,
                            candidate.actual, candidate.delta, path)) {
                        continue;
                    }
                    String intent = parseAssertionIntent(entry.get("intent"), path + ".intent");
                    if (intent == INVALID_EXPRESSION) {
                        continue;
                    }
                    String purpose = sanitizeComment(text(entry.get("purpose")));
                    if (purpose != null && purpose.length() > options().contextLimits().commentChars()) {
                        diagnostic(DiagnosticCode.LIMIT_EXCEEDED, path + ".purpose",
                                "Assertion purpose is too long");
                        purpose = null;
                    }
                    response.addAssertion(new LlmPostProcessingResponse.AssertionProposal(
                            assertionId, candidate.kind, candidate.expected, candidate.actual,
                            candidate.delta, purpose, intent, placement.site,
                            placement.afterStatementId, placement.exceptionId, candidateId.trim()));
                    accepted++;
                    continue;
                }
                LlmPostProcessingResponse.AssertionKind kind = parseKind(entry.get("kind"), path + ".kind");
                if (kind == null) {
                    continue;
                }
                if (!hasValidOperandShape(kind, entry, path)) {
                    continue;
                }
                String expected = expression(PostProcessingAssertionKindRules.expectedNode(kind, entry),
                        path + PostProcessingAssertionKindRules.expectedPathSuffix(kind),
                        PostProcessingAssertionKindRules.requiresExpected(kind));
                String actual = expression(PostProcessingAssertionKindRules.actualNode(kind, entry),
                        path + PostProcessingAssertionKindRules.actualPathSuffix(kind), true);
                String delta = expression(entry.get("delta"), path + ".delta", false);
                if ((PostProcessingAssertionKindRules.requiresExpected(kind) && expected == null)
                        || actual == null || delta == INVALID_EXPRESSION) {
                    continue;
                }
                ExprType originalExpectedType = resolveExpressionType(expected);
                ExprType originalActualType = resolveExpressionType(actual);
                expected = canonicalSpecialFloatingLiteral(expected, originalActualType);
                actual = canonicalSpecialFloatingLiteral(actual, originalExpectedType);
                String intent = parseAssertionIntent(entry.get("intent"), path + ".intent");
                if (intent == INVALID_EXPRESSION) {
                    continue;
                }
                if (!PostProcessingPlacementValidator.referencesAreAvailable(
                        expected, actual, delta, placement, context, path,
                        currentAssertionIndex, currentAssertionId, diagnostics)) {
                    continue;
                }
                if (delta == null && (kind == LlmPostProcessingResponse.AssertionKind.EQUALS
                        || kind == LlmPostProcessingResponse.AssertionKind.NOT_EQUALS)) {
                    delta = defaultFloatingDelta(expected, actual);
                }
                if (!hasCompatibleOperands(kind, expected, actual, delta, path)) {
                    continue;
                }
                if (!canRenderCanonicalAssertion(kind, expected, actual, delta, path)) {
                    continue;
                }
                String assertionKey = assertionKey(kind, expected, actual, delta);
                if (!assertionKeys.add(assertionKey)) {
                    diagnostic(DiagnosticCode.DUPLICATE, path, "Duplicate assertion expression");
                    continue;
                }
                if (!selectedCandidate && context.hasObservedCandidateKey(assertionKey)) {
                    diagnostic(DiagnosticCode.DUPLICATE, path,
                            "Assertion duplicates an EvoSuite-observed candidate fact");
                    continue;
                }
                if (isDirectSetupInputAssertion(kind, expected, actual)) {
                    diagnostic(DiagnosticCode.DUPLICATE, path,
                            "Assertion directly restates an immutable setup input");
                    continue;
                }
                if (isObviousTautology(kind, expected, actual)) {
                    diagnostic(DiagnosticCode.DUPLICATE, path, "Assertion is an obvious tautology");
                    continue;
                }
                String purpose = sanitizeComment(text(entry.get("purpose")));
                if (purpose != null && purpose.length() > options().contextLimits().commentChars()) {
                    diagnostic(DiagnosticCode.LIMIT_EXCEEDED, path + ".purpose", "Assertion purpose is too long");
                    purpose = null;
                }
                response.addAssertion(new LlmPostProcessingResponse.AssertionProposal(
                        assertionId, kind, expected, actual, delta, purpose, intent,
                        placement.site, placement.afterStatementId, placement.exceptionId, null));
                accepted++;
            }
            currentAssertionIndex = -1;
            currentAssertionId = null;
        }

        private void parseAssertionDecision(JsonNode decisionNode, JsonNode reasonNode) {
            String decision = text(decisionNode);
            String reason = text(reasonNode);
            if (decisionNode != null && !decisionNode.isNull()
                    && (decision == null || !ASSERTION_DECISIONS.contains(decision.trim()))) {
                diagnostic(DiagnosticCode.INVALID_FIELD, "assertionDecision",
                        "assertionDecision must be PROPOSED or NO_SAFE_ORACLE");
                decision = null;
            }
            if (reasonNode != null && !reasonNode.isNull()
                    && (reason == null || !NO_ASSERTION_REASONS.contains(reason.trim()))) {
                diagnostic(DiagnosticCode.INVALID_FIELD, "noAssertionReason",
                        "Unknown noAssertionReason");
                reason = null;
            }
            if (decision != null) {
                decision = decision.trim();
            }
            if (reason != null) {
                reason = reason.trim();
            }
            if (!response.getAssertions().isEmpty() && "NO_SAFE_ORACLE".equals(decision)) {
                diagnostic(DiagnosticCode.INVALID_FIELD, "assertionDecision",
                        "NO_SAFE_ORACLE cannot accompany accepted assertions");
                decision = null;
                reason = null;
            } else if (response.getAssertions().isEmpty() && "PROPOSED".equals(decision)) {
                diagnostic(DiagnosticCode.INVALID_FIELD, "assertionDecision",
                        "PROPOSED requires at least one accepted assertion");
                decision = null;
            }
            if (!"NO_SAFE_ORACLE".equals(decision) && reason != null) {
                diagnostic(DiagnosticCode.INVALID_FIELD, "noAssertionReason",
                        "noAssertionReason is only valid with NO_SAFE_ORACLE");
                reason = null;
            }
            response.setAssertionDecision(decision);
            response.setNoAssertionReason(reason);
        }

        private JsonNode normalizeAssertionEntry(JsonNode original) {
            ObjectNode entry = ((ObjectNode) original).deepCopy();
            String kind = text(entry.get("kind"));
            if (!entry.has("actual") && entry.has("expression")) {
                entry.set("actual", entry.get("expression"));
            }
            if (entry.has("value")) {
                if (!entry.has("expected") && entry.has("actual")
                        && ("EQUALS".equals(kind) || "NOT_EQUALS".equals(kind)
                        || "SAME".equals(kind) || "NOT_SAME".equals(kind))) {
                    entry.set("expected", entry.get("value"));
                } else if (!entry.has("actual")) {
                    entry.set("actual", entry.get("value"));
                }
            }
            entry.remove("value");
            entry.remove("expression");
            if ("TRUE".equals(kind) || "FALSE".equals(kind) || "NULL".equals(kind)
                    || "NOT_NULL".equals(kind) || "IS_EMPTY".equals(kind)) {
                entry.remove("expected");
            }
            for (String field : new String[]{"expected", "actual", "delta", "container", "element",
                    "target", "size", "map", "key"}) {
                JsonNode value = entry.get(field);
                if (value != null && (value.isNumber() || value.isBoolean())) {
                    entry.put(field, value.asText());
                } else if (value != null && value.isNull() && "expected".equals(field)) {
                    entry.put(field, "null");
                }
            }
            return entry;
        }

        private String defaultFloatingDelta(String expected, String actual) {
            ExprType expectedType = resolveExpressionType(expected);
            ExprType actualType = resolveExpressionType(actual);
            if (!expectedType.isFloatingPoint() && !actualType.isFloatingPoint()) {
                return null;
            }
            String type = expectedType.isFloatingPoint() ? expectedType.typeName : actualType.typeName;
            return "float".equals(type) || "java.lang.Float".equals(type) || "Float".equals(type)
                    ? "0.0F" : "0.0D";
        }

        /**
         * Runtime floating values are commonly rendered by libraries as
         * {@code Infinity}, {@code -Infinity}, or {@code NaN}. Those are not
         * Java expressions. Normalize the complete bare token using the other
         * operand's declared type, defaulting to {@code Double} when no type is
         * available.
         */
        private String canonicalSpecialFloatingLiteral(String expression, ExprType inferredType) {
            if (expression == null) {
                return null;
            }
            String token = expression.trim();
            String member;
            if ("Infinity".equals(token) || "+Infinity".equals(token)) {
                member = "POSITIVE_INFINITY";
            } else if ("-Infinity".equals(token)) {
                member = "NEGATIVE_INFINITY";
            } else if ("NaN".equals(token)) {
                member = "NaN";
            } else {
                return expression;
            }
            String typeName = inferredType == null ? null : canonicalType(inferredType.typeName);
            String owner = "float".equals(typeName) || "java.lang.Float".equals(typeName)
                    ? "Float" : "Double";
            return owner + "." + member;
        }

        private String parseAssertionIntent(JsonNode node, String path) {
            if (node == null || node.isNull()) {
                return "REGRESSION";
            }
            String intent = text(node);
            if (intent == null || intent.trim().isEmpty()) {
                diagnostic(DiagnosticCode.INVALID_FIELD, path, "Assertion intent must be a non-empty string");
                return INVALID_EXPRESSION;
            }
            intent = intent.trim();
            if (!"REGRESSION".equals(intent)) {
                diagnostic(DiagnosticCode.UNSUPPORTED_KIND, path,
                        "Only REGRESSION assertion intent is supported by schema version 1");
                return INVALID_EXPRESSION;
            }
            return intent;
        }

        private boolean hasCompatibleOperands(LlmPostProcessingResponse.AssertionKind kind, String expected,
                                              String actual, String delta, String path) {
            ExprType actualType = resolveExpressionType(actual);
            ExprType expectedType = resolveExpressionType(expected);
            ExprType deltaType = resolveExpressionType(delta);
            switch (kind) {
                case TRUE:
                case FALSE:
                    if (actualType.isKnown() && !actualType.isBoolean()) {
                        diagnostic(DiagnosticCode.INVALID_FIELD, path + ".actual",
                                "TRUE/FALSE actual must be boolean-compatible");
                        return false;
                    }
                    return true;
                case NULL:
                case NOT_NULL:
                    if (actualType.isKnown() && !actualType.isReferenceLike()) {
                        diagnostic(DiagnosticCode.INVALID_FIELD, path + ".actual",
                                "NULL/NOT_NULL actual must be reference-typed");
                        return false;
                    }
                    return true;
                case SAME:
                case NOT_SAME:
                    if ((expectedType.isKnown() && !expectedType.isReferenceLike())
                            || (actualType.isKnown() && !actualType.isReferenceLike())) {
                        diagnostic(DiagnosticCode.INVALID_FIELD, path,
                                "SAME/NOT_SAME operands must be reference-typed");
                        return false;
                    }
                    return true;
                case EQUALS:
                case NOT_EQUALS:
                    if (actualType.isArray() || expectedType.isArray()) {
                        return hasCompatibleArrayOperands(kind, expectedType, actualType, path);
                    }
                    if (actualType.isKnown() && expectedType.isKnown()
                            && !areEqualsCompatible(expectedType, actualType)) {
                        diagnostic(DiagnosticCode.INVALID_FIELD, path,
                                "EQUALS/NOT_EQUALS operands are not type-compatible");
                        return false;
                    }
                    boolean floating = actualType.isFloatingPoint() || expectedType.isFloatingPoint();
                    boolean bothOperandsKnown = actualType.isKnown() && expectedType.isKnown();
                    if (floating && delta == null) {
                        diagnostic(DiagnosticCode.INVALID_FIELD, path + ".delta",
                                "Floating-point equality requires a delta");
                        return false;
                    }
                    if (bothOperandsKnown && !floating && delta != null) {
                        diagnostic(DiagnosticCode.INVALID_FIELD, path + ".delta",
                                "Delta is only allowed for floating-point equality");
                        return false;
                    }
                    if (delta != null && deltaType.isKnown() && !deltaType.isNumeric()) {
                        diagnostic(DiagnosticCode.INVALID_FIELD, path + ".delta", "Delta must be numeric");
                        return false;
                    }
                    if (delta != null && isNegativeNumericLiteral(delta)) {
                        diagnostic(DiagnosticCode.INVALID_FIELD, path + ".delta", "Delta must be non-negative");
                        return false;
                    }
                    return true;
                case CONTAINS:
                case NOT_CONTAINS:
                    if (actualType.isKnown() && !actualType.isReferenceLike()) {
                        diagnostic(DiagnosticCode.INVALID_FIELD, path + ".actual",
                                "CONTAINS/NOT_CONTAINS container must be reference-typed");
                        return false;
                    }
                    return hasCallableForRenderedPredicate(actual, "contains", expected, path + ".actual");
                case SIZE_EQUALS:
                    if (actualType.isKnown() && !actualType.isReferenceLike()) {
                        diagnostic(DiagnosticCode.INVALID_FIELD, path + ".actual",
                                "SIZE_EQUALS target must be reference-typed");
                        return false;
                    }
                    if (expectedType.isKnown() && !expectedType.isNumericLike()) {
                        diagnostic(DiagnosticCode.INVALID_FIELD, path + ".expected",
                                "SIZE_EQUALS size must be numeric-compatible");
                        return false;
                    }
                    return hasCallableForRenderedPredicate(actual, "size", null, path + ".actual");
                case MAP_CONTAINS_KEY:
                    if (actualType.isKnown() && !actualType.isReferenceLike()) {
                        diagnostic(DiagnosticCode.INVALID_FIELD, path + ".actual",
                                "MAP_CONTAINS_KEY map must be reference-typed");
                        return false;
                    }
                    return hasCallableForRenderedPredicate(actual, "containsKey", expected, path + ".actual");
                case IS_EMPTY:
                    if (actualType.isKnown() && !actualType.isReferenceLike()) {
                        diagnostic(DiagnosticCode.INVALID_FIELD, path + ".actual",
                                "IS_EMPTY target must be reference-typed");
                        return false;
                    }
                    return hasCallableForRenderedPredicate(actual, "isEmpty", null, path + ".actual");
                case GREATER:
                case LESS:
                case GREATER_EQUALS:
                case LESS_EQUALS:
                    if (actualType.isKnown() && !actualType.isNumericLike()) {
                        diagnostic(DiagnosticCode.INVALID_FIELD, path + ".actual",
                                "Relational operands must be numeric-compatible");
                        return false;
                    }
                    if (expectedType.isKnown() && !expectedType.isNumericLike()) {
                        diagnostic(DiagnosticCode.INVALID_FIELD, path + ".expected",
                                "Relational operands must be numeric-compatible");
                        return false;
                    }
                    return true;
                default:
                    return true;
            }
        }

        private boolean hasCallableForRenderedPredicate(String receiverExpression, String methodName,
                                                        String argumentExpression,
                                                        String path) {
            try {
                Expression parsed = StaticJavaParser.parseExpression(receiverExpression);
                MethodCallExpr renderedCall = new MethodCallExpr(parsed, methodName);
                if (argumentExpression != null) {
                    renderedCall.addArgument(StaticJavaParser.parseExpression(argumentExpression));
                }
                if (parsed instanceof NameExpr) {
                    String receiverId = ((NameExpr) parsed).getNameAsString();
                    if (isCallableMethod(receiverId, null, renderedCall)) {
                        return true;
                    }
                }
                ExprType receiverType = resolveExpressionType(parsed);
                if (receiverType.isKnown() && isCallableMethod(null, receiverType.typeName, renderedCall)) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Syntax has already been validated by expression().
            }
            diagnostic(DiagnosticCode.INVALID_FIELD, path,
                    "Rendered predicate requires a matching callable member " + methodName);
            return false;
        }

        private boolean hasCompatibleArrayOperands(LlmPostProcessingResponse.AssertionKind kind, ExprType expectedType,
                                                   ExprType actualType, String path) {
            if (kind == LlmPostProcessingResponse.AssertionKind.NOT_EQUALS) {
                diagnostic(DiagnosticCode.UNSUPPORTED_KIND, path,
                        "NOT_EQUALS on arrays is unsupported in schema version 1");
                return false;
            }
            if ((expectedType.isArray() && !actualType.isKnown())
                    || (actualType.isArray() && !expectedType.isKnown())) {
                return true;
            }
            if (!expectedType.isArray() || !actualType.isArray()) {
                diagnostic(DiagnosticCode.INVALID_FIELD, path,
                        "Array equality requires array operands on both sides");
                return false;
            }
            if (expectedType.arrayDepth != 1 || actualType.arrayDepth != 1) {
                diagnostic(DiagnosticCode.INVALID_FIELD, path,
                        "Array equality supports one-dimensional arrays only");
                return false;
            }
            if (expectedType.componentType != null && actualType.componentType != null
                    && !sameType(expectedType.componentType, actualType.componentType)) {
                diagnostic(DiagnosticCode.INVALID_FIELD, path,
                        "Array equality requires matching component types");
                return false;
            }
            return true;
        }

        private boolean canRenderCanonicalAssertion(LlmPostProcessingResponse.AssertionKind kind, String expected,
                                                    String actual, String delta, String path) {
            try {
                ExprType actualType = resolveExpressionType(actual);
                ExprType expectedType = resolveExpressionType(expected);
                boolean arrayEquality = kind == LlmPostProcessingResponse.AssertionKind.EQUALS
                        && delta == null
                        && (actualType.isArray() || expectedType.isArray());
                String rendered = CanonicalAssertionRenderer.forConfiguredFormat().render(
                        TemplateAssertionKind.valueOf(kind.name()), expected, actual, delta,
                        arrayEquality);
                if (rendered == null || rendered.trim().isEmpty()) {
                    diagnostic(DiagnosticCode.INVALID_FIELD, path, "Assertion cannot be rendered");
                    return false;
                }
                return true;
            } catch (RuntimeException e) {
                diagnostic(DiagnosticCode.INVALID_FIELD, path,
                        "Assertion cannot be rendered for the configured output format");
                return false;
            }
        }

        private boolean areEqualsCompatible(ExprType expectedType, ExprType actualType) {
            if (expectedType.isNull() || actualType.isNull()) {
                return expectedType.isReferenceLike() || actualType.isReferenceLike();
            }
            if (expectedType.isNumericLike() && actualType.isNumericLike()) {
                return true;
            }
            if (expectedType.isBooleanLike() && actualType.isBooleanLike()) {
                return true;
            }
            if (expectedType.isCharLike() && actualType.isCharLike()) {
                return true;
            }
            if (expectedType.isReferenceLike() && actualType.isReferenceLike()) {
                return sameType(expectedType.typeName, actualType.typeName)
                        || "java.lang.Object".equals(canonicalType(expectedType.typeName))
                        || "java.lang.Object".equals(canonicalType(actualType.typeName))
                        || areReferenceTypesAssignmentCompatible(expectedType.typeName, actualType.typeName);
            }
            return false;
        }

        private boolean areReferenceTypesAssignmentCompatible(String first, String second) {
            try {
                ClassLoader loader = org.evosuite.TestGenerationContext.getInstance().getClassLoaderForSUT();
                Class<?> firstClass = Class.forName(canonicalType(first), false, loader);
                Class<?> secondClass = Class.forName(canonicalType(second), false, loader);
                return firstClass.isAssignableFrom(secondClass) || secondClass.isAssignableFrom(firstClass)
                        || hasCommonComparableSupertype(firstClass, secondClass);
            } catch (Throwable ignored) {
                return false;
            }
        }

        private boolean hasCommonComparableSupertype(Class<?> first, Class<?> second) {
            for (Class<?> type : first.getInterfaces()) {
                if (type.equals(java.io.Serializable.class) || type.equals(Cloneable.class)) {
                    continue;
                }
                if (type.isAssignableFrom(second)) {
                    return true;
                }
            }
            Class<?> superclass = first.getSuperclass();
            return superclass != null && !Object.class.equals(superclass) && superclass.isAssignableFrom(second);
        }

        private ExprType resolveExpressionType(String expression) {
            if (expression == null || expression.trim().isEmpty()) {
                return ExprType.unknown();
            }
            try {
                return resolveExpressionType(StaticJavaParser.parseExpression(expression));
            } catch (RuntimeException e) {
                return ExprType.unknown();
            }
        }

        private ExprType resolveExpressionType(Expression expression) {
            if (expression == null) {
                return ExprType.unknown();
            }
            if (expression.isEnclosedExpr()) {
                return resolveExpressionType(expression.asEnclosedExpr().getInner());
            }
            if (expression.isBooleanLiteralExpr()) {
                return ExprType.primitive("boolean");
            }
            if (expression.isNullLiteralExpr()) {
                return ExprType.nullType();
            }
            if (expression.isStringLiteralExpr()) {
                return ExprType.reference("java.lang.String");
            }
            if (expression.isCharLiteralExpr()) {
                return ExprType.primitive("char");
            }
            if (expression.isIntegerLiteralExpr()) {
                return ExprType.primitive("int");
            }
            if (expression.isLongLiteralExpr()) {
                return ExprType.primitive("long");
            }
            if (expression.isDoubleLiteralExpr()) {
                String value = expression.asDoubleLiteralExpr().getValue().toLowerCase();
                return ExprType.primitive(value.endsWith("f") ? "float" : "double");
            }
            if (expression instanceof NameExpr) {
                return ExprType.fromTypeName(context.variableType(((NameExpr) expression).getNameAsString()));
            }
            if (expression instanceof ArrayCreationExpr) {
                ArrayCreationExpr arrayCreation = (ArrayCreationExpr) expression;
                return ExprType.array(arrayCreation.getElementType().asString(), arrayCreation.getLevels().size());
            }
            if (expression instanceof ObjectCreationExpr) {
                return ExprType.reference(((ObjectCreationExpr) expression).getTypeAsString());
            }
            if (expression instanceof MethodCallExpr) {
                return resolveMethodCallType((MethodCallExpr) expression);
            }
            if (expression instanceof FieldAccessExpr) {
                FieldAccessExpr fieldAccess = (FieldAccessExpr) expression;
                if ("length".equals(fieldAccess.getNameAsString())
                        && resolveExpressionType(fieldAccess.getScope()).isArray()) {
                    return ExprType.primitive("int");
                }
                return ExprType.unknown();
            }
            if (expression instanceof UnaryExpr) {
                UnaryExpr unaryExpr = (UnaryExpr) expression;
                if (unaryExpr.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
                    return ExprType.primitive("boolean");
                }
                return resolveExpressionType(unaryExpr.getExpression());
            }
            if (expression instanceof BinaryExpr) {
                return resolveBinaryType((BinaryExpr) expression);
            }
            return ExprType.unknown();
        }

        private ExprType resolveMethodCallType(MethodCallExpr methodCall) {
            if (methodCall.getScope().isPresent() && methodCall.getScope().get() instanceof NameExpr) {
                String receiverId = ((NameExpr) methodCall.getScope().get()).getNameAsString();
                String returnType = callableReturnType(receiverId, null, methodCall);
                if (returnType != null) {
                    return ExprType.fromTypeName(returnType);
                }
            }
            if (methodCall.getScope().isPresent()
                    && options().assertionPolicy().allowChainedCalls()
                    && isInstanceCallScope(methodCall.getScope().get())) {
                ExprType receiverType = resolveExpressionType(methodCall.getScope().get());
                String returnType = callableReturnType(null, receiverType.typeName, methodCall);
                if (returnType != null) {
                    return ExprType.fromTypeName(returnType);
                }
            }
            if (methodCall.getScope().isPresent() && !isInstanceCallScope(methodCall.getScope().get())) {
                return staticMethodReturnType(methodCall.getScope().get().toString(), methodCall);
            }
            return ExprType.unknown();
        }

        private String callableReturnType(String receiverId, String ownerType, MethodCallExpr call) {
            String selectedReturnType = null;
            int selectedScore = Integer.MAX_VALUE;
            for (CallableMethod method : context.callableMethods) {
                if (!java.util.Objects.equals(receiverId, method.receiverId)
                        || (ownerType != null && !java.util.Objects.equals(
                        CallableMethod.canonicalOwnerType(ownerType),
                        CallableMethod.canonicalOwnerType(method.ownerType)))
                        || !java.util.Objects.equals(call.getNameAsString(), method.methodName)
                        || call.getArguments().size() != method.argumentCount) {
                    continue;
                }
                int score = descriptorMatchScore(call, method.signature());
                if (score >= 0 && score < selectedScore) {
                    selectedScore = score;
                    selectedReturnType = method.returnType;
                }
            }
            return selectedReturnType;
        }

        private ExprType staticMethodReturnType(String owner, MethodCallExpr call) {
            String methodName = call.getNameAsString();
            String canonicalOwner = canonicalType(owner);
            if ("java.lang.Boolean".equals(canonicalOwner) && "valueOf".equals(methodName)) {
                return ExprType.reference("java.lang.Boolean");
            }
            if ("java.lang.Byte".equals(canonicalOwner) && ("valueOf".equals(methodName) || "parseByte".equals(methodName))) {
                return "parseByte".equals(methodName) ? ExprType.primitive("byte") : ExprType.reference("java.lang.Byte");
            }
            if ("java.lang.Short".equals(canonicalOwner) && ("valueOf".equals(methodName) || "parseShort".equals(methodName))) {
                return "parseShort".equals(methodName) ? ExprType.primitive("short") : ExprType.reference("java.lang.Short");
            }
            if ("java.lang.Integer".equals(canonicalOwner) && ("valueOf".equals(methodName) || "parseInt".equals(methodName))) {
                return "parseInt".equals(methodName) ? ExprType.primitive("int") : ExprType.reference("java.lang.Integer");
            }
            if ("java.lang.Long".equals(canonicalOwner) && ("valueOf".equals(methodName) || "parseLong".equals(methodName))) {
                return "parseLong".equals(methodName) ? ExprType.primitive("long") : ExprType.reference("java.lang.Long");
            }
            if ("java.lang.Float".equals(canonicalOwner) && ("valueOf".equals(methodName) || "parseFloat".equals(methodName))) {
                return "parseFloat".equals(methodName) ? ExprType.primitive("float") : ExprType.reference("java.lang.Float");
            }
            if ("java.lang.Double".equals(canonicalOwner) && ("valueOf".equals(methodName) || "parseDouble".equals(methodName))) {
                return "parseDouble".equals(methodName) ? ExprType.primitive("double") : ExprType.reference("java.lang.Double");
            }
            if ("java.math.BigInteger".equals(canonicalOwner) && "valueOf".equals(methodName)) {
                return ExprType.reference("java.math.BigInteger");
            }
            if ("java.math.BigDecimal".equals(canonicalOwner) && "valueOf".equals(methodName)) {
                return ExprType.reference("java.math.BigDecimal");
            }
            if ("java.util.Optional".equals(canonicalOwner)) {
                return ExprType.reference("java.util.Optional");
            }
            for (String entry : allStaticAllowlistEntries()) {
                if (!isBuiltInStaticEntry(entry) && matchesStaticAllowlistEntry(owner, call, entry)) {
                    int descriptorStart = entry.indexOf('(');
                    int close = entry.indexOf(')', descriptorStart + 1);
                    if (descriptorStart > 0 && close > descriptorStart) {
                        JvmMethodDescriptor signature = JvmMethodDescriptor.parse(
                                entry.substring(descriptorStart));
                        String returnType = signature == null || !signature.isValid()
                                ? null : descriptorTypeName(signature.returnDescriptor());
                        if (returnType != null) {
                            return ExprType.fromTypeName(returnType);
                        }
                    }
                }
            }
            return ExprType.unknown();
        }

        private ExprType resolveBinaryType(BinaryExpr binaryExpr) {
            switch (binaryExpr.getOperator()) {
                case EQUALS:
                case NOT_EQUALS:
                case LESS:
                case LESS_EQUALS:
                case GREATER:
                case GREATER_EQUALS:
                case AND:
                case OR:
                    return ExprType.primitive("boolean");
                case PLUS:
                    ExprType left = resolveExpressionType(binaryExpr.getLeft());
                    ExprType right = resolveExpressionType(binaryExpr.getRight());
                    if ("java.lang.String".equals(canonicalType(left.typeName))
                            || "java.lang.String".equals(canonicalType(right.typeName))) {
                        return ExprType.reference("java.lang.String");
                    }
                    return promotedNumericType(left, right);
                case MINUS:
                case MULTIPLY:
                case DIVIDE:
                case REMAINDER:
                    return promotedNumericType(resolveExpressionType(binaryExpr.getLeft()),
                            resolveExpressionType(binaryExpr.getRight()));
                default:
                    return ExprType.unknown();
            }
        }

        private ExprType promotedNumericType(ExprType left, ExprType right) {
            if (!left.isNumericLike() || !right.isNumericLike()) {
                return ExprType.unknown();
            }
            if (left.isDoubleLike() || right.isDoubleLike()) {
                return ExprType.primitive("double");
            }
            if (left.isFloatLike() || right.isFloatLike()) {
                return ExprType.primitive("float");
            }
            if (left.isLongLike() || right.isLongLike()) {
                return ExprType.primitive("long");
            }
            return ExprType.primitive("int");
        }

        private boolean isNegativeNumericLiteral(String expression) {
            try {
                Expression parsed = StaticJavaParser.parseExpression(expression);
                if (parsed instanceof UnaryExpr) {
                    UnaryExpr unaryExpr = (UnaryExpr) parsed;
                    return unaryExpr.getOperator() == UnaryExpr.Operator.MINUS
                            && resolveExpressionType(unaryExpr.getExpression()).isNumeric();
                }
                return false;
            } catch (RuntimeException e) {
                return false;
            }
        }

        private boolean sameType(String first, String second) {
            return first != null && second != null && canonicalType(first).equals(canonicalType(second));
        }

        private String canonicalType(String typeName) {
            if (typeName == null) {
                return "";
            }
            String trimmed = typeName.trim();
            if ("String".equals(trimmed)) {
                return "java.lang.String";
            }
            if ("Boolean".equals(trimmed)) {
                return "java.lang.Boolean";
            }
            if ("Byte".equals(trimmed)) {
                return "java.lang.Byte";
            }
            if ("Short".equals(trimmed)) {
                return "java.lang.Short";
            }
            if ("Character".equals(trimmed)) {
                return "java.lang.Character";
            }
            if ("Integer".equals(trimmed)) {
                return "java.lang.Integer";
            }
            if ("Long".equals(trimmed)) {
                return "java.lang.Long";
            }
            if ("Float".equals(trimmed)) {
                return "java.lang.Float";
            }
            if ("Double".equals(trimmed)) {
                return "java.lang.Double";
            }
            if ("Object".equals(trimmed)) {
                return "java.lang.Object";
            }
            if ("BigInteger".equals(trimmed)) {
                return "java.math.BigInteger";
            }
            if ("BigDecimal".equals(trimmed)) {
                return "java.math.BigDecimal";
            }
            if ("Optional".equals(trimmed)) {
                return "java.util.Optional";
            }
            return trimmed;
        }

        private String assertionKey(LlmPostProcessingResponse.AssertionKind kind, String expected, String actual,
                                    String delta) {
            return kind.name()
                    + "|" + normalizedExpression(expected)
                    + "|" + normalizedExpression(actual)
                    + "|" + normalizedExpression(delta);
        }

        private String normalizedExpression(String expression) {
            return expression == null ? "" : expression.replaceAll("\\s+", "");
        }

        private boolean isDirectSetupInputAssertion(LlmPostProcessingResponse.AssertionKind kind,
                                                    String expected, String actual) {
            String normalizedActual = normalizedExpression(actual);
            String normalizedExpected = normalizedExpression(expected);
            if (normalizedActual.isEmpty()) {
                return false;
            }
            if (isDirectSetupInputVariable(normalizedActual)
                    && (normalizedExpected.isEmpty() || isLiteralOrNullExpression(normalizedExpected))) {
                return true;
            }
            return PostProcessingAssertionKindRules.requiresExpected(kind)
                    && isDirectSetupInputVariable(normalizedExpected)
                    && isLiteralOrNullExpression(normalizedActual);
        }

        private boolean isDirectSetupInputVariable(String expression) {
            return context.isSetupInputVariableId(expression);
        }

        private boolean isLiteralOrNullExpression(String expression) {
            if (expression == null || expression.isEmpty()) {
                return false;
            }
            Expression parsed;
            try {
                parsed = StaticJavaParser.parseExpression(expression);
            } catch (RuntimeException e) {
                return false;
            }
            return parsed.isLiteralExpr() || parsed.isNullLiteralExpr();
        }

        private boolean isObviousTautology(LlmPostProcessingResponse.AssertionKind kind, String expected,
                                           String actual) {
            String normalizedExpected = normalizedExpression(expected);
            String normalizedActual = normalizedExpression(actual);
            if ((kind == LlmPostProcessingResponse.AssertionKind.EQUALS
                    || kind == LlmPostProcessingResponse.AssertionKind.SAME)
                    && !normalizedExpected.isEmpty()
                    && normalizedExpected.equals(normalizedActual)) {
                return true;
            }
            if (PostProcessingAssertionKindRules.isRelational(kind)
                    && !normalizedExpected.isEmpty()
                    && normalizedExpected.equals(normalizedActual)) {
                // actual OP actual is degenerate: strict forms are always false,
                // non-strict forms are always true. Neither adds value.
                return true;
            }
            if (kind == LlmPostProcessingResponse.AssertionKind.NOT_EQUALS
                    || kind == LlmPostProcessingResponse.AssertionKind.NOT_SAME) {
                return false;
            }
            if ((kind == LlmPostProcessingResponse.AssertionKind.TRUE
                    || kind == LlmPostProcessingResponse.AssertionKind.FALSE)
                    && isSelfComparison(actual, kind == LlmPostProcessingResponse.AssertionKind.TRUE)) {
                return true;
            }
            return false;
        }

        private boolean isSelfComparison(String expression, boolean assertedTrue) {
            if (expression == null || expression.trim().isEmpty()) {
                return false;
            }
            Expression parsed;
            try {
                parsed = StaticJavaParser.parseExpression(expression);
            } catch (RuntimeException e) {
                return false;
            }
            if (!(parsed instanceof BinaryExpr)) {
                return false;
            }
            BinaryExpr binary = (BinaryExpr) parsed;
            if (!normalizedExpression(binary.getLeft().toString())
                    .equals(normalizedExpression(binary.getRight().toString()))) {
                return false;
            }
            BinaryExpr.Operator operator = binary.getOperator();
            if (assertedTrue) {
                return operator == BinaryExpr.Operator.EQUALS
                        || operator == BinaryExpr.Operator.LESS_EQUALS
                        || operator == BinaryExpr.Operator.GREATER_EQUALS;
            }
            return operator == BinaryExpr.Operator.NOT_EQUALS
                    || operator == BinaryExpr.Operator.LESS
                    || operator == BinaryExpr.Operator.GREATER;
        }

        private boolean hasValidOperandShape(LlmPostProcessingResponse.AssertionKind kind, JsonNode entry,
                                             String path) {
            boolean valid = true;
            if (isForbidden(entry, "expected") && !PostProcessingAssertionKindRules.requiresExpected(kind)) {
                diagnostic(DiagnosticCode.INVALID_FIELD, path + ".expected",
                        "expected is not allowed for assertion kind " + kind);
                valid = false;
            }
            if (isForbidden(entry, "delta") && !PostProcessingAssertionKindRules.allowsDelta(kind)) {
                diagnostic(DiagnosticCode.INVALID_FIELD, path + ".delta",
                        "delta is not allowed for assertion kind " + kind);
                valid = false;
            }
            return valid;
        }

        private LlmPostProcessingResponse.AssertionKind parseKind(JsonNode node, String path) {
            String kind = text(node);
            if (kind == null || kind.trim().isEmpty()) {
                diagnostic(DiagnosticCode.INVALID_FIELD, path, "Assertion kind must be a non-empty string");
                return null;
            }
            try {
                return LlmPostProcessingResponse.AssertionKind.valueOf(kind.trim());
            } catch (IllegalArgumentException e) {
                diagnostic(DiagnosticCode.UNSUPPORTED_KIND, path, "Unsupported assertion kind: " + kind);
                return null;
            }
        }

        private String expression(JsonNode node, String path, boolean required) {
            if (node == null || node.isNull()) {
                if (required) {
                    diagnostic(DiagnosticCode.INVALID_FIELD, path, "Expression is required");
                }
                return null;
            }
            if (!node.isTextual()) {
                diagnostic(DiagnosticCode.INVALID_FIELD, path, "Expression must be a string");
                return required ? null : INVALID_EXPRESSION;
            }
            String expression = node.asText().trim();
            if (expression.isEmpty()) {
                diagnostic(DiagnosticCode.INVALID_FIELD, path, "Expression must be non-empty");
                return required ? null : INVALID_EXPRESSION;
            }
            if (expression.endsWith(";")) {
                diagnostic(DiagnosticCode.INVALID_FIELD, path, "Expression must not end with a semicolon");
                return required ? null : INVALID_EXPRESSION;
            }
            if (expression.length() > options().assertionPolicy().maxExpressionChars()) {
                diagnostic(DiagnosticCode.LIMIT_EXCEEDED, path, "Expression is too long");
                return required ? null : INVALID_EXPRESSION;
            }
            Expression parsed;
            try {
                parsed = StaticJavaParser.parseExpression(expression);
            } catch (RuntimeException e) {
                diagnostic(DiagnosticCode.INVALID_FIELD, path, "Expression is not valid Java syntax");
                return required ? null : INVALID_EXPRESSION;
            }
            boolean normalizedVariableAlias = normalizeProposedVariableAliases(parsed);
            if (exceedsExpressionNodeLimit(parsed)) {
                diagnostic(DiagnosticCode.LIMIT_EXCEEDED, path, "Expression AST node limit exceeded");
                return required ? null : INVALID_EXPRESSION;
            }
            if (LlmPostProcessingExpressionUtils.containsUnsupportedExpressionConstruct(parsed)) {
                diagnostic(DiagnosticCode.INVALID_FIELD, path,
                        "Expression contains unsupported mutation or code block constructs");
                return required ? null : INVALID_EXPRESSION;
            }
            if (LlmPostProcessingExpressionUtils.containsRawAssertionCall(parsed)) {
                diagnostic(DiagnosticCode.INVALID_FIELD, path,
                        "Expression must not contain raw assertion calls");
                return required ? null : INVALID_EXPRESSION;
            }
            if (LlmPostProcessingExpressionUtils.containsArbitraryToStringCall(parsed)) {
                diagnostic(DiagnosticCode.INVALID_FIELD, path,
                        "Expression must not use arbitrary toString() calls");
                return required ? null : INVALID_EXPRESSION;
            }
            if (containsDisallowedObjectConstruction(parsed)) {
                diagnostic(DiagnosticCode.INVALID_FIELD, path,
                        "Expression contains non-allowlisted object construction");
                return required ? null : INVALID_EXPRESSION;
            }
            if (referencesUnknownVariableId(parsed)) {
                diagnostic(DiagnosticCode.UNKNOWN_ID, path, "Expression references an unknown variable ID");
                return required ? null : INVALID_EXPRESSION;
            }
            if (containsDisallowedStaticMethodCall(parsed)) {
                diagnostic(DiagnosticCode.INVALID_FIELD, path,
                        "Expression contains a non-allowlisted static method call");
                return required ? null : INVALID_EXPRESSION;
            }
            if (exceedsChainDepthLimit(parsed)) {
                diagnostic(DiagnosticCode.LIMIT_EXCEEDED, path, "Expression member-chain depth exceeded");
                return required ? null : INVALID_EXPRESSION;
            }
            if (containsDisallowedInstanceMethodCall(parsed)) {
                diagnostic(DiagnosticCode.INVALID_FIELD, path,
                        "Expression contains a non-allowlisted instance method call");
                return required ? null : INVALID_EXPRESSION;
            }
            if (exceedsLiteralCharLimit(parsed)) {
                diagnostic(DiagnosticCode.LIMIT_EXCEEDED, path, "Expression literal character limit exceeded");
                return required ? null : INVALID_EXPRESSION;
            }
            if (exceedsConstructedArrayElementLimit(parsed)) {
                diagnostic(DiagnosticCode.LIMIT_EXCEEDED, path, "Constructed array element limit exceeded");
                return required ? null : INVALID_EXPRESSION;
            }
            if (containsDisallowedArrayConstruction(parsed)) {
                diagnostic(DiagnosticCode.INVALID_FIELD, path,
                        "Constructed arrays must be one-dimensional and contain only literal or null elements");
                return required ? null : INVALID_EXPRESSION;
            }
            return normalizedVariableAlias ? parsed.toString() : expression;
        }

        private boolean normalizeProposedVariableAliases(Expression expression) {
            if (expression == null || response.getVariableNames().isEmpty()) {
                return false;
            }
            Map<String, String> uniqueAliases = new LinkedHashMap<>();
            Set<String> ambiguousAliases = new LinkedHashSet<>();
            for (Map.Entry<String, String> rename : response.getVariableNames().entrySet()) {
                String variableId = rename.getKey();
                String alias = rename.getValue();
                if (alias == null || alias.equals(variableId)) {
                    continue;
                }
                String previous = uniqueAliases.put(alias, variableId);
                if (previous != null && !previous.equals(variableId)) {
                    ambiguousAliases.add(alias);
                }
            }
            for (String alias : ambiguousAliases) {
                uniqueAliases.remove(alias);
            }
            boolean changed = false;
            for (NameExpr name : expression.findAll(NameExpr.class)) {
                String variableId = uniqueAliases.get(name.getNameAsString());
                if (variableId != null) {
                    name.setName(variableId);
                    changed = true;
                }
            }
            return changed;
        }

        private boolean exceedsExpressionNodeLimit(Expression expression) {
            int limit = options().assertionPolicy().maxExpressionNodes();
            if (limit <= 0) {
                return false;
            }
            return expression.findAll(Node.class).size() > limit;
        }

        private boolean containsDisallowedObjectConstruction(Expression expression) {
            for (ObjectCreationExpr objectCreation : expression.findAll(ObjectCreationExpr.class)) {
                if (!isAllowedImmutableConstructorType(objectCreation.getTypeAsString())) {
                    return true;
                }
            }
            return false;
        }

        private boolean containsDisallowedStaticMethodCall(Expression expression) {
            for (MethodCallExpr methodCall : expression.findAll(MethodCallExpr.class)) {
                if (!methodCall.getScope().isPresent()) {
                    return true;
                }
                Expression scope = methodCall.getScope().get();
                if (isInstanceCallScope(scope)) {
                    continue;
                }
                if (isDeniedStaticCall(scope.toString(), methodCall.getNameAsString())) {
                    return true;
                }
                if (!isAllowedStaticCall(scope.toString(), methodCall)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isInstanceCallScope(Expression scope) {
            if (scope instanceof NameExpr) {
                return context.hasVariableId(((NameExpr) scope).getNameAsString());
            }
            if (scope instanceof MethodCallExpr) {
                MethodCallExpr methodCall = (MethodCallExpr) scope;
                return methodCall.getScope().isPresent() && isInstanceCallScope(methodCall.getScope().get());
            }
            if (scope instanceof FieldAccessExpr) {
                return isInstanceCallScope(((FieldAccessExpr) scope).getScope());
            }
            return false;
        }

        private boolean containsDisallowedInstanceMethodCall(Expression expression) {
            for (MethodCallExpr methodCall : expression.findAll(MethodCallExpr.class)) {
                if (!methodCall.getScope().isPresent()) {
                    continue;
                }
                Expression scope = methodCall.getScope().get();
                if (!isInstanceCallScope(scope)) {
                    continue;
                }
                if (isDeniedInstanceCall(methodCall.getNameAsString())) {
                    return true;
                }
                if (scope instanceof NameExpr) {
                    String receiverId = ((NameExpr) scope).getNameAsString();
                    if (!isCallableMethod(receiverId, null, methodCall)) {
                        return true;
                    }
                    continue;
                }
                if (!options().assertionPolicy().allowChainedCalls()) {
                    return true;
                }
                ExprType receiverType = resolveExpressionType(scope);
                if (!receiverType.isKnown()
                        || !isCallableMethod(null, receiverType.typeName, methodCall)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isCallableMethod(String receiverId, String ownerType, MethodCallExpr call) {
            for (CallableMethod method : context.callableMethods) {
                if (!java.util.Objects.equals(receiverId, method.receiverId)
                        || (ownerType != null && !java.util.Objects.equals(
                        CallableMethod.canonicalOwnerType(ownerType),
                        CallableMethod.canonicalOwnerType(method.ownerType)))
                        || !java.util.Objects.equals(call.getNameAsString(), method.methodName)
                        || call.getArguments().size() != method.argumentCount) {
                    continue;
                }
                if (argumentsMatchDescriptor(call, method.signature())) {
                    return true;
                }
            }
            return false;
        }

        private boolean isDeniedStaticCall(String owner, String methodName) {
            return DENIED_STATIC_OWNERS.contains(owner)
                    || DENIED_STATIC_METHODS.contains(owner + "#" + methodName)
                    || DENIED_STATIC_METHODS.contains(simpleName(owner) + "#" + methodName);
        }

        private boolean isDeniedInstanceCall(String methodName) {
            return DENIED_INSTANCE_METHODS.contains(methodName);
        }

        private boolean isAllowedStaticCall(String owner, MethodCallExpr call) {
            for (String allowlistEntry : allStaticAllowlistEntries()) {
                if (matchesStaticAllowlistEntry(owner, call, allowlistEntry)) {
                    return true;
                }
            }
            return false;
        }

        private Set<String> allStaticAllowlistEntries() {
            Set<String> entries = new LinkedHashSet<>(BUILT_IN_PURE_STATIC_METHODS);
            if (!options().assertionPolicy().pureStaticAllowlist().isEmpty()) {
                for (String entry : options().assertionPolicy().pureStaticAllowlist()) {
                    if (isValidConfiguredStaticAllowlistEntry(entry)) {
                        entries.add(entry);
                    }
                }
            }
            return entries;
        }

        private boolean matchesStaticAllowlistEntry(String owner, MethodCallExpr call, String entry) {
            int separator = entry.indexOf('#');
            if (separator <= 0 || separator == entry.length() - 1) {
                return false;
            }
            String allowedOwner = entry.substring(0, separator);
            String allowedMember = entry.substring(separator + 1);
            if (!owner.equals(allowedOwner) && !owner.equals(simpleName(allowedOwner))) {
                return false;
            }
            if ("*".equals(allowedMember)) {
                return isBuiltInStaticEntry(entry) && !"random".equals(call.getNameAsString());
            }
            int descriptorStart = allowedMember.indexOf('(');
            String allowedMethod = descriptorStart < 0 ? allowedMember : allowedMember.substring(0, descriptorStart);
            if (!call.getNameAsString().equals(allowedMethod)) {
                return false;
            }
            if (descriptorStart < 0) {
                return isBuiltInStaticEntry(entry);
            }
            return argumentsMatchDescriptor(call, allowedMember.substring(descriptorStart));
        }

        private boolean isBuiltInStaticEntry(String entry) {
            return BUILT_IN_PURE_STATIC_METHODS.contains(entry);
        }

        private boolean isValidConfiguredStaticAllowlistEntry(String entry) {
            if (entry == null || entry.isEmpty()) {
                return false;
            }
            int separator = entry.indexOf('#');
            if (separator <= 0 || separator == entry.length() - 1) {
                return false;
            }
            String owner = entry.substring(0, separator);
            String member = entry.substring(separator + 1);
            if (!isDottedTypeName(owner)) {
                return false;
            }
            if ("*".equals(member)) {
                return false;
            }
            int descriptorStart = member.indexOf('(');
            int descriptorEnd = member.lastIndexOf(')');
            if (descriptorStart <= 0 || descriptorEnd <= descriptorStart || descriptorEnd == member.length() - 1) {
                return false;
            }
            String methodName = member.substring(0, descriptorStart);
            String descriptor = member.substring(descriptorStart);
            JvmMethodDescriptor signature = JvmMethodDescriptor.parse(descriptor);
            return SourceVersion.isIdentifier(methodName)
                    && signature != null && signature.isValid();
        }

        private boolean argumentsMatchDescriptor(MethodCallExpr call, JvmMethodDescriptor signature) {
            return descriptorMatchScore(call, signature) >= 0;
        }

        private boolean argumentsMatchDescriptor(MethodCallExpr call, String descriptor) {
            return argumentsMatchDescriptor(call, JvmMethodDescriptor.parse(descriptor));
        }

        private int descriptorMatchScore(MethodCallExpr call, String descriptor) {
            return descriptorMatchScore(call, JvmMethodDescriptor.parse(descriptor));
        }

        private int descriptorMatchScore(MethodCallExpr call, JvmMethodDescriptor signature) {
            if (signature == null) {
                // A zero-argument method cannot have a same-name overload selected by
                // argument types. Parameterized callables must carry a full descriptor.
                return call.getArguments().isEmpty() ? 0 : -1;
            }
            if (!signature.isValid()) {
                return -1;
            }
            List<String> parameterDescriptors = signature.parameterDescriptors();
            if (parameterDescriptors.size() != call.getArguments().size()) {
                return -1;
            }
            int score = 0;
            for (int i = 0; i < parameterDescriptors.size(); i++) {
                int argumentScore = argumentCompatibilityScore(
                        resolveExpressionType(call.getArgument(i)), parameterDescriptors.get(i));
                if (argumentScore < 0) {
                    return -1;
                }
                score += argumentScore;
            }
            return score;
        }

        private int argumentCompatibilityScore(ExprType actual, String expectedDescriptor) {
            if (actual == null || !actual.isKnown()) {
                return -1;
            }
            if (actual.isNull()) {
                return expectedDescriptor.startsWith("L") || expectedDescriptor.startsWith("[") ? 5 : -1;
            }
            String expectedType = descriptorTypeName(expectedDescriptor);
            if (expectedType == null) {
                return -1;
            }
            String expectedCanonical = canonicalType(expectedType);
            if ("java.lang.Object".equals(expectedCanonical) && !actual.isNull()) {
                return 10;
            }
            if (actual.isNumericLike()) {
                return numericInvocationConversionScore(actual.typeName, expectedType);
            }
            if (actual.isBooleanLike()) {
                return ExprType.fromTypeName(expectedType).isBooleanLike() ? 0 : -1;
            }
            if (actual.isCharLike()) {
                return ExprType.fromTypeName(expectedType).isCharLike() ? 0 : -1;
            }
            if (actual.isArray()) {
                return expectedDescriptor.startsWith("[")
                        && expectedType.equals(canonicalType(actual.typeName)) ? 0 : -1;
            }
            String actualType = canonicalType(actual.typeName);
            if (actualType.equals(expectedCanonical) || "java.lang.Object".equals(expectedCanonical)) {
                return 0;
            }
            try {
                ClassLoader loader = org.evosuite.TestGenerationContext.getInstance().getClassLoaderForSUT();
                Class<?> expectedClass = Class.forName(expectedCanonical, false, loader);
                Class<?> actualClass = Class.forName(actualType, false, loader);
                return expectedClass.isAssignableFrom(actualClass) ? 5 : -1;
            } catch (Throwable ignored) {
                return -1;
            }
        }

        private int numericInvocationConversionScore(String actualType, String expectedType) {
            String actualPrimitive = unboxedNumericType(actualType);
            String expectedPrimitive = unboxedNumericType(expectedType);
            if (actualPrimitive == null || expectedPrimitive == null) {
                return -1;
            }
            if (actualPrimitive.equals(expectedPrimitive)) {
                return 0;
            }
            boolean actualIsPrimitive = isNumericPrimitiveOrChar(actualType);
            boolean expectedIsPrimitive = isNumericPrimitiveOrChar(expectedType);
            if (actualIsPrimitive && !expectedIsPrimitive) {
                // Method invocation does not permit widening followed by boxing
                // (for example, int cannot be passed to Long).
                return -1;
            }
            if (!actualIsPrimitive && !expectedIsPrimitive) {
                // Distinct wrapper types are not assignment-compatible.
                return -1;
            }
            if ("char".equals(actualPrimitive)) {
                int expectedRank = numericRank(expectedPrimitive);
                return expectedRank >= numericRank("int") ? expectedRank - numericRank("int") + 1 : -1;
            }
            int actualRank = numericRank(actualPrimitive);
            int expectedRank = numericRank(expectedPrimitive);
            return actualRank >= 0 && expectedRank >= actualRank ? expectedRank - actualRank + 1 : -1;
        }

        private String unboxedNumericType(String typeName) {
            String canonical = canonicalType(typeName);
            if ("java.lang.Byte".equals(canonical)) return "byte";
            if ("java.lang.Short".equals(canonical)) return "short";
            if ("java.lang.Character".equals(canonical)) return "char";
            if ("java.lang.Integer".equals(canonical)) return "int";
            if ("java.lang.Long".equals(canonical)) return "long";
            if ("java.lang.Float".equals(canonical)) return "float";
            if ("java.lang.Double".equals(canonical)) return "double";
            return "byte".equals(canonical) || "short".equals(canonical) || "char".equals(canonical)
                    || "int".equals(canonical) || "long".equals(canonical) || "float".equals(canonical)
                    || "double".equals(canonical) ? canonical : null;
        }

        private boolean isNumericPrimitiveOrChar(String typeName) {
            String canonical = canonicalType(typeName);
            return "byte".equals(canonical) || "short".equals(canonical) || "char".equals(canonical)
                    || "int".equals(canonical) || "long".equals(canonical) || "float".equals(canonical)
                    || "double".equals(canonical);
        }

        private int numericRank(String primitive) {
            if ("byte".equals(primitive)) return 0;
            if ("short".equals(primitive)) return 1;
            if ("int".equals(primitive)) return 2;
            if ("long".equals(primitive)) return 3;
            if ("float".equals(primitive)) return 4;
            if ("double".equals(primitive)) return 5;
            return -1;
        }

        private String descriptorTypeName(String descriptor) {
            if (descriptor == null || descriptor.isEmpty()) {
                return null;
            }
            int arrays = 0;
            while (arrays < descriptor.length() && descriptor.charAt(arrays) == '[') {
                arrays++;
            }
            if (arrays > 0) {
                String component = descriptorTypeName(descriptor.substring(arrays));
                if (component == null) {
                    return null;
                }
                StringBuilder result = new StringBuilder(component);
                for (int i = 0; i < arrays; i++) {
                    result.append("[]");
                }
                return result.toString();
            }
            switch (descriptor.charAt(0)) {
                case 'Z': return "boolean";
                case 'B': return "byte";
                case 'C': return "char";
                case 'S': return "short";
                case 'I': return "int";
                case 'J': return "long";
                case 'F': return "float";
                case 'D': return "double";
                case 'L':
                    return descriptor.endsWith(";")
                            ? descriptor.substring(1, descriptor.length() - 1).replace('/', '.') : null;
                default: return null;
            }
        }

        private boolean isDottedTypeName(String owner) {
            String[] parts = owner.split("\\.");
            if (parts.length == 0) {
                return false;
            }
            for (String part : parts) {
                if (!SourceVersion.isIdentifier(part)) {
                    return false;
                }
            }
            return true;
        }

        private boolean isAllowedImmutableConstructorType(String typeName) {
            if (!options().assertionPolicy().allowImmutableConstructors()) {
                return false;
            }
            if (BUILT_IN_IMMUTABLE_TYPES.contains(typeName)) {
                return true;
            }
            for (String configuredType : options().assertionPolicy().immutableTypes()) {
                if (configuredType.equals(typeName) || simpleName(configuredType).equals(typeName)) {
                    return true;
                }
            }
            return false;
        }

        private Set<String> splitConfiguredTypes(String configuredTypes) {
            if (configuredTypes == null || configuredTypes.trim().isEmpty()) {
                return Collections.emptySet();
            }
            Set<String> result = new LinkedHashSet<>();
            for (String rawType : configuredTypes.split(",")) {
                String type = rawType.trim();
                if (!type.isEmpty()) {
                    result.add(type);
                }
            }
            return result;
        }

        private String simpleName(String typeName) {
            int lastDot = typeName.lastIndexOf('.');
            return lastDot < 0 ? typeName : typeName.substring(lastDot + 1);
        }

        private boolean exceedsChainDepthLimit(Expression expression) {
            return LlmPostProcessingExpressionUtils.exceedsMemberChainDepth(expression,
                    options().assertionPolicy().maxChainDepth());
        }

        private boolean referencesUnknownVariableId(Expression expression) {
            if (!context.knowsVariableIds()) {
                return false;
            }
            for (NameExpr name : expression.findAll(NameExpr.class)) {
                String identifier = name.getNameAsString();
                if (LlmPostProcessingExpressionUtils.looksLikeVariableId(identifier)
                        && !context.hasVariableId(identifier)) {
                    return true;
                }
            }
            return false;
        }

        private boolean exceedsLiteralCharLimit(Expression expression) {
            int limit = options().assertionPolicy().maxLiteralChars();
            if (limit <= 0) {
                return false;
            }
            int chars = 0;
            for (StringLiteralExpr literal : expression.findAll(StringLiteralExpr.class)) {
                chars += literal.getValue().length();
                if (chars > limit) {
                    return true;
                }
            }
            for (CharLiteralExpr literal : expression.findAll(CharLiteralExpr.class)) {
                chars += literal.getValue().length();
                if (chars > limit) {
                    return true;
                }
            }
            return false;
        }

        private boolean exceedsConstructedArrayElementLimit(Expression expression) {
            int limit = options().assertionPolicy().maxConstructedArrayElements();
            if (limit <= 0) {
                return false;
            }
            for (ArrayInitializerExpr initializer : expression.findAll(ArrayInitializerExpr.class)) {
                if (initializer.getValues().size() > limit) {
                    return true;
                }
            }
            return false;
        }

        private boolean containsDisallowedArrayConstruction(Expression expression) {
            for (ArrayCreationExpr arrayCreation : expression.findAll(ArrayCreationExpr.class)) {
                if (arrayCreation.getLevels().size() != 1 || !arrayCreation.getInitializer().isPresent()) {
                    return true;
                }
                ArrayInitializerExpr initializer = arrayCreation.getInitializer().get();
                for (Expression value : initializer.getValues()) {
                    if (value instanceof ArrayInitializerExpr || !isLiteralOrNull(value)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean isLiteralOrNull(Expression expression) {
            return expression.isLiteralExpr() || expression.isNullLiteralExpr();
        }

        private static final String INVALID_EXPRESSION = new String("<invalid>");

        private boolean isForbidden(JsonNode entry, String fieldName) {
            JsonNode node = entry.get(fieldName);
            return node != null && !node.isNull();
        }

        private void diagnostic(DiagnosticCode code, String path, String message) {
            diagnostics.add(new Diagnostic(code, path, message, null,
                    currentAssertionIndex, currentAssertionId));
        }

        private void reportUnknownFields(JsonNode object, String path, Set<String> allowed) {
            if (object == null || !object.isObject()) {
                return;
            }
            Iterator<String> fieldNames = object.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                if (!allowed.contains(fieldName)) {
                    diagnostic(DiagnosticCode.INVALID_FIELD, path.isEmpty() ? fieldName : path + "." + fieldName,
                            "Unknown field: " + fieldName);
                }
            }
        }

        private static String text(JsonNode node) {
            if (node == null || node.isNull() || !node.isTextual()) {
                return null;
            }
            return node.asText();
        }

        private static String sanitizeComment(String text) {
            if (text == null) {
                return null;
            }
            if (text.contains("*/") || text.contains("/*")) {
                return null;
            }
            String sanitized = text.replace('\r', ' ').replace('\n', ' ').trim();
            while (true) {
                String next = stripCommentPrefix(sanitized).trim();
                if (next.equals(sanitized)) {
                    break;
                }
                sanitized = next;
            }
            for (int i = 0; i < sanitized.length(); i++) {
                char ch = sanitized.charAt(i);
                if (Character.isISOControl(ch)) {
                    return null;
                }
            }
            if (sanitized.startsWith("@")) {
                return null;
            }
            return sanitized;
        }

        private static String stripCommentPrefix(String text) {
            if (text.startsWith("//")) {
                return text.substring(2);
            }
            if (text.startsWith("/*")) {
                return text.substring(2);
            }
            if (text.startsWith("*")) {
                return text.substring(1);
            }
            return text;
        }

    }
}
