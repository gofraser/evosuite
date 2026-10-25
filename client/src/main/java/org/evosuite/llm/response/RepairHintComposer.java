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
package org.evosuite.llm.response;

import org.evosuite.Properties;
import org.evosuite.testparser.ParseDiagnostic;
import org.evosuite.testparser.ParseResult;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.evosuite.llm.response.ExecutionFailureFormatter.EXPLICIT_REPAIR_ACTION_MARKER;
import static org.evosuite.llm.response.MockRuntimeExceptions.MOCK_RUNTIME_PACKAGE_PREFIX;
import static org.evosuite.llm.response.MockRuntimeExceptions.errorMentionsMockRuntimeException;
import static org.evosuite.llm.response.MockRuntimeExceptions.isRaisedByEvoSuiteRuntime;
import static org.evosuite.llm.response.MockRuntimeExceptions.reportedExceptionName;
import static org.evosuite.llm.response.ParsedTestExecutionCheck.matchesExpectedExceptionName;
import static org.evosuite.llm.response.RepairText.MISSING_METHOD_DIAGNOSTIC_PATTERN;
import static org.evosuite.llm.response.RepairText.NEW_CALL_PATTERN;
import static org.evosuite.llm.response.RepairText.NULL_DECLARATION_PATTERN;
import static org.evosuite.llm.response.RepairText.extractMissingMethodName;
import static org.evosuite.llm.response.RepairText.extractTypedFallbackDeclaredType;
import static org.evosuite.llm.response.RepairText.getSutPackage;
import static org.evosuite.llm.response.RepairText.isUnresolvedTypeDiagnostic;
import static org.evosuite.llm.response.RepairText.joinTop;
import static org.evosuite.llm.response.RepairText.safeMethodName;
import static org.evosuite.llm.response.RepairText.simpleName;
import static org.evosuite.llm.response.RepairText.stripTypeDecorators;

/**
 * Writes the failure-specific hint section of a repair turn: the hint blocks
 * chosen from the error text, the execution failures and the previous
 * response, the dependency facts, and the
 * class-cast hint. It also supplies the shared-failure line of the
 * batch attrition summary and the hints appended to parse diagnostics.
 *
 * <p>Hint blocks are appended in a fixed order; the order is part of the
 * prompt text.
 */
final class RepairHintComposer {

    private static final Pattern NPE_NULL_VARIABLE_PATTERN = Pattern.compile(
            "because\\s+\"([^\"]+)\"\\s+is null");
    private static final Pattern SYNTHETIC_LOCAL_VARIABLE_PATTERN = Pattern.compile(
            "<local\\d+>");
    private static final Pattern INIT_FAILURE_CLASS_PATTERN = Pattern.compile(
            "Could not initialize class\\s+([A-Za-z0-9_.$]+)");
    private static final Pattern NEW_DECLARATION_PATTERN = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_$.<>]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*new"
                    + "\\s+([A-Za-z_][A-Za-z0-9_$.]*)\\s*\\(");
    private static final Pattern PARSED_TEST_CODE_EXCERPT_PATTERN = Pattern.compile(
            "Parsed test code excerpt:\\n```java\\n(.*?)\\n```", Pattern.DOTALL);
    private static final Pattern ANONYMOUS_TYPE_CREATION_PATTERN = Pattern.compile(
            "\\bnew\\s+([A-Za-z_][A-Za-z0-9_$.]*)\\s*\\([^)]*\\)\\s*\\{");
    private static final Pattern MISSING_SYMBOL_CLASS_PATTERN = Pattern.compile(
            "symbol:\\s+class\\s+([A-Za-z_][A-Za-z0-9_$]*)", Pattern.MULTILINE);
    private static final Pattern MISSING_PACKAGE_PATTERN = Pattern.compile(
            "package\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s+does\\s+not\\s+exist", Pattern.MULTILINE);
    private static final Pattern MISSING_SYMBOL_METHOD_WITH_LOCATION_PATTERN = Pattern.compile(
            "symbol:\\s+method\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s*\\([^)]*\\)\\s*"
                    + "location:\\s+variable\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s+of\\s+type\\s+([A-Za-z_][A-Za-z0-9_$.]*)",
            Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern MISSING_SYMBOL_VARIABLE_WITH_LOCATION_PATTERN = Pattern.compile(
            "symbol:\\s+variable\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s*"
                    + "location:\\s+variable\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s+of\\s+type\\s+([A-Za-z_][A-Za-z0-9_$.]*)",
            Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern MALFORMED_IMPORT_LINE_PATTERN = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?[^;]*([/\\\\]|\\s+as\\s+)[^;]*;\\s*$");

    private static final Set<String> INITIALIZATION_FAILURE_MARKERS = new HashSet<>(Arrays.asList(
            "ExceptionInInitializerError",
            "NoClassDefFoundError",
            "Could not initialize class",
            "ClassNotFoundException",
            "UnsatisfiedLinkError",
            "LinkageError",
            "java.awt.AWTError",
            "HeadlessException",
            "Local GraphicsEnvironment must not be null"
    ));
    private static final Set<String> INSTANTIATION_FAILURE_MARKERS = new HashSet<>(Arrays.asList(
            "InstantiationException",
            "is abstract; cannot be instantiated",
            "cannot instantiate"
    ));
    private static final Set<String> MOCKITO_MISUSE_MARKERS = new HashSet<>(Arrays.asList(
            "NotAMockException",
            "UnfinishedStubbingException",
            "MissingMethodInvocationException",
            "org.mockito.exceptions.misusing"
    ));
    private static final Pattern FALSE_POSITIVE_MOCK_CALL_PATTERN = Pattern.compile(
            "Mock call to\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s+which was not presented when the test was generated");
    private static final Pattern FALSE_POSITIVE_MOCK_SIGNATURE_PATTERN = Pattern.compile(
            "(?:\\[Dependency Stack\\]\\s+)?([A-Za-z_$][A-Za-z0-9_.$]*)\\.([A-Za-z_$][A-Za-z0-9_$]*)\\(");
    private static final Set<String> INDEXED_FIXTURE_FAILURE_MARKERS = new HashSet<>(Arrays.asList(
            "ArrayIndexOutOfBoundsException",
            "IndexOutOfBoundsException",
            "NegativeArraySizeException"
    ));

    /** Helpful NullPointerException message: group 1 is the member, group 2 the null receiver. */
    private static final java.util.regex.Pattern NPE_CANNOT_INVOKE_PATTERN = java.util.regex.Pattern.compile(
            "Cannot invoke\\s+\"([^\"]+)\"\\s+because\\s+\"([^\"]+)\"\\s+is null");
    private static final java.util.regex.Pattern CLASS_CAST_PATTERN = java.util.regex.Pattern.compile(
            "class\\s+([^\\s]+)\\s+cannot be cast to class\\s+([^\\s]+)");

    private final Supplier<String> sutContextSummary;
    private final ExecutionFailureFormatter failureFormatter;
    private final DependencyCodeContextResolver dependencyCodeResolver = new DependencyCodeContextResolver();

    RepairHintComposer(Supplier<String> sutContextSummary, ExecutionFailureFormatter failureFormatter) {
        this.sutContextSummary = sutContextSummary;
        this.failureFormatter = failureFormatter;
    }

    /**
     * Appends every failure-specific hint block of one repair turn, in prompt order.
     */
    void appendFailureHints(RepairConversation state,
                            String error,
                            String previousResponse,
                            Map<ParseResult, ExecutionFailureContext> executionFailures,
                            StringBuilder repairMessage) {
        FailureScopes scopes = classifyExecutionFailures(executionFailures);
        appendDependencyMissingRepairInstructions(error, repairMessage);
        appendInitializationFailureRepairInstructions(error, repairMessage);
        appendHeadlessEscalationRepairInstructions(state, error, repairMessage);
        appendInstantiationFailureRepairInstructions(error, repairMessage);
        appendExpectationMismatchRepairInstructions(scopes, repairMessage);
        appendNpePreconditionRepairInstructions(error, scopes, repairMessage);
        appendMockingRepairInstructions(error, repairMessage);
        appendStreamPreconditionRepairInstructions(error, repairMessage);
        appendArgumentPreconditionRepairInstructions(error, repairMessage);
        appendIndexedFixtureShapeRepairInstructions(error, repairMessage);
        appendReflectiveInvocationRepairInstructions(error, repairMessage);
        appendReflectiveAssertThrowsFallbackRepairInstructions(error, repairMessage);
        appendMissingMethodOnVariableRepairInstructions(error, repairMessage);
        appendMemberAccessRepairInstructions(state, error, repairMessage);
        appendFalsePositiveMockRepairInstructions(error, repairMessage);
        appendNoTestMethodsRepairInstructions(error, previousResponse, repairMessage);
        appendSpyUnsupportedRepairInstructions(error, repairMessage);
        appendCollaboratorFallbackRepairInstructions(error, repairMessage);
        appendAnonymousImplementationRepairInstructions(error, previousResponse, repairMessage);
        appendContextSpecificRepairFacts(error, previousResponse, repairMessage);
        appendDependencyCodeContext(state, executionFailures, repairMessage);
        appendClassCastRepairHint(error, executionFailures, repairMessage);
    }

    /** Adds the instruction to keep executable tests, unless an earlier hint block of the message has it. */
    private static void appendKeepExecutableTests(StringBuilder repairMessage) {
        String bullet = "\n- Keep all already executable tests unchanged.";
        if (repairMessage.indexOf(bullet) < 0) {
            repairMessage.append(bullet);
        }
    }

    /**
     * Guidance for a class that does not resolve at compile time. A missing class at run time is
     * covered by the initialization/dependency failure hint, which repeats this guidance.
     */
    private void appendDependencyMissingRepairInstructions(String error, StringBuilder repairMessage) {
        if (!TestRepairLoop.isDependencyMissingError(error) || isInitializationFailureError(error)) {
            return;
        }
        repairMessage.append("\n\nDependency-missing repair instructions:")
                .append("\n- Do NOT reference or instantiate missing external/framework classes.")
                .append("\n- Avoid code paths that trigger static initialization of missing dependencies.")
                .append("\n- Replace such tests with alternatives that only use available SUT/JDK types.")
                .append("\n- Focus on pure constructors/methods that do not require "
                        + "GUI/network/plugin containers.");
    }

    String buildSharedExecutionFailureHint(Map<ParseResult, ExecutionFailureContext> executionErrorByTest) {
        if (executionErrorByTest == null || executionErrorByTest.size() < 2) {
            return null;
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> hints = new LinkedHashMap<>();
        for (ExecutionFailureContext failureContext : executionErrorByTest.values()) {
            if (failureContext == null) {
                continue;
            }
            String executionError = failureContext.errorMessage;
            if (executionError == null || executionError.trim().isEmpty()) {
                continue;
            }

            String anonymousConstructedType = extractAnonymousConstructedType(executionError);
            if (anonymousConstructedType != null
                    && !anonymousConstructedType.isEmpty()
                    && isMalformedAnonymousSnippetSyntaxError(executionError)) {
                String key = "anon:" + anonymousConstructedType;
                counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
                hints.putIfAbsent(key, buildSharedAnonymousSnippetFailureHint(anonymousConstructedType));
                continue;
            }

            NpeDereferenceInfo dereferenceInfo = extractNpeDereferenceInfo(executionError);
            if (dereferenceInfo != null) {
                String key = "npe:" + dereferenceInfo.memberSignature + "::" + dereferenceInfo.nullVariable;
                counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
                hints.putIfAbsent(key, buildSharedNpeExecutionFailureHint(dereferenceInfo));
                continue;
            }

            String initializationFailureClass = extractInitializationFailureClassName(executionError);
            if (initializationFailureClass != null && !initializationFailureClass.isEmpty()) {
                String key = "init:" + initializationFailureClass;
                counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
                hints.putIfAbsent(key, "Multiple dropped tests share the same initialization failure in '"
                        + initializationFailureClass
                        + "'. Repair the shared environment/setup once or avoid that path instead of deleting those "
                                + "tests individually.");
                continue;
            }

            if (isIndexedFixtureShapeError(executionError)) {
                String key = "shape:indexed-fixture";
                counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
                hints.putIfAbsent(key, buildSharedIndexedFixtureFailureHint());
                continue;
            }

            if (isFalsePositiveMockError(executionError)) {
                String mockMethodName = pickFalsePositiveMockName(executionError);
                String key = mockMethodName == null || mockMethodName.isEmpty()
                        ? "mockfp:generic"
                        : "mockfp:" + mockMethodName;
                counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
                hints.putIfAbsent(key, buildSharedFalsePositiveMockFailureHint(mockMethodName));
            }
        }

        String bestKey = null;
        int bestCount = 1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestKey = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        if (bestKey == null) {
            return null;
        }
        return hints.get(bestKey) + " (" + bestCount + " tests)";
    }

    private String buildSharedAnonymousSnippetFailureHint(String constructedType) {
        String simpleType = simpleName(constructedType);
        return "Multiple dropped tests share the same anonymous-class snippet failure around '"
                + simpleType
                + "'. This is not a parser/import issue. Do not keep writing `new "
                + simpleType
                + "() { ... }`; replace it with Mockito.mock("
                + constructedType
                + ".class, new ViolatedAssumptionAnswer()) or a real concrete collaborator setup.";
    }

    private String buildSharedFalsePositiveMockFailureHint(String mockMethodName) {
        if (mockMethodName == null || mockMethodName.isEmpty()) {
            return "Multiple dropped tests fail because a ViolatedAssumptionAnswer mock path is missing one or more "
                    + "collaborator stubs. "
                    + "Repair the shared mock setup once by adding the minimal stub(s) the SUT actually calls instead "
                            + "of deleting those tests one-by-one.";
        }
        return "Multiple dropped tests fail because the SUT reaches an unstubbed mock call to `"
                + mockMethodName
                + "(...)` on a ViolatedAssumptionAnswer collaborator. Repair the shared mock setup once by stubbing "
                        + "that collaborator method (and any returned nested collaborator) instead of deleting those "
                        + "tests one-by-one.";
    }

    private String buildSharedNpeExecutionFailureHint(NpeDereferenceInfo dereferenceInfo) {
        StringBuilder hint = new StringBuilder();
        hint.append("Multiple dropped tests share the same execution root cause: null receiver '")
                .append(dereferenceInfo.nullVariable)
                .append("' inside SUT call '")
                .append(dereferenceInfo.memberSignature)
                .append("'.");
        if (dereferenceInfo.receiverType != null && !dereferenceInfo.receiverType.isEmpty()) {
            hint.append(" Repair the shared object/collaborator setup that should initialize this ")
                    .append(simpleName(dereferenceInfo.receiverType))
                    .append(" instance before that call, instead of deleting those tests one-by-one.");
        } else {
            hint.append(" Repair the shared object/collaborator setup before that call, instead of deleting those "
                    + "tests one-by-one.");
        }
        return hint.toString();
    }

    private String buildSharedIndexedFixtureFailureHint() {
        return "Multiple dropped tests share the same bounds/shape failure while constructing or populating indexed "
                + "fixtures. "
                + "Repair the shared setup pattern once: make constructor size/count arguments consistent with every "
                        + "later indexed get/set call, "
                + "do not assume every helper/container stores all slots exactly 1:1 with its constructor arguments, "
                        + "and regenerate those fixtures instead of deleting the tests one-by-one.";
    }

    private void appendNpePreconditionRepairInstructions(String error,
                                                         FailureScopes scopes,
                                                         StringBuilder repairMessage) {
        if (error == null || error.isEmpty()) {
            return;
        }
        String lower = error.toLowerCase();
        if (!lower.contains("nullpointerexception") && !lower.contains("is null")) {
            return;
        }

        // Scope hint to the specific tests whose actual exception is an NPE.
        // Without this, an NPE in one test would emit "fix the receiver" hints
        // for sibling tests that failed for an unrelated reason (e.g. shaded
        // mock-runtime exceptions), pushing the LLM toward changes that cannot
        // resolve the non-NPE failures.
        if (scopes.npeFailingTests.isEmpty() && !scopes.executionFailuresAvailable) {
            // Best-effort: when no per-failure context is available, fall through
            // and emit the hint based on the aggregate error string only.
        } else if (scopes.npeFailingTests.isEmpty()) {
            return;
        }

        String nullVariable = extractNullVariableName(error);
        NpeDereferenceInfo dereferenceInfo = extractNpeDereferenceInfo(error);
        boolean syntheticLocal = isSyntheticLocalVariable(nullVariable);
        boolean directTestVariable = appearsInParsedTestCodeExcerpt(error, nullVariable);
        repairMessage.append("\n\nNPE precondition hint:");
        appendFailureScopeQualifier(repairMessage, scopes, scopes.npeFailingTests,
                "These NPE-precondition hints apply ONLY to the test(s) above; do not "
                        + "apply them to sibling failing tests whose actual exception is "
                        + "different (those are listed separately and require different fixes).");
        repairMessage.append("\n- The failing test violates required non-null preconditions.");
        appendKeepExecutableTests(repairMessage);
        if (nullVariable != null && !nullVariable.isEmpty() && !syntheticLocal && directTestVariable) {
            repairMessage.append("\n- For failing tests only, ensure the object named '")
                    .append(nullVariable)
                    .append("' is initialized before invoking the SUT.");
        } else if (nullVariable != null && !nullVariable.isEmpty() && !syntheticLocal) {
            repairMessage.append("\n- The JVM-reported null receiver name '")
                    .append(nullVariable)
                    .append("' comes from inside the failing SUT call path and is not necessarily a direct test "
                            + "variable; ")
                    .append("it is likely a parameter or local variable inside the SUT.");
            repairMessage.append("\n- Repair the upstream collaborator setup so the SUT receives a non-null value "
                    + "there before dereferencing it.");
        } else {
            repairMessage.append("\n- For failing tests only, initialize required provider/session objects "
                    + "before invoking the SUT.");
        }
        if (dereferenceInfo != null) {
            repairMessage.append("\n- Detected null dereference: '")
                    .append(dereferenceInfo.nullVariable)
                    .append("' was used in call '")
                    .append(dereferenceInfo.memberSignature)
                    .append("'.");
            if (dereferenceInfo.receiverType != null && !dereferenceInfo.receiverType.isEmpty()) {
                if (directTestVariable) {
                    repairMessage.append("\n- Initialize '")
                            .append(dereferenceInfo.nullVariable)
                            .append("' with a non-null value compatible with ")
                            .append(dereferenceInfo.receiverType)
                            .append(" before invoking the SUT.");
                } else {
                    repairMessage.append("\n- Here, '")
                            .append(dereferenceInfo.nullVariable)
                            .append("' is the null receiver inside the SUT call '")
                            .append(dereferenceInfo.memberSignature)
                            .append("', likely as a parameter or local variable there, not necessarily a same-named "
                                    + "test variable.");
                    repairMessage.append("\n- Stub or initialize the upstream collaborator/return value so that "
                            + "receiver is a non-null ")
                            .append(dereferenceInfo.receiverType)
                            .append(" before the SUT reaches that call.");
                }
                if (isDomElementType(dereferenceInfo.receiverType)) {
                    repairMessage.append("\n- For DOM collaborators, build a minimal in-memory element "
                            + "(DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()"
                            + ".createElement(\"element\")) "
                            + "instead of null.");
                }
            } else {
                repairMessage.append("\n- Ensure the null receiver is initialized before dereferencing it.");
            }
            repairMessage.append("\n- If this collaborator is required by the constructor, pass a concrete non-null "
                    + "argument during object construction (do not rely on later null-state assertions).");
        }
        if (syntheticLocal) {
            repairMessage.append("\n- The null receiver name is compiler-synthetic (for example '<local1>'); "
                    + "explicitly initialize each call receiver right before invoking the failing method.");
        }
        repairMessage.append("\n- Prefer minimal straight-line setup with concrete objects "
                + "over anonymous classes.");
        repairMessage.append("\n- Do not call the target method with null for mandatory "
                + "provider/session arguments.");
        repairMessage.append("\n- If the test is intentionally checking null-input behavior, "
                + "wrap the call in assertThrows(NullPointerException.class, ...) instead of "
                + "making a plain invocation with a known-null collaborator.");
        repairMessage.append("\n- If a required collaborator type is an interface/abstract type, "
                + "do NOT use new on that type; use a listed concrete subtype or Mockito.mock(Type.class).");
        repairMessage.append("\n- Do not assume complex SUT methods succeed on minimally initialized objects: "
                + "either construct the required internal state first, or assertThrows for the observed failure path.");
        repairMessage.append("\n- If a branch requires null, assert that behavior only after "
                + "satisfying earlier mandatory preconditions.");
    }

    private boolean isSyntheticLocalVariable(String variableName) {
        if (variableName == null || variableName.isEmpty()) {
            return false;
        }
        return SYNTHETIC_LOCAL_VARIABLE_PATTERN.matcher(variableName).matches();
    }

    /**
     * Per-failure classification of an execution-failure batch. Used to scope
     * hint blocks (e.g. NPE-precondition hints) to the tests they actually
     * apply to, instead of letting one failure's signal pollute the prompt for
     * sibling tests that failed for different reasons.
     */
    private static final class FailureScopes {
        final boolean executionFailuresAvailable;
        final List<String> npeFailingTests = new ArrayList<>();
        final List<String> shadedMockFailingTests = new ArrayList<>();
        final List<String> expectationMismatchFailingTests = new ArrayList<>();
        /** Maps a test method name to "expected=X, actual=Y". */
        final Map<String, String> expectationMismatchDetails = new LinkedHashMap<>();

        FailureScopes(boolean executionFailuresAvailable) {
            this.executionFailuresAvailable = executionFailuresAvailable;
        }
    }

    private FailureScopes classifyExecutionFailures(
            Map<ParseResult, ExecutionFailureContext> executionFailures) {
        boolean available = executionFailures != null && !executionFailures.isEmpty();
        FailureScopes scopes = new FailureScopes(available);
        if (!available) {
            return scopes;
        }
        for (Map.Entry<ParseResult, ExecutionFailureContext> entry : executionFailures.entrySet()) {
            ParseResult pr = entry.getKey();
            ExecutionFailureContext ctx = entry.getValue();
            if (pr == null || ctx == null) {
                continue;
            }
            String name = safeMethodName(pr);
            Throwable thrown = ctx.throwable;
            if (thrown instanceof NullPointerException
                    || (ctx.errorMessage != null
                            && (ctx.errorMessage.contains("NullPointerException")
                                    || ctx.errorMessage.contains("is null")))) {
                scopes.npeFailingTests.add(name);
            }
            if (isRaisedByEvoSuiteRuntime(thrown)
                    || errorMentionsMockRuntimeException(ctx.errorMessage)) {
                scopes.shadedMockFailingTests.add(name);
            }
            String expected = pr.getExpectedExceptionClass();
            if (expected != null && !expected.trim().isEmpty()) {
                String actualName = thrown != null ? reportedExceptionName(thrown) : null;
                if (actualName != null && !matchesExpectedExceptionName(thrown.getClass(), expected)) {
                    scopes.expectationMismatchFailingTests.add(name);
                    scopes.expectationMismatchDetails.put(name,
                            "expected=" + expected + ", actual=" + actualName);
                }
            }
        }
        return scopes;
    }

    /**
     * Appends a one-line scope qualifier that names the specific tests a hint
     * block applies to. Skipped when no per-failure context is available, so
     * hints emitted purely from the aggregate error text remain unchanged.
     */
    private void appendFailureScopeQualifier(StringBuilder repairMessage,
                                             FailureScopes scopes,
                                             List<String> applicableTests,
                                             String exclusivityNote) {
        if (scopes == null || !scopes.executionFailuresAvailable) {
            return;
        }
        if (applicableTests == null || applicableTests.isEmpty()) {
            return;
        }
        // Only add the scope qualifier when there is at least one sibling failure
        // that this hint block does NOT apply to; otherwise the hint already
        // implicitly applies to every failure in the batch.
        boolean hasNonApplicableSibling =
                scopes.npeFailingTests.size() + scopes.shadedMockFailingTests.size()
                        + scopes.expectationMismatchFailingTests.size()
                        > applicableTests.size()
                        || scopes.shadedMockFailingTests.stream()
                                .anyMatch(name -> !applicableTests.contains(name));
        if (!hasNonApplicableSibling) {
            return;
        }
        repairMessage.append("\n- Applies to test(s): ").append(applicableTests).append(".");
        if (exclusivityNote != null && !exclusivityNote.isEmpty()) {
            repairMessage.append("\n- ").append(exclusivityNote);
        }
    }

    /**
     * Surfaces a single, prioritized line near the top of the repair message
     * when the LLM's <code>assertThrows(Y.class, ...)</code> expectation does
     * not match the actually observed exception type. The bytecode signature
     * tells the LLM what the SUT <em>declares</em> it can throw; the runtime
     * tells us what actually happens. Trust the runtime.
     */
    private void appendExpectationMismatchRepairInstructions(FailureScopes scopes,
                                                             StringBuilder repairMessage) {
        if (!scopes.executionFailuresAvailable) {
            return;
        }
        if (scopes.expectationMismatchFailingTests.isEmpty()) {
            return;
        }
        repairMessage.append("\n\nExpected-vs-actual exception mismatch:");
        for (String name : scopes.expectationMismatchFailingTests) {
            String detail = scopes.expectationMismatchDetails.get(name);
            repairMessage.append("\n- ").append(name).append(": ")
                    .append(detail == null ? "(details unavailable)" : detail);
        }
        repairMessage.append("\n- The actual runtime exception is what executes; the expected class");
        repairMessage.append(" came from the method's declared `throws` clause but the SUT does not");
        repairMessage.append(" actually translate the runtime failure into that type in this environment.");
        repairMessage.append("\n- For each test above, EITHER change the assertThrows expectation to the");
        repairMessage.append(" actually observed class, OR replace the test with one that does not");
        repairMessage.append(" reach the failing call. Do NOT keep the same expected class hoping a");
        repairMessage.append(" different setup will produce it.");
        if (!scopes.shadedMockFailingTests.isEmpty()) {
            repairMessage.append("\n- Note: tests ").append(scopes.shadedMockFailingTests)
                    .append(" failed with an exception under ").append(MOCK_RUNTIME_PACKAGE_PREFIX).append("*;");
            repairMessage.append(" that is the EvoSuite test harness, not the SUT, and you cannot");
            repairMessage.append(" eliminate it by changing test inputs. Either accept it via");
            repairMessage.append(" assertThrows on that exact class or drop those tests.");
        }
    }

    private void appendMockingRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isMockingMisuseError(error)) {
            return;
        }
        repairMessage.append("\n\nMockito usage hint:");
        appendKeepExecutableTests(repairMessage);
        repairMessage.append("\n- For failing tests only, ensure objects passed to Mockito.when(...), "
                + "doThrow(...).when(...), and verify(...) are actual mocks/spies.");
        repairMessage.append("\n- Do NOT stub or verify real objects created with constructors.");
        repairMessage.append("\n- If a collaborator type is interface/abstract, use Mockito.mock(Type.class) "
                + "or a concrete subtype; never use new on the interface/abstract type.");
        repairMessage.append("\n- Do NOT use Mockito.spy(...) or doReturn(...).when(spy)...; regenerate failing tests "
                + "using real instances or Mockito.mock(...) collaborators.");
        repairMessage.append("\n- Complete each stubbing expression fully (avoid unfinished stubbing chains).");
    }

    private void appendSpyUnsupportedRepairInstructions(String error, StringBuilder repairMessage) {
        // The Mockito usage hint already bans spies and spy-based stubbing and names the replacements.
        if (!isSpyUnsupportedPattern(error) || isMockingMisuseError(error)) {
            return;
        }
        repairMessage.append("\n\nSpy-unsupported repair instructions:");
        repairMessage.append("\n- Do NOT use Mockito.spy(...) in EvoSuite-generated parsed tests.");
        repairMessage.append("\n- Do NOT use spy-based stubbing such as doReturn(...).when(spy)... or "
                + "when(spy.method(...)).thenReturn(...).");
        repairMessage.append("\n- Replace spy-based setup with one of: (1) direct calls on real SUT instances without "
                + "spy stubbing, or (2) Mockito.mock(...) collaborators that are stubbed minimally.");
    }

    private void appendFalsePositiveMockRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isFalsePositiveMockError(error)) {
            return;
        }

        String mockMethodName = pickFalsePositiveMockName(error);
        repairMessage.append("\n\nMock-stubbing hint:");
        repairMessage.append("\n- The rerun failed because a mocked collaborator used ViolatedAssumptionAnswer and the "
                + "SUT called an unstubbed method.");
        if (mockMethodName != null && !mockMethodName.isEmpty()) {
            repairMessage.append("\n- Unstubbed mock call: `").append(mockMethodName).append("(...)`.");
        } else {
            repairMessage.append("\n- Unstubbed mock call: the runtime reported a false-positive mock-policy failure, "
                    + "but the exact method name was not captured.");
        }
        repairMessage.append("\n- Add the minimal stub for the collaborator method the SUT actually calls, instead of "
                + "leaving that mock on a bare ViolatedAssumptionAnswer path.");
        repairMessage.append("\n- Keep the rest of the test unchanged unless it depends on the missing stub.");
        repairMessage.append("\n- Do not replace the collaborator with null; make the mock return the value or nested "
                + "collaborator the SUT needs.");
    }

    private void appendStreamPreconditionRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isStreamRelatedError(error)) {
            return;
        }
        repairMessage.append("\n\nStream precondition hint:");
        repairMessage.append("\n- The failing test violates stream preconditions or stream lifecycle constraints.");
        appendKeepExecutableTests(repairMessage);
        repairMessage.append("\n- For failing tests only, avoid null InputStream/OutputStream/Reader/Writer arguments "
                + "unless the method contract explicitly expects null.");
        repairMessage.append("\n- Prefer in-memory data sources over filesystem/network I/O in tests:");
        repairMessage.append("\n  ByteArrayInputStream for bytes, StringReader for text, "
                + "ByteArrayOutputStream/StringWriter for outputs.");
        repairMessage.append("\n- In EvoSuite sandbox, avoid direct file-writer constructors "
                + "(e.g., FileOutputStream/FileWriter/PrintWriter on real files); use in-memory outputs or "
                + "EvoSuite mock I/O types (MockFile*, MockPrint*) when file-like APIs are required.");
        repairMessage.append("\n- Build minimally valid non-empty test payloads before invoking parser/configuration "
                + "methods.");
        repairMessage.append("\n- If the expected behavior is argument validation, assert the concrete thrown "
                + "exception for null stream arguments.");
    }

    private void appendArgumentPreconditionRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isArgumentPreconditionError(error)) {
            return;
        }
        String lower = error == null ? "" : error.toLowerCase();
        boolean eventObjectNullSource = lower.contains("null source")
                && (lower.contains("eventobject") || lower.contains("actionevent"));
        repairMessage.append("\n\nArgument precondition hint:");
        repairMessage.append("\n- The failing test violates method preconditions for input arguments.");
        appendKeepExecutableTests(repairMessage);
        repairMessage.append("\n- For failing tests only, replace invalid empty/default inputs with minimally valid "
                + "non-empty values before invoking the SUT.");
        repairMessage.append("\n- Typical fixes: provide at least one required line/entry/token/element instead of "
                + "empty structures or empty strings.");
        repairMessage.append("\n- If testing validation behavior, use assertThrows(...) for the invalid-input case and "
                + "add a separate valid-input smoke test.");
        if (eventObjectNullSource) {
            repairMessage.append("\n- Detected `IllegalArgumentException: null source` from EventObject/ActionEvent "
                    + "construction.");
            repairMessage.append("\n- Never build `new ActionEvent(null, ...)`: the first constructor argument (event "
                    + "source) must be non-null.");
            repairMessage.append("\n- If receiver construction collapsed to a typed null placeholder "
                    + "(`__llm_fallback...`), do not keep emitting event-driven tests on that null receiver; repair "
                    + "upstream construction or rewrite/drop those tests.");
        }
    }

    private void appendIndexedFixtureShapeRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isIndexedFixtureShapeError(error)) {
            return;
        }
        repairMessage.append("\n\nIndexed fixture/bounds hint:");
        repairMessage.append("\n- The failing test uses inconsistent dimensions, counts, or indices while building an "
                + "indexed fixture or invoking an indexed API.");
        appendKeepExecutableTests(repairMessage);
        repairMessage.append("\n- For failing tests only, re-check each constructor size/count argument against every "
                + "later indexed read/write in that test.");
        repairMessage.append("\n- Do not assume constructor counts map 1:1 to all later accessible "
                + "rows/elements/slots; some helpers reserve header or metadata positions, or derive internal storage "
                + "differently.");
        repairMessage.append("\n- Rebuild the fixture so every set/get index stays within the actual bounds created by "
                + "the constructor and setup calls.");
        repairMessage.append("\n- If the behavior under test is rejection of invalid sizes or indices, stop the test "
                + "at the offending constructor/mutator with assertThrows(...) instead of continuing with normal-path "
                + "assertions.");
    }

    private void appendReflectiveInvocationRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isReflectiveInvocationWrapperError(error)) {
            return;
        }
        repairMessage.append("\n\nReflection wrapper hint:");
        appendKeepExecutableTests(repairMessage);
        repairMessage.append("\n- Prefer direct invocation of accessible SUT methods over reflection.");
        repairMessage.append("\n- If using Method.invoke(...), expect InvocationTargetException from assertThrows.");
        repairMessage.append("\n- Then assert the wrapped cause type/message (e.getCause()) matches the expected SUT "
                + "exception.");
        repairMessage.append("\n- Avoid Class.forName/getDeclaredMethod/setAccessible when the target method is public "
                + "or package-private.");
    }

    private void appendReflectiveAssertThrowsFallbackRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isReflectiveAssertThrowsFallbackError(error)) {
            return;
        }
        repairMessage.append("\n\nReflective assertThrows fallback hint:");
        repairMessage.append("\n- The shown InvocationTargetException/__llm_fallback line is a parser-generated "
                + "fallback, not valid intended test logic.");
        repairMessage.append("\n- Prefer direct invocation of accessible SUT methods over reflection.");
        repairMessage.append("\n- If reflection is unavoidable, capture InvocationTargetException with "
                + "Assertions.assertThrows(...), then cast/check invocationTargetException.getCause() as the expected "
                + "SUT exception type.");
        repairMessage.append("\n- Do not assign assertThrows(...) results to synthetic null/fallback placeholders.");
    }

    private void appendMissingMethodOnVariableRepairInstructions(String error, StringBuilder repairMessage) {
        if (error == null || error.isEmpty()) {
            return;
        }
        java.util.regex.Matcher missingMethodMatcher = MISSING_SYMBOL_METHOD_WITH_LOCATION_PATTERN.matcher(error);
        boolean emitted = false;
        while (missingMethodMatcher.find()) {
            String methodName = missingMethodMatcher.group(1);
            String receiverVar = missingMethodMatcher.group(2);
            String receiverType = missingMethodMatcher.group(3);
            if (!emitted) {
                repairMessage.append("\n\nMissing-method repair instructions:");
                emitted = true;
            }
            if (methodName != null && methodName.equals(receiverVar)) {
                repairMessage.append("\n- Invalid self-call typo detected: `")
                        .append(receiverVar).append(".").append(methodName).append("(...)`.")
                        .append(" Remove this call entirely or replace it with a real existing API call on `")
                        .append(receiverType).append("` from provided context.")
                        .append(" Do not invent methods from variable names.");
            } else {
                repairMessage.append("\n- Cannot resolve method `").append(methodName)
                        .append("(...)` on variable `").append(receiverVar)
                        .append("` of type `").append(receiverType)
                        .append("`. Replace with an existing method from context or remove this call/assertion.");
                repairMessage.append("\n- Do not call subtype-only methods on a supertype reference.")
                        .append(" If the runtime object is a subtype, add an `instanceof` guard and cast before "
                                + "calling subtype APIs")
                        .append(" (for example `Component` -> `JLabel` before `getText()`).");
                String closest = failureFormatter.findClosestAccessibleApiSuggestion(methodName);
                if (closest != null && !closest.isEmpty()) {
                    repairMessage.append("\n- Closest available API: ").append(closest);
                }
            }
        }

        java.util.regex.Matcher directMissingMethodMatcher = MISSING_METHOD_DIAGNOSTIC_PATTERN.matcher(error);
        while (directMissingMethodMatcher.find()) {
            if (!emitted) {
                repairMessage.append("\n\nMissing-method repair instructions:");
                emitted = true;
            }
            String methodName = directMissingMethodMatcher.group(1);
            String targetClass = directMissingMethodMatcher.group(2);
            repairMessage.append("\n- No method named `").append(methodName)
                    .append("` exists in `").append(targetClass)
                    .append("`. Replace the invented call with a real API from context, or remove the call entirely.");
            String closest = failureFormatter.findClosestAccessibleApiSuggestion(methodName);
            if (closest != null && !closest.isEmpty()) {
                repairMessage.append("\n- Closest available API: ").append(closest);
            }
        }
    }

    private void appendMemberAccessRepairInstructions(RepairConversation state, String error,
                                                      StringBuilder repairMessage) {
        if (error == null || error.isEmpty()) {
            return;
        }
        String lower = error.toLowerCase();
        if (!(lower.contains("has private access")
                || lower.contains("has protected access")
                || lower.contains("cannot be accessed from outside package"))) {
            return;
        }

        // Parse specific inaccessible members from diagnostics and track them
        List<AccessViolationDiagnosticParser.AccessViolation> violations =
                AccessViolationDiagnosticParser.parse(error);
        Set<String> currentKeys = new LinkedHashSet<>();
        for (AccessViolationDiagnosticParser.AccessViolation v : violations) {
            currentKeys.add(v.toTrackingKey());
        }
        Set<String> recurrentKeys = state.recordAccessViolations(currentKeys);

        repairMessage.append("\n\nMember-access repair hint:");
        repairMessage.append("\n- The failing test directly accesses private or protected members (which are not "
                + "allowed). Note: Package-private members ARE allowed as tests are in the same package.");
        appendKeepExecutableTests(repairMessage);

        // Emit specific inaccessible member names so the LLM knows exactly what to avoid
        if (!violations.isEmpty()) {
            repairMessage.append("\n- Specifically inaccessible members detected:");
            for (AccessViolationDiagnosticParser.AccessViolation v : violations) {
                if (recurrentKeys.contains(v.toTrackingKey())) {
                    // Escalation language for members that recurred across retries
                    repairMessage.append("\n  * CRITICAL (recurrent): You MUST NOT call `")
                            .append(v.getMemberName()).append("()` — it has ")
                            .append(v.getAccessLevel()).append(" access in `")
                            .append(v.getDeclaringClass()).append("`. ")
                            .append("Remove ALL calls to this member immediately.");
                } else {
                    repairMessage.append("\n  * Do NOT call `")
                            .append(v.getMemberName()).append("()` — it has ")
                            .append(v.getAccessLevel()).append(" access in `")
                            .append(v.getDeclaringClass()).append("`.");
                }
            }
        }

        repairMessage.append("\n- For failing tests only, remove direct field/member access assertions and replace "
                + "them with checks via accessible public or package-visible methods.");
        repairMessage.append("\n- Do not assert internal fields such as receiver.superclassField; assert externally "
                + "visible behavior instead.");
        repairMessage.append("\n- If no accessible API exposes the same state, drop that assertion rather than forcing "
                + "illegal member access.");
        repairMessage.append("\n- Also avoid non-public cross-package types in imports/declarations/constructors; use "
                + "only public accessible types/paths from context.");
    }

    static boolean isReflectiveAssertThrowsFallbackError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        String lower = error.toLowerCase();
        return lower.contains("snippet compilation")
                && lower.contains("__llm_fallback")
                && lower.contains("invocationtargetexception");
    }

    static boolean isPartialAnonymousImplementationError(String error, String previousResponse) {
        if ((error == null || error.isEmpty())
                && (previousResponse == null || previousResponse.isEmpty())) {
            return false;
        }
        String sourceText = error != null && !error.isEmpty() ? error : previousResponse;
        if (extractAnonymousConstructedTypeStatic(sourceText) == null) {
            return false;
        }
        String lower = error == null ? "" : error.toLowerCase();
        if (lower.contains("is not abstract and does not override abstract method")) {
            return true;
        }
        return lower.contains("snippet compilation")
                && (lower.contains("illegal start of type")
                || lower.contains("class, interface, enum, or record expected")
                || lower.contains("';' expected")
                || lower.contains("is abstract; cannot be instantiated")
                || lower.contains("has protected access")
                || (lower.contains("constructor") && lower.contains("cannot be applied to given types")));
    }

    private void appendCollaboratorFallbackRepairInstructions(String error, StringBuilder repairMessage) {
        String fallbackType = extractTypedFallbackDeclaredType(error);
        if (fallbackType == null || fallbackType.isEmpty()) {
            return;
        }
        String simpleType = simpleName(fallbackType);
        repairMessage.append("\n\nCollaborator fallback hint:");
        repairMessage.append("\n- The shown '")
                .append(fallbackType)
                .append(" __llm_fallback... = null;' line is a parser-generated placeholder, not a usable collaborator "
                        + "setup.");
        repairMessage.append("\n- Do not alias that synthetic fallback, call methods on it, stub through it, or keep "
                + "passing null instead of a real ")
                .append(simpleType)
                .append(" value.");
        repairMessage.append("\n- If ")
                .append(simpleType)
                .append(" is interface/abstract-like in context, prefer Mockito.mock(")
                .append(simpleType)
                .append(".class, new ViolatedAssumptionAnswer()) and stub only the minimal methods the SUT call "
                        + "actually uses.");
    }

    private void appendAnonymousImplementationRepairInstructions(String error,
                                                                 String previousResponse,
                                                                 StringBuilder repairMessage) {
        if (!isPartialAnonymousImplementationError(error, previousResponse)) {
            return;
        }
        String constructedType = extractAnonymousConstructedType(error);
        if (constructedType == null || constructedType.isEmpty()) {
            constructedType = extractAnonymousConstructedType(previousResponse);
        }
        if (constructedType == null || constructedType.isEmpty()) {
            return;
        }

        String simpleType = simpleName(constructedType);
        Class<?> resolvedType = failureFormatter.tryResolveRepairType(constructedType);
        repairMessage.append("\n\nAnonymous implementation repair hint:");
        repairMessage.append("\n- The failing test created an anonymous implementation/subclass of '")
                .append(constructedType)
                .append("', and that anonymous `new ")
                .append(simpleType)
                .append("() { ... }` fragment is non-compilable in EvoSuite snippet execution here.");
        repairMessage.append("\n- This is not a parser/import issue: the anonymous `new ")
                .append(simpleType)
                .append("() { ... }` fragment itself is not a valid repair for this type.");
        if (resolvedType != null) {
            if (resolvedType.isInterface()) {
                repairMessage.append("\n- '")
                        .append(simpleType)
                        .append("' is an interface, so the one-off anonymous body still has to implement every "
                                + "required method.");
            } else if (Modifier.isAbstract(resolvedType.getModifiers()) && hasOnlyNonPublicConstructors(resolvedType)) {
                repairMessage.append("\n- '")
                        .append(simpleType)
                        .append("' is abstract and its constructors are not directly public, so a one-method anonymous "
                                + "subclass is especially brittle here.");
            } else if (Modifier.isAbstract(resolvedType.getModifiers())) {
                repairMessage.append("\n- '")
                        .append(simpleType)
                        .append("' is abstract, so overriding one visible method is still not enough to make the "
                                + "anonymous subtype valid.");
            } else if (hasOnlyNonPublicConstructors(resolvedType)) {
                repairMessage.append("\n- '")
                        .append(simpleType)
                        .append("' does not expose a directly public constructor, so `new ")
                        .append(simpleType)
                        .append("() { ... }` is not a safe repair strategy.");
            } else {
                repairMessage.append("\n- Even though '")
                        .append(simpleType)
                        .append("' is concrete, keep avoiding `new ")
                        .append(simpleType)
                        .append("() { ... }` here; the anonymous override body is the failing pattern.");
            }
        }
        if (isMalformedAnonymousSnippetSyntaxError(error)) {
            repairMessage.append("\n- The reported javac syntax errors (for example `illegal start of type`, `';' "
                    + "expected`, or stray `return` lines) are downstream effects of the malformed anonymous class, "
                    + "not evidence that EvoSuite parsed the body incorrectly.");
        }
        repairMessage.append("\n- Do NOT write new ")
                .append(simpleType)
                .append("() { ... } just to override one method.");
        repairMessage.append("\n- Replace it with Mockito.mock(")
                .append(constructedType)
                .append(".class, new ViolatedAssumptionAnswer()) or a concrete existing subtype from context.");
        repairMessage.append("\n- Stub or verify only the minimal method actually relevant to the failing path instead "
                + "of hand-writing an anonymous class body.");
        repairMessage.append("\n- If the intended assertion is that a callback method must not be "
                + "reached, keep the collaborator as a mock and verify the method is never invoked instead of calling "
                + "fail(...) inside an anonymous override.");
    }

    private void appendNoTestMethodsRepairInstructions(String error,
                                                       String previousResponse,
                                                       StringBuilder repairMessage) {
        if (error == null || !error.contains("Parser produced no test methods")) {
            return;
        }
        repairMessage.append("\n\nNo-test-method parsing hint:");
        repairMessage.append("\n- Return a complete compilable Java test class that contains one or more concrete "
                + "methods annotated with @Test inside the class body.");
        
        List<String> malformedImports =
                extractMalformedImportLines(error + "\n" + (previousResponse == null ? "" : previousResponse));
        if (!malformedImports.isEmpty()) {
            boolean hasAlias = malformedImports.stream().anyMatch(l -> l.toLowerCase().contains(" as "));
            if (hasAlias) {
                repairMessage.append("\n- The previous response used invalid 'import ... as ...' alias syntax, which "
                        + "Java does not support.");
                repairMessage.append("\n- To resolve naming conflicts (e.g., between two classes named 'Query'), use "
                        + "the fully qualified name at the call site instead of aliasing the import.");
                repairMessage.append("\n- Example: `com.example.query.Query exampleQuery = new "
                        + "com.example.query.Query();`.");
            } else {
                repairMessage.append("\n- The previous response used invalid Java import syntax with file-path "
                        + "separators instead of package dots.");
                for (String importLine : malformedImports.subList(0, Math.min(2, malformedImports.size()))) {
                    repairMessage.append("\n- Invalid import from previous response: ").append(importLine);
                }
                repairMessage.append("\n- Java imports must use dotted package names, for example `import "
                        + "com.example.service.ExampleBean;`.");
            }
        } else if (error.contains("LLM response was empty") || error.contains("hit a token limit")
                || error.contains("contained only bytecode")) {
            repairMessage.append("\n- Do not return only context, documentation, or bytecode disassembly.");
            repairMessage.append("\n- Generate actual test code with concrete @Test methods that instantiate and "
                    + "interact with the target class.");
            repairMessage.append("\n- If your previous response was cut off or hit a token limit, generate fewer test "
                    + "cases but include actual test code.");
        } else if (previousResponse != null && previousResponse.contains("@Test")) {
            repairMessage.append("\n- The previous response already looked like a test class, so malformed Java syntax "
                    + "likely prevented method extraction. Recheck imports, class declaration, braces, and method "
                    + "headers.");
        } else {
            repairMessage.append("\n- Do not return prose or helper snippets only; include actual @Test methods in the "
                    + "final class.");
        }
    }

    static List<String> extractMalformedImportLines(String sourceText) {
        if (sourceText == null || sourceText.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> malformed = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = MALFORMED_IMPORT_LINE_PATTERN.matcher(sourceText);
        while (matcher.find()) {
            String line = matcher.group();
            if (line != null) {
                malformed.add(line.trim());
            }
        }
        return new ArrayList<>(malformed);
    }

    private static String extractAnonymousConstructedType(String sourceText) {
        return extractAnonymousConstructedTypeStatic(sourceText);
    }

    private static String extractAnonymousConstructedTypeStatic(String sourceText) {
        if (sourceText == null || sourceText.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher matcher = ANONYMOUS_TYPE_CREATION_PATTERN.matcher(sourceText);
        if (!matcher.find()) {
            return null;
        }
        return stripTypeDecorators(matcher.group(1));
    }

    private boolean isMalformedAnonymousSnippetSyntaxError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        String lower = error.toLowerCase();
        return lower.contains("snippet compilation")
                && (lower.contains("illegal start of type")
                || lower.contains("class, interface, enum, or record expected")
                || lower.contains("';' expected"));
    }

    private boolean hasOnlyNonPublicConstructors(Class<?> type) {
        if (type == null) {
            return false;
        }
        java.lang.reflect.Constructor<?>[] constructors = type.getDeclaredConstructors();
        if (constructors.length == 0) {
            return false;
        }
        for (java.lang.reflect.Constructor<?> constructor : constructors) {
            if (Modifier.isPublic(constructor.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    private boolean isArgumentPreconditionError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        String lower = error.toLowerCase();
        boolean illegalArgument = lower.contains("illegalargumentexception")
                || lower.contains("mockillegalargumentexception");
        if (!illegalArgument) {
            return false;
        }
        if (lower.contains("null source")
                && (lower.contains("eventobject") || lower.contains("actionevent"))) {
            return true;
        }
        return lower.contains("no ")
                || lower.contains("empty")
                || lower.contains("must ")
                || lower.contains("required")
                || lower.contains("cannot be ")
                || lower.contains("at least");
    }

    private boolean isIndexedFixtureShapeError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        for (String marker : INDEXED_FIXTURE_FAILURE_MARKERS) {
            if (error.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private boolean isReflectiveInvocationWrapperError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        String lower = error.toLowerCase();
        boolean mentionsInvocationTarget = lower.contains("invocationtargetexception");
        if (!mentionsInvocationTarget) {
            return false;
        }
        return lower.contains("method.invoke")
                || lower.contains("getdeclaredmethod")
                || lower.contains("setaccessible")
                || lower.contains("unexpected exception type thrown");
    }

    private boolean isStreamRelatedError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        String lower = error.toLowerCase();
        boolean mentionsStreamType = lower.contains("inputstream")
                || lower.contains("outputstream")
                || lower.contains("reader")
                || lower.contains("writer")
                || lower.contains("stream");
        if (!mentionsStreamType) {
            return false;
        }
        return lower.contains("null")
                || lower.contains("cannot be null")
                || lower.contains("illegalargumentexception")
                || lower.contains("stream closed")
                || lower.contains("eofexception")
                || lower.contains("malformedinputexception")
                || lower.contains("premature end of file");
    }

    private String extractNullVariableName(String error) {
        if (error == null) {
            return null;
        }
        java.util.regex.Matcher matcher = NPE_NULL_VARIABLE_PATTERN.matcher(error);
        if (matcher.find()) {
            String variable = matcher.group(1);
            if (variable != null && !variable.trim().isEmpty()) {
                return variable.trim();
            }
        }
        return null;
    }

    private static final class NpeDereferenceInfo {
        private final String memberSignature;
        private final String nullVariable;
        private final String receiverType;

        private NpeDereferenceInfo(String memberSignature, String nullVariable, String receiverType) {
            this.memberSignature = memberSignature;
            this.nullVariable = nullVariable;
            this.receiverType = receiverType;
        }
    }

    private NpeDereferenceInfo extractNpeDereferenceInfo(String error) {
        if (error == null || error.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher matcher = NPE_CANNOT_INVOKE_PATTERN.matcher(error);
        if (!matcher.find()) {
            return null;
        }

        String memberSignature = matcher.group(1) == null ? "" : matcher.group(1).trim();
        String nullVariable = matcher.group(2) == null ? "" : matcher.group(2).trim();
        if (memberSignature.isEmpty() || nullVariable.isEmpty()) {
            return null;
        }

        String receiverType = extractReceiverTypeFromMemberSignature(memberSignature);
        return new NpeDereferenceInfo(memberSignature, nullVariable, receiverType);
    }

    private String extractReceiverTypeFromMemberSignature(String memberSignature) {
        if (memberSignature == null || memberSignature.isEmpty()) {
            return null;
        }
        int paren = memberSignature.indexOf('(');
        String withoutArgs = paren >= 0 ? memberSignature.substring(0, paren) : memberSignature;
        int lastDot = withoutArgs.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        return withoutArgs.substring(0, lastDot);
    }

    private boolean appearsInParsedTestCodeExcerpt(String error, String variableName) {
        if (error == null || error.isEmpty() || variableName == null || variableName.isEmpty()) {
            return false;
        }
        java.util.regex.Matcher matcher = PARSED_TEST_CODE_EXCERPT_PATTERN.matcher(error);
        if (!matcher.find()) {
            return false;
        }
        String excerpt = matcher.group(1);
        if (excerpt == null || excerpt.isEmpty()) {
            return false;
        }
        return Pattern.compile("\\b" + Pattern.quote(variableName) + "\\b").matcher(excerpt).find();
    }

    private boolean isDomElementType(String receiverType) {
        return "org.w3c.dom.Element".equals(receiverType)
                || "org.w3c.dom.Node".equals(receiverType)
                || receiverType.endsWith(".Element");
    }

    /**
     * Appends two kinds of dep-code-driven repair guidance.
     * <ol>
     *   <li>For exceptions raised inside a non-CUT instance method or constructor body,
     *       inline a focused excerpt of that member's source/decompiled code so the
     *       LLM can satisfy the missing precondition.</li>
     *   <li>For {@code <clinit>} failures and follow-on {@code NoClassDefFoundError},
     *       record the poisoned class on the conversation-scoped set and emit a
     *       strong avoidance instruction. The class is permanently unusable for the
     *       rest of this run, so showing its source would only mislead the LLM.</li>
     * </ol>
     */
    private void appendDependencyCodeContext(RepairConversation state,
                                             Map<ParseResult, ExecutionFailureContext> executionFailures,
                                             StringBuilder repairMessage) {
        if (!Properties.LLM_INCLUDE_DEPENDENCY_CODE_ON_REPAIR) {
            return;
        }
        String targetClass = Properties.TARGET_CLASS;
        String targetPackage = getSutPackage();

        DependencyFailureAnalysis.Frame depFrame = null;

        if (executionFailures != null) {
            for (ExecutionFailureContext context : executionFailures.values()) {
                if (context == null || context.throwable == null) {
                    continue;
                }
                DependencyFailureAnalysis analysis = DependencyFailureAnalysis.analyze(
                        context.throwable, targetClass, targetPackage);
                switch (analysis.getKind()) {
                    case CLASS_INIT_FAILURE:
                    case CLASS_INIT_AFTERSHOCK:
                        if (analysis.getPoisonedClass() != null) {
                            state.recordPoisonedClass(analysis.getPoisonedClass());
                        }
                        break;
                    case DEP_MEMBER_FAILURE:
                        if (depFrame == null) {
                            depFrame = analysis.getFrame();
                        }
                        break;
                    default:
                        // No-op
                        break;
                }
            }
        }

        appendPoisonedClassAvoidance(state.getPoisonedClasses(), repairMessage);
        appendDependencyCodeExcerpt(depFrame, repairMessage);
    }

    private void appendPoisonedClassAvoidance(Set<String> poisonedClasses, StringBuilder repairMessage) {
        if (poisonedClasses.isEmpty()) {
            return;
        }
        repairMessage.append("\n\nPoisoned-class avoidance:");
        repairMessage.append("\n- The following non-CUT class(es) failed to initialize during this run "
                + "and are now permanently unusable in this JVM. Any test that touches them — directly "
                + "or transitively — will fail with NoClassDefFoundError no matter how it is rewritten.");
        for (String poisoned : poisonedClasses) {
            repairMessage.append("\n  * ").append(poisoned);
        }
        repairMessage.append("\n- Do NOT reference these classes from any test in the corrected class, "
                + "including via fields, parameters, return types, factories, or methods that internally "
                + "construct or load them. Choose alternative SUT/JDK types from the dependency summary instead.");
    }

    private void appendDependencyCodeExcerpt(DependencyFailureAnalysis.Frame depFrame,
                                             StringBuilder repairMessage) {
        if (depFrame == null) {
            return;
        }
        if (Properties.LLM_DEPENDENCY_CODE_MAX_CLASSES <= 0) {
            return;
        }
        int budget = Math.max(0, Properties.LLM_DEPENDENCY_CODE_MAX_CHARS);
        if (budget <= 0) {
            return;
        }
        Optional<String> excerpt = dependencyCodeResolver.resolveExcerpt(depFrame, budget);
        if (!excerpt.isPresent()) {
            return;
        }
        repairMessage.append("\n\nDependency code excerpt (non-CUT, source unavailable to LLM otherwise):\n")
                .append(excerpt.get())
                .append("\n- Use this excerpt to understand the precondition the failing call requires.");
        if (depFrame.isConstructor()) {
            repairMessage.append("\n- Provide constructor arguments that satisfy the body's null/range checks before "
                    + "invoking it.");
        } else {
            repairMessage.append("\n- Initialize collaborators or arguments so the method body's branches succeed "
                    + "instead of throwing.");
        }
        repairMessage.append("\n- Do not assert on the dependency's internals; only adjust how your test sets up "
                + "inputs to it.");
    }

    private void appendClassCastRepairHint(String error,
                                           Map<ParseResult, ExecutionFailureContext> executionFailures,
                                           StringBuilder repairMessage) {
        if (!isClassCastFailure(error, executionFailures)) {
            return;
        }
        repairMessage.append("\n\nTargeted repair hints:")
                .append("\n- ClassCastException indicates wrong-type setup/stubbing. Return the exact runtime "
                        + "type expected at that lookup/call site.");
    }

    /** Whether the error text or any failing test's root cause is a ClassCastException naming both types. */
    private boolean isClassCastFailure(String error, Map<ParseResult, ExecutionFailureContext> executionFailures) {
        if (error != null && error.contains("ClassCastException")
                && CLASS_CAST_PATTERN.matcher(error).find()) {
            return true;
        }
        for (Throwable throwable : collectExecutionThrowables(executionFailures)) {
            Throwable root = DependencyFailureAnalysis.unwrap(throwable);
            if (root instanceof ClassCastException && root.getMessage() != null
                    && CLASS_CAST_PATTERN.matcher(root.getMessage()).find()) {
                return true;
            }
        }
        return false;
    }

    private List<Throwable> collectExecutionThrowables(
            Map<ParseResult, ExecutionFailureContext> executionFailures) {
        if (executionFailures == null || executionFailures.isEmpty()) {
            return Collections.emptyList();
        }
        List<Throwable> throwables = new ArrayList<>();
        for (ExecutionFailureContext context : executionFailures.values()) {
            if (context != null && context.throwable != null) {
                throwables.add(context.throwable);
            }
        }
        return throwables;
    }

    private void appendInitializationFailureRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isInitializationFailureError(error)) {
            return;
        }
        String failingClass = extractInitializationFailureClassName(error);
        repairMessage.append("\n\nInitialization/dependency failure hint:");
        if (failingClass != null && !failingClass.isEmpty()) {
            repairMessage.append("\n- Detected failing initialization class: ").append(failingClass);
        }
        appendKeepExecutableTests(repairMessage);
        repairMessage.append("\n- For failing tests only, avoid code paths that load or initialize unavailable "
                + "framework/native classes.");
        repairMessage.append("\n- Avoid static initialization triggers of missing dependencies.");
        repairMessage.append("\n- Replace failing tests with alternatives that only use available SUT/JDK types.");
        repairMessage.append("\n- Prefer pure constructors/methods and precondition checks that do not require "
                + "GUI/native/plugin containers.");
        if (containsHeadlessGuiSignal(error)) {
            repairMessage.append("\n- AWT/Swing initialization failed in headless execution: avoid UI construction "
                    + "paths that require a real display toolkit.");
            repairMessage.append("\n- Prefer non-visual SUT logic and model-level methods; only create GUI objects "
                    + "when constructor preconditions are fully initialized.");
        }
    }

    /**
     * Strong, last-chance hint emitted exactly once per conversation when a
     * headless/AWT failure repeats unchanged. By the time we reach this branch,
     * gentler guidance has already failed; the LLM is stuck on a SUT it cannot
     * construct in this JVM. Tell it explicitly to drop the constructor.
     */
    private void appendHeadlessEscalationRepairInstructions(RepairConversation state, String error,
                                                            StringBuilder repairMessage) {
        if (!state.isHeadlessRepairEscalated() || !containsHeadlessGuiSignal(error)) {
            return;
        }
        String sut = Properties.TARGET_CLASS;
        String simpleSut = sut == null ? "<SUT>"
                : sut.substring(sut.lastIndexOf('.') + 1);
        repairMessage.append("\n\nHEADLESS ESCALATION (final attempt for this failure mode):");
        repairMessage.append("\n- The previous repair attempt produced the same headless/AWT failure. The SUT cannot "
                + "be constructed in this JVM.");
        repairMessage.append("\n- Do NOT call `new ").append(simpleSut).append("(...)` anywhere in the test class — "
                + "every call path through the constructor will hit `Local GraphicsEnvironment must not be null`.");
        repairMessage.append("\n- Replace SUT instantiation with `").append(simpleSut)
                .append(" sut = Mockito.mock(").append(simpleSut).append(".class);` (or `mock(")
                .append(simpleSut).append(".class)` when static Mockito imports are present), then exercise only "
                        + "methods that do not require real GUI state.");
        repairMessage.append("\n- For tests that need real behavior, restrict them to static methods or utility entry "
                + "points reachable without instantiation.");
        repairMessage.append("\n- Do NOT extract construction into a private helper method; the parser cannot inline "
                + "GUI-touching helpers and the SUT will be elided to a typed null.");
        repairMessage.append("\n- If a test fundamentally requires a real ").append(simpleSut)
                .append(", remove that test from the class — partial coverage is preferable to a class that yields "
                        + "zero executable tests.");
    }

    static boolean containsHeadlessGuiSignal(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        String lower = error.toLowerCase();
        return lower.contains("awterror")
                || lower.contains("headlessexception")
                || lower.contains("graphicsenvironment")
                || lower.contains("local graphicsenvironment must not be null");
    }

    private String extractInitializationFailureClassName(String error) {
        if (error == null) {
            return null;
        }
        java.util.regex.Matcher matcher = INIT_FAILURE_CLASS_PATTERN.matcher(error);
        if (matcher.find()) {
            String className = matcher.group(1);
            if (className != null && !className.trim().isEmpty()) {
                return className.trim();
            }
        }
        return null;
    }

    static boolean isInitializationFailureError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        for (String marker : INITIALIZATION_FAILURE_MARKERS) {
            if (error.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private void appendInstantiationFailureRepairInstructions(String error, StringBuilder repairMessage) {
        if (!isInstantiationFailureError(error)) {
            return;
        }
        repairMessage.append("\n\nInstantiation failure hint:");
        appendKeepExecutableTests(repairMessage);
        repairMessage.append("\n- For failing tests only, do NOT instantiate abstract classes or interfaces.");
        repairMessage.append("\n- Prefer concrete implementations listed in the dependency summary (use concrete "
                + "subtype constructors).");
        repairMessage.append("\n- If only an abstract/interface type is available at the call site, use a known "
                + "concrete subtype or a mock.");
        repairMessage.append("\n- Avoid reflective instantiation or helper patterns that hide non-instantiable types.");
    }

    static boolean isInstantiationFailureError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        for (String marker : INSTANTIATION_FAILURE_MARKERS) {
            if (error.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    static boolean isMockingMisuseError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        for (String marker : MOCKITO_MISUSE_MARKERS) {
            if (error.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    static boolean isSpyUnsupportedPattern(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        String lower = error.toLowerCase();
        boolean hasSpySignal = lower.contains("mockito.spy(")
                || lower.contains(".when(spy)")
                || lower.contains("when(spy.")
                || lower.contains("spy).");
        if (!hasSpySignal) {
            return false;
        }
        return lower.contains("__llm_fallback")
                || lower.contains("cannot find symbol")
                || lower.contains("notamockexception")
                || lower.contains("missingmethodinvocationexception")
                || lower.contains("llm_repair_action_required");
    }

    static boolean isFalsePositiveMockError(String error) {
        return extractFalsePositiveMockMethod(error) != null
                || (error != null && !error.isEmpty()
                && error.contains("FalsePositiveException")
                && error.contains("Mock call to"));
    }

    private static String extractFalsePositiveMockMethod(String error) {
        if (error == null || error.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher matcher = FALSE_POSITIVE_MOCK_CALL_PATTERN.matcher(error);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    /**
     * Picks the most useful name to surface for a false-positive mock failure.
     * The canonical signal is the {@code "Mock call to X which was not presented"}
     * phrase produced by the runtime; that yields the bare method name. The
     * SIGNATURE pattern can additionally enrich it with a {@code Class.method}
     * form when the runtime has captured the actual collaborator type — but
     * only when the matched method name agrees with the canonical hint;
     * otherwise the signature match is just noise from the test runner's own
     * stack frames (e.g. {@code java.util.ArrayList.forEach}) and must be
     * rejected so the canonical method name wins.
     */
    private static String pickFalsePositiveMockName(String error) {
        String simpleMethod = extractFalsePositiveMockMethod(error);
        String signature = extractFalsePositiveMockSignature(error);
        if (signature == null || signature.isEmpty()) {
            return simpleMethod;
        }
        if (simpleMethod == null || simpleMethod.isEmpty()) {
            return signature;
        }
        if (signature.endsWith("." + simpleMethod) || signature.equals(simpleMethod)) {
            return signature;
        }
        return simpleMethod;
    }

    private static String extractFalsePositiveMockSignature(String error) {
        if (error == null || error.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher matcher = FALSE_POSITIVE_MOCK_SIGNATURE_PATTERN.matcher(error);
        while (matcher.find()) {
            String className = matcher.group(1);
            String methodName = matcher.group(2);
            if (className == null || className.isEmpty() || methodName == null || methodName.isEmpty()) {
                continue;
            }
            String lowerClass = className.toLowerCase();
            if (lowerClass.startsWith("org.evosuite.")
                    || lowerClass.startsWith("org.mockito.")
                    || lowerClass.startsWith("java.lang.reflect.")) {
                continue;
            }
            return className + "." + methodName;
        }
        return null;
    }

    void appendFallbackRepairInstructions(String error, StringBuilder repairMessage) {
        if (error == null || error.isEmpty()) {
            return;
        }
        String lower = error.toLowerCase();
        boolean fallbackArtifactDetected =
                lower.contains("__llm_fallback")
                        || lower.contains("preserved as uninterpretedstatement")
                        || lower.contains("cannot resolve unscoped method call")
                        || lower.contains("cannot resolve method scope")
                        || lower.contains("setfield(")
                        || lower.contains("setstaticfield(");
        if (!fallbackArtifactDetected) {
            return;
        }

        LinkedHashSet<String> hints = new LinkedHashSet<>();
        hints.add("__llm_fallback variables are parser-generated placeholders inserted because the previous "
                + "LLM output contained unresolved or unsupported code that had to be replaced with a compilable "
                        + "fallback. "
                + "Do not trigger synthetic __llm_fallback variables by reusing unsupported helper patterns.");
        hints.add("Do NOT call methods on these fallback variables; they are 'null' placeholders and will throw "
                + "NullPointerException at runtime.");
        hints.add("Instead of using a fallback, fix the object instantiation that made the parser fail by using a "
                + "valid SUT/JDK type or a proper Mockito.mock().");
        hints.add("Use only existing SUT/JDK types and exact type names; never derive package/type names from variable "
                + "names.");
        hints.add("Do not reference non-public dependency types from other packages "
                + "(diagnostics like 'X is not public in package Y; cannot be accessed from outside package').");
        hints.add("If a constructor/method path requires a non-public cross-package type, do not force it with "
                + "fallbacks; "
                + "use a public alternative setup/factory/path from context or rewrite the test to avoid that path.");
        hints.add("Do not use helper wrappers like setField/setStaticField unless those methods are explicitly "
                + "available in the test class.");
        hints.add("Do not define anonymous classes; replace them with concrete existing types or null "
                + "when acceptable.");

        repairMessage.append("\n\nFallback-strategy repair instructions:");
        for (String hint : hints) {
            repairMessage.append("\n- ").append(hint);
        }
    }

    private void appendContextSpecificRepairFacts(String error,
                                                  String previousResponse,
                                                  StringBuilder repairMessage) {
        if ((error == null || error.isEmpty())
                && (previousResponse == null || previousResponse.isEmpty())) {
            return;
        }
        ContextTypeIndex context = ContextTypeIndex.fromSummary(sutContextSummary.get());
        if (context.isEmpty()) {
            return;
        }

        LinkedHashSet<String> facts = new LinkedHashSet<>();
        addNonInstantiableTypeFacts(previousResponse, context, facts);
        addNpeConstructionOriginFacts(error, previousResponse, context, facts);
        addSymbolConsistencyFacts(error, context, facts);

        if (facts.isEmpty()) {
            return;
        }
        repairMessage.append("\n\nContext-specific repair facts:");
        for (String fact : facts) {
            repairMessage.append("\n- ").append(fact);
        }
    }

    private void addNonInstantiableTypeFacts(String previousResponse,
                                             ContextTypeIndex context,
                                             LinkedHashSet<String> facts) {
        if (previousResponse == null || previousResponse.isEmpty()) {
            return;
        }
        java.util.regex.Matcher matcher = NEW_CALL_PATTERN.matcher(previousResponse);
        while (matcher.find()) {
            String constructedType = matcher.group(1);
            String simple = simpleName(constructedType);
            if (!context.isNonInstantiable(simple)) {
                continue;
            }
            List<String> concrete = context.getConcreteSubtypes(simple);
            if (concrete.isEmpty()) {
                facts.add("Detected construction of non-instantiable type '" + simple
                        + "'. Use a concrete subtype from context or Mockito.mock(" + simple + ".class).");
            } else {
                facts.add("Detected construction of non-instantiable type '" + simple
                        + "'. Prefer one of these concrete subtypes from context: "
                        + joinTop(concrete, 3) + ".");
            }
        }
    }

    private void addNpeConstructionOriginFacts(String error,
                                               String previousResponse,
                                               ContextTypeIndex context,
                                               LinkedHashSet<String> facts) {
        String nullVariable = extractNullVariableName(error);
        if (nullVariable == null || nullVariable.isEmpty()
                || previousResponse == null || previousResponse.isEmpty()) {
            return;
        }

        Map<String, String> declaredTypeByVar = new LinkedHashMap<>();
        Map<String, String> constructedTypeByVar = new LinkedHashMap<>();
        java.util.regex.Matcher newDecl = NEW_DECLARATION_PATTERN.matcher(previousResponse);
        while (newDecl.find()) {
            String declaredType = stripTypeDecorators(newDecl.group(1));
            String var = newDecl.group(2);
            String constructedType = stripTypeDecorators(newDecl.group(3));
            declaredTypeByVar.put(var, declaredType);
            constructedTypeByVar.put(var, constructedType);
        }
        java.util.regex.Matcher nullDecl = NULL_DECLARATION_PATTERN.matcher(previousResponse);
        while (nullDecl.find()) {
            String declaredType = stripTypeDecorators(nullDecl.group(1));
            String var = nullDecl.group(2);
            declaredTypeByVar.put(var, declaredType);
        }

        if (!declaredTypeByVar.containsKey(nullVariable)) {
            return;
        }
        String declaredType = declaredTypeByVar.get(nullVariable);
        String declaredSimple = simpleName(declaredType);
        if (context.isNonInstantiable(declaredSimple)) {
            List<String> concrete = context.getConcreteSubtypes(declaredSimple);
            if (concrete.isEmpty()) {
                facts.add("Null variable '" + nullVariable + "' has non-instantiable declared type '"
                        + declaredSimple + "'. Repair construction first (concrete subtype or Mockito.mock).");
            } else {
                facts.add("Null variable '" + nullVariable + "' has non-instantiable declared type '"
                        + declaredSimple + "'. Repair construction first using a concrete subtype such as "
                        + joinTop(concrete, 2) + ".");
            }
            return;
        }

        String constructedType = constructedTypeByVar.get(nullVariable);
        if (constructedType != null) {
            String constructedSimple = simpleName(constructedType);
            if (context.isKnownType(declaredSimple) && context.isKnownType(constructedSimple)
                    && !declaredSimple.equals(constructedSimple)) {
                facts.add("Null variable '" + nullVariable + "' has declared type '" + declaredSimple
                        + "' but is constructed as '" + constructedSimple
                        + "'. Repair type consistency before adding downstream null checks.");
            }
        } else {
            facts.add("Null variable '" + nullVariable + "' is explicitly assigned null. "
                    + "Repair by initializing required precondition objects at construction site first.");
        }
    }

    private void addSymbolConsistencyFacts(String error,
                                           ContextTypeIndex context,
                                           LinkedHashSet<String> facts) {
        if (error == null || error.isEmpty()) {
            return;
        }
        String lower = error.toLowerCase();
        if (!(lower.contains("cannot find symbol")
                || lower.contains("package ") && lower.contains(" does not exist"))) {
            return;
        }
        java.util.regex.Matcher symbolMatcher = MISSING_SYMBOL_CLASS_PATTERN.matcher(error);
        LinkedHashSet<String> missingSymbols = new LinkedHashSet<>();
        while (symbolMatcher.find()) {
            missingSymbols.add(symbolMatcher.group(1));
        }
        java.util.regex.Matcher missingPackageMatcher = MISSING_PACKAGE_PATTERN.matcher(error);
        boolean hasMissingPackage = false;
        while (missingPackageMatcher.find()) {
            hasMissingPackage = true;
            String missingPackage = missingPackageMatcher.group(1);
            facts.add("Unresolved package/type prefix '" + missingPackage
                    + "' often means an unqualified nested type/enum constant (for example Foo.BAR). "
                    + "Use the declaring type qualifier from context (for example OuterType."
                    + missingPackage + ".BAR) or an already declared variable.");
        }
        boolean hasMissingMethodWithLocation = MISSING_SYMBOL_METHOD_WITH_LOCATION_PATTERN.matcher(error).find();
        java.util.regex.Matcher missingVariableMatcher = MISSING_SYMBOL_VARIABLE_WITH_LOCATION_PATTERN.matcher(error);
        boolean hasMissingVariableWithLocation = false;
        while (missingVariableMatcher.find()) {
            hasMissingVariableWithLocation = true;
            String missingField = missingVariableMatcher.group(1);
            String receiverVar = missingVariableMatcher.group(2);
            String receiverType = missingVariableMatcher.group(3);
            if ("java.lang.Object".equals(receiverType)) {
                facts.add("Invalid member access '" + receiverVar + "." + missingField
                        + "': receiver is typed as Object (often a __llm_fallback alias). "
                        + "Remove this assertion/access and replace with assertions over real typed collaborators.");
            } else {
                facts.add("Cannot resolve member '" + missingField + "' on variable '" + receiverVar
                        + "' of type '" + receiverType + "'. Use accessible members that actually exist.");
            }
        }
        if (missingSymbols.isEmpty()) {
            if (!hasMissingMethodWithLocation && !hasMissingVariableWithLocation && !hasMissingPackage) {
                facts.add("Resolve symbol/package errors by using exact type names/packages from the provided context; "
                        + "do not invent FQCNs.");
            }
            return;
        }
        for (String symbol : missingSymbols) {
            if (error.contains("__llm_fallback")) {
                facts.add("Unresolved class '" + symbol + "' appears in a __llm_fallback declaration. "
                        + "Do not introduce short unresolved type aliases (e.g., '" + symbol
                        + " __llm_fallback...'); use a resolvable fully-qualified type or Object.");
            }
            if (error.contains("new " + symbol + "(")) {
                facts.add("Unresolved class '" + symbol + "' is being instantiated (`new "
                        + symbol + "(...)`). Do not invent helper/stub classes. "
                        + "Replace with an existing SUT/JDK type, or use Mockito.mock(ExpectedType.class, new "
                                + "ViolatedAssumptionAnswer()) "
                        + "for collaborator parameters.");
            }
            if (context.isKnownType(symbol)) {
                facts.add("Symbol '" + symbol + "' exists in provided context; use its exact context form/package and "
                        + "avoid invented qualifiers.");
            } else {
                facts.add("Symbol '" + symbol + "' is not present in provided context; replace it with a "
                        + "context-listed SUT/JDK type.");
            }
        }
    }

    /**
     * Appends hints for the diagnostics of the dropped tests. The diagnostics of tests dropped at
     * parse time are printed with their action text, so only the ones of tests dropped at
     * execution contribute that action; a hand-written hint is added only where no action exists.
     */
    String enrichParseErrorWithRepairHints(List<ParseResult> droppedAtParse,
                                           List<ParseResult> droppedAtExecution,
                                           String parseErrorText) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        collectDiagnosticHints(droppedAtParse, true, hints);
        collectDiagnosticHints(droppedAtExecution, false, hints);

        if (hints.isEmpty()) {
            return parseErrorText;
        }

        StringBuilder enriched = new StringBuilder(parseErrorText == null ? "" : parseErrorText);
        if (enriched.length() > 0) {
            enriched.append(System.lineSeparator());
        }
        enriched.append("Repair hints:");
        for (String hint : hints) {
            enriched.append(System.lineSeparator()).append("- ").append(hint);
        }
        return enriched.toString();
    }

    private void collectDiagnosticHints(List<ParseResult> parseResults, boolean printedWithAction,
                                        LinkedHashSet<String> hints) {
        if (parseResults == null) {
            return;
        }
        for (ParseResult parseResult : parseResults) {
            if (parseResult == null) {
                continue;
            }
            for (ParseDiagnostic diagnostic : parseResult.getDiagnostics()) {
                if (diagnostic == null || diagnostic.getMessage() == null) {
                    continue;
                }
                String message = diagnostic.getMessage();
                int explicitActionIdx = message.indexOf(EXPLICIT_REPAIR_ACTION_MARKER);
                if (explicitActionIdx >= 0) {
                    String explicit = message.substring(
                            explicitActionIdx + EXPLICIT_REPAIR_ACTION_MARKER.length()).trim();
                    if (!explicit.isEmpty() && !printedWithAction) {
                        hints.add(explicit);
                    }
                }

                // The action text of the diagnostic's kind replaces the generic hint for that kind.
                String generic = null;
                String lower = message.toLowerCase();
                if (lower.contains("cannot resolve method scope")) {
                    generic = "Declare the receiver variable earlier, or use a real static call "
                            + "in ClassName.method(...) form.";
                } else if (lower.contains("cannot resolve unscoped method call")) {
                    generic = "Avoid bare helper calls; inline helper logic or call an existing SUT/JDK method.";
                } else if (lower.contains("no method named") || lower.contains("no matching method")) {
                    String missingMethodName = extractMissingMethodName(message);
                    if (missingMethodName != null && !missingMethodName.isEmpty()) {
                        hints.add("The parser rejected invented method `" + missingMethodName
                                + "`; keep the raw failing line visible in the prompt instead of collapsing it into "
                                        + "generic `llm_feedback`.");
                        String snippet = diagnostic.getSourceSnippet();
                        if (snippet != null && !snippet.trim().isEmpty()) {
                            hints.add("Source expression: " + snippet.trim());
                        }
                        String closest = failureFormatter.findClosestAccessibleApiSuggestion(missingMethodName);
                        if (closest != null && !closest.isEmpty()) {
                            hints.add("Closest available API: " + closest);
                        }
                    } else {
                        hints.add("Replace invented method calls with real APIs from the supplied context; keep the "
                                + "offending line visible in the repair prompt.");
                    }
                } else if (lower.contains("unknown array variable")) {
                    generic = "Declare the array before indexing it, including full rank/dimensions.";
                } else if (lower.contains("unresolved variable")) {
                    generic = "Declare the variable earlier and keep names consistent across statements.";
                } else if (isUnresolvedTypeDiagnostic(diagnostic)) {
                    generic = "Replace invented/unknown types with existing SUT or JDK types.";
                }
                if (generic != null && explicitActionIdx < 0) {
                    hints.add(generic);
                }
            }
        }
    }
}
