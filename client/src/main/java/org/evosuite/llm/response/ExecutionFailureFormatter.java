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

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.evosuite.Properties;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testparser.ParseDiagnostic;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testparser.TestParser;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.evosuite.llm.response.MockRuntimeExceptions.reportedExceptionName;
import static org.evosuite.llm.response.MockRuntimeExceptions.unmockedExceptionClass;
import static org.evosuite.llm.response.RepairText.NEW_CALL_PATTERN;
import static org.evosuite.llm.response.RepairText.extractFallbackTypeFromDiagnostic;
import static org.evosuite.llm.response.RepairText.extractMissingMethodName;
import static org.evosuite.llm.response.RepairText.extractTypedFallbackDeclaredType;
import static org.evosuite.llm.response.RepairText.extractTypedNullDeclarationTypes;
import static org.evosuite.llm.response.RepairText.getSutPackage;
import static org.evosuite.llm.response.RepairText.isUnresolvedTypeDiagnostic;
import static org.evosuite.llm.response.RepairText.joinTop;
import static org.evosuite.llm.response.RepairText.simpleName;
import static org.evosuite.llm.response.RepairText.stripTypeDecorators;
import static org.evosuite.llm.response.RepairText.truncate;

/**
 * Renders an exception raised by a parsed LLM test as repair feedback: the
 * exception with its root cause and a stack excerpt, the failing statement,
 * notes on parser fallback placeholders, and an indexed excerpt of the test.
 */
final class ExecutionFailureFormatter {

    private static final int MAX_STACK_EXCERPT_FRAMES = 4;
    static final int MAX_TEST_CODE_EXCERPT_CHARS = 1600;
    private static final int MAX_FALLBACK_DIAGNOSTIC_CHARS = 1200;
    private static final int MAX_FALLBACK_DIAGNOSTICS = 2;

    private static final String EXCERPT_INDEX_MARKER_PREFIX = "__EVOSUITE_REPAIR_INDEX__";
    private static final Pattern EXCERPT_INDEX_MARKER_PATTERN = Pattern.compile(
            "^\\s*//\\s*" + Pattern.quote(EXCERPT_INDEX_MARKER_PREFIX) + "(\\d+)\\s*$");
    static final String EXPLICIT_REPAIR_ACTION_MARKER = "LLM_REPAIR_ACTION_REQUIRED:";
    private static final Pattern EXPLICIT_REPAIR_ACTION_PATTERN = Pattern.compile(
            EXPLICIT_REPAIR_ACTION_MARKER + "\\s*(.*)$");
    private static final Pattern METHOD_SIGNATURE_PATTERN = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_$]*)\\s*\\(([^\\)]*)\\)");
    private static final Pattern INVENTED_UNKNOWN_TYPE_PATTERN = Pattern.compile(
            "replace invented/unknown type '([A-Za-z_][A-Za-z0-9_$.]*)'",
            Pattern.CASE_INSENSITIVE);

    private final Supplier<String> sutContextSummary;
    private final TestParser testParser;

    /**
     * @param sutContextSummary supplies the SUT context summary used for API suggestions and
     *                          type kinds, on first use; may supply null
     * @param testParser        the parser whose class loader resolves type names; may be null
     */
    ExecutionFailureFormatter(Supplier<String> sutContextSummary, TestParser testParser) {
        this.sutContextSummary = sutContextSummary;
        this.testParser = testParser;
    }

    static String formatThrowable(Throwable throwable) {
        String message = throwable.getMessage() == null ? "" : throwable.getMessage();
        return unmockedExceptionClass(throwable.getClass()).getSimpleName()
                + (message.isEmpty() ? "" : (": " + message));
    }

    String formatThrowableWithStackExcerpt(Throwable throwable,
                                                  ParseResult parseResult,
                                                  Integer failingPosition) {
        if (throwable == null) {
            return "UnknownException";
        }
        Throwable rootCause = DependencyFailureAnalysis.unwrap(throwable);
        StringBuilder message = new StringBuilder();
        message.append(reportedExceptionName(throwable))
                .append(" - ")
                .append(throwable.getMessage() == null ? "" : throwable.getMessage());
        if (rootCause != null && rootCause != throwable) {
            message.append("\nRoot cause: ")
                    .append(reportedExceptionName(rootCause))
                    .append(" - ")
                    .append(rootCause.getMessage() == null ? "" : rootCause.getMessage());
        }
        appendSnippetCompilationDetails(message, throwable, rootCause);
        message.append(buildFailureStackExcerpt(rootCause != null ? rootCause : throwable,
                parseResult, failingPosition));
        return message.toString();
    }

    private void appendSnippetCompilationDetails(StringBuilder message,
                                                 Throwable throwable,
                                                 Throwable rootCause) {
        if (!isSnippetCompilationThrowable(throwable) && !isSnippetCompilationThrowable(rootCause)) {
            return;
        }

        String wrapperMessage = throwable == null ? null : throwable.getMessage();
        String causeMessage = rootCause == null ? null : rootCause.getMessage();
        String compilerDiagnostics = extractSnippetCompilerDiagnostics(wrapperMessage, causeMessage);

        message.append("\nSnippet compilation note:");
        if (compilerDiagnostics != null && !compilerDiagnostics.isEmpty()) {
            message.append("\n- javac diagnostics:\n")
                    .append(compilerDiagnostics);
        } else {
            message.append("\n- No javac diagnostics were captured; only the snippet compilation wrapper message was "
                    + "available.");
        }
        message.append("\n- This came from EvoSuite-rendered synthesized code after parsing, not directly from raw LLM "
                + "text.");
    }

    private boolean isSnippetCompilationThrowable(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        String className = throwable.getClass().getName();
        if (className.endsWith("ExecutableSnippetEngine$SnippetCompilationException")
                || className.contains("ExecutableSnippetEngine$SnippetCompilationException")) {
            return true;
        }
        String message = throwable.getMessage();
        return message != null
                && (message.contains("SnippetCompilationException")
                || message.contains("Snippet compilation failed")
                || message.contains("Snippet compilation interrupted"));
    }

    private String extractSnippetCompilerDiagnostics(String wrapperMessage, String causeMessage) {
        String[] candidates = new String[]{wrapperMessage, causeMessage};
        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            int idx = candidate.indexOf("Snippet compilation failed for ");
            if (idx >= 0) {
                int colon = candidate.indexOf(':', idx);
                if (colon >= 0 && colon + 1 < candidate.length()) {
                    return candidate.substring(colon + 1).trim();
                }
            }
            if (candidate.contains("Could not compile snippet")
                    || candidate.contains("Snippet compilation interrupted")) {
                return null;
            }
            if (candidate.contains("cannot find symbol")
                    || candidate.contains("illegal start of type")
                    || candidate.contains("error:")
                    || candidate.contains("cannot be applied to given types")
                    || candidate.contains("has protected access")
                    || candidate.contains("not public in")
                    || candidate.contains("incompatible types")) {
                return candidate.trim();
            }
        }
        return null;
    }

    String buildExecutionContext(ParseResult parseResult,
                                         Integer failingPosition,
                                         Throwable thrown) {
        if (parseResult == null || parseResult.getTestCase() == null) {
            return "";
        }
        StringBuilder context = new StringBuilder();
        String statementCode = resolveDiagnosticStatementCode(parseResult, failingPosition, thrown);
        if (statementCode != null && !statementCode.isEmpty()) {
            context.append("\n")
                    .append(thrown instanceof TestCaseExecutor.TimeoutExceeded
                            ? "Timed out statement"
                            : "Failing statement");
            if (failingPosition != null) {
                context.append(" (index ").append(failingPosition).append(", zero-based)");
            }
            context.append(":\n").append(statementCode);
            String receiverNote = buildFailingStatementReceiverNote(parseResult, failingPosition, statementCode);
            if (receiverNote != null && !receiverNote.isEmpty()) {
                context.append(receiverNote);
            }
        }
        Integer laterAssertThrowsPosition = findLaterAssertThrowsStatementPosition(parseResult, failingPosition);
        if (laterAssertThrowsPosition != null) {
            context.append("\nAssertion reachability note: a later assertThrows(...) statement exists at index ")
                    .append(laterAssertThrowsPosition)
                    .append(" (zero-based), but execution failed earlier at index ")
                    .append(failingPosition)
                    .append(" (zero-based), so that assertion was never reached.");
        }
        String fallbackResolutionNote = buildFallbackResolutionNote(parseResult, failingPosition);
        if (fallbackResolutionNote != null && !fallbackResolutionNote.isEmpty()) {
            context.append(fallbackResolutionNote);
        }

        try {
            String testCode = renderIndexedTestCaseExcerpt(parseResult);
            if (testCode != null && !testCode.trim().isEmpty()) {
                context.append("\nParsed test code excerpt:\n```java\n")
                        .append(truncate(testCode.trim(), MAX_TEST_CODE_EXCERPT_CHARS))
                        .append("\n```");
            }
        } catch (Throwable ignored) {
            // best-effort diagnostic enrichment only
        }
        return context.toString();
    }

    private String buildFailingStatementReceiverNote(ParseResult parseResult,
                                                     Integer failingPosition,
                                                     String statementCode) {
        if (!isValidStatementPosition(parseResult, failingPosition)
                || statementCode == null
                || statementCode.trim().isEmpty()) {
            return "";
        }

        String receiverName = extractTopLevelReceiverVariableName(statementCode);
        if (receiverName == null || receiverName.isEmpty()) {
            return "";
        }

        String priorStatements = renderIndexedStatementRange(parseResult, 0, failingPosition);
        if (priorStatements.isEmpty()) {
            return "";
        }

        if (Pattern.compile("\\b" + Pattern.quote(receiverName) + "\\b").matcher(priorStatements).find()) {
            return "";
        }

        StringBuilder note = new StringBuilder();
        note.append("\nReceiver setup note: the receiver variable '")
                .append(receiverName)
                .append("' used by the failing statement has no visible earlier initialization in the parsed test "
                        + "excerpt.");

        String fallbackType = extractTypedFallbackDeclaredType(priorStatements);
        if (fallbackType != null && !fallbackType.isEmpty()) {
            note.append("\n- Earlier parsed statements contain parser-generated placeholder '")
                    .append(fallbackType)
                    .append(" __llm_fallback... = null;'. The missing receiver setup likely came from aliasing that "
                            + "null fallback.");
            note.append("\n- Replace the fallback-based collaborator chain with a real ")
                    .append(simpleName(fallbackType))
                    .append(" value before invoking or mutating it.");
        }
        return note.toString();
    }

    private String buildFallbackResolutionNote(ParseResult parseResult, Integer failingPosition) {
        if (parseResult == null || parseResult.getDiagnostics().isEmpty()) {
            return "";
        }
        int endExclusive = failingPosition == null
                ? parseResult.getTestCase().size()
                : Math.max(0, Math.min(parseResult.getTestCase().size(), failingPosition + 1));
        String relevantStatements = renderIndexedStatementRange(parseResult, 0, endExclusive);
        if (relevantStatements.isEmpty()) {
            return "";
        }

        boolean explicitFallbackMarker = relevantStatements.contains("__llm_fallback");
        String fallbackType = resolveFallbackTypeForRepairNote(parseResult, relevantStatements);
        List<ParseDiagnostic> relevantDiagnostics =
                findRelevantFallbackResolutionDiagnostics(parseResult, fallbackType);
        if (relevantDiagnostics.isEmpty()) {
            return "";
        }

        boolean constructorDiagnostic = false;
        boolean methodDiagnostic = false;
        String missingMethodName = null;
        InstantiationGuidance fallbackInstantiationGuidance = resolveInstantiationGuidance(fallbackType);
        LinkedHashSet<String> explicitRepairActions = new LinkedHashSet<>();
        LinkedHashSet<String> inventedHelperTypes = new LinkedHashSet<>();
        StringBuilder note = new StringBuilder();
        note.append("\nFallback origin note:");
        if (fallbackType != null && !fallbackType.isEmpty()) {
            if (explicitFallbackMarker) {
                note.append("\n- The earlier '")
                        .append(simpleName(fallbackType))
                        .append(" __llm_fallback... = null;' placeholder came from an unresolved constructor/method "
                                + "expression, not intended test logic.");
            } else {
                note.append("\n- The earlier typed null declaration for '")
                        .append(simpleName(fallbackType))
                        .append("' is parser-generated fallback state from an unresolved constructor/method "
                                + "expression, not intended test logic.");
            }
        }
        for (ParseDiagnostic diagnostic : relevantDiagnostics) {
            if (diagnostic == null || diagnostic.getMessage() == null || diagnostic.getMessage().trim().isEmpty()) {
                continue;
            }
            String message = diagnostic.getMessage().trim();
            String lower = message.toLowerCase();
            if (lower.contains("constructor")) {
                constructorDiagnostic = true;
            }
            if (lower.contains("method")) {
                methodDiagnostic = true;
            }
            String explicitRepairAction = extractExplicitRepairAction(message);
            if (explicitRepairAction != null && !explicitRepairAction.isEmpty()) {
                explicitRepairActions.add(explicitRepairAction);
            }
            if (missingMethodName == null) {
                missingMethodName = extractMissingMethodName(message);
            }
            note.append("\n- Parser detail: ")
                    .append(truncate(message, MAX_FALLBACK_DIAGNOSTIC_CHARS).replace('\n', ' '));
            String sourceSnippet = diagnostic.getSourceSnippet();
            if (sourceSnippet != null && !sourceSnippet.trim().isEmpty()) {
                note.append("\n- Source expression: ")
                        .append(truncate(sourceSnippet.trim(), MAX_FALLBACK_DIAGNOSTIC_CHARS).replace('\n', ' '));
                String inventedHelperType = extractInventedHelperTypeForFallback(diagnostic, fallbackType);
                if (inventedHelperType != null && !inventedHelperType.isEmpty()) {
                    inventedHelperTypes.add(inventedHelperType);
                }
            }
        }
        for (String explicitRepairAction : explicitRepairActions) {
            note.append("\n- Repair action: ").append(explicitRepairAction);
        }
        for (String inventedHelperType : inventedHelperTypes) {
            note.append("\n- The unresolved helper/local type inside that source expression is '")
                    .append(simpleName(inventedHelperType))
                    .append("'. Replace that invented helper with a real SUT/JDK/dependency value or rewrite the test "
                            + "to avoid it; do not only rename or re-alias the __llm_fallback placeholder.");
        }
        if (constructorDiagnostic) {
            if (fallbackInstantiationGuidance != null && fallbackInstantiationGuidance.isNonInstantiable()) {
                note.append("\n- ")
                        .append(buildNonInstantiableConstructionHint(fallbackType, fallbackInstantiationGuidance));
            } else {
                note.append("\n- Use one of the listed existing constructors exactly; match owner/package types "
                        + "exactly and include required trailing parameters.");
            }
        } else if (methodDiagnostic) {
            note.append("\n- Use one of the listed existing methods/overloads exactly; keep receiver and argument "
                    + "types aligned with the resolved signature.");
            String closest = findClosestAccessibleApiSuggestion(missingMethodName);
            if (closest != null && !closest.isEmpty()) {
                note.append("\n- Closest available API: ").append(closest);
            }
        }
        return note.toString();
    }

    String findClosestAccessibleApiSuggestion(String missingMethodName) {
        String summary = sutContextSummary.get();
        if (missingMethodName == null || missingMethodName.isEmpty()
                || summary == null || summary.isEmpty()) {
            return null;
        }

        java.util.regex.Matcher matcher = METHOD_SIGNATURE_PATTERN.matcher(summary);
        String bestSignature = null;
        String bestMethodName = null;
        int bestScore = 0;
        while (matcher.find()) {
            String candidateMethodName = matcher.group(1);
            if (candidateMethodName == null || candidateMethodName.isEmpty()
                    || candidateMethodName.equals(missingMethodName)) {
                continue;
            }
            int score = scoreMethodSimilarity(missingMethodName, candidateMethodName);
            if (score > bestScore) {
                bestScore = score;
                bestMethodName = candidateMethodName;
                bestSignature = candidateMethodName + "(" + matcher.group(2).trim() + ")";
            }
        }

        if (bestSignature == null || bestScore < 2) {
            return null;
        }
        if (bestMethodName == null) {
            return null;
        }
        return bestSignature;
    }

    private int scoreMethodSimilarity(String missingMethodName, String candidateMethodName) {
        if (missingMethodName == null || candidateMethodName == null
                || missingMethodName.isEmpty() || candidateMethodName.isEmpty()) {
            return 0;
        }
        String missingVerb = methodVerbPrefix(missingMethodName);
        String candidateVerb = methodVerbPrefix(candidateMethodName);
        String missingStem = stripMethodVerbPrefix(missingMethodName);
        String candidateStem = stripMethodVerbPrefix(candidateMethodName);
        Set<String> missingTokens = splitCamelCaseTokens(missingStem);
        Set<String> candidateTokens = splitCamelCaseTokens(candidateStem);
        missingTokens.retainAll(candidateTokens);
        int score = missingTokens.size();
        if (score == 0) {
            return 0;
        }
        if (!missingVerb.isEmpty()) {
            if (missingVerb.equals(candidateVerb)) {
                score += 5;
            } else {
                score -= 2;
            }
        }
        if (candidateTokens.containsAll(missingTokens)) {
            score += 1;
        }
        return score;
    }

    private String methodVerbPrefix(String methodName) {
        if (methodName == null || methodName.isEmpty()) {
            return "";
        }
        if (methodName.startsWith("set")) {
            return "set";
        }
        if (methodName.startsWith("get")) {
            return "get";
        }
        if (methodName.startsWith("is")) {
            return "is";
        }
        return "";
    }

    private String stripMethodVerbPrefix(String methodName) {
        if (methodName == null || methodName.length() <= 3) {
            return methodName == null ? "" : methodName;
        }
        if (methodName.startsWith("set") || methodName.startsWith("get")) {
            return methodName.substring(3);
        }
        if (methodName.startsWith("is")) {
            return methodName.substring(2);
        }
        return methodName;
    }

    private Set<String> splitCamelCaseTokens(String text) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (i > 0 && Character.isUpperCase(ch) && current.length() > 0) {
                tokens.add(current.toString().toLowerCase());
                current.setLength(0);
            }
            if (Character.isLetterOrDigit(ch)) {
                current.append(ch);
            } else if (current.length() > 0) {
                tokens.add(current.toString().toLowerCase());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString().toLowerCase());
        }
        return tokens;
    }

    private String extractExplicitRepairAction(String diagnosticMessage) {
        if (diagnosticMessage == null || diagnosticMessage.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher matcher = EXPLICIT_REPAIR_ACTION_PATTERN.matcher(diagnosticMessage);
        if (!matcher.find()) {
            return null;
        }
        String action = matcher.group(1);
        if (action == null) {
            return null;
        }
        return action.trim();
    }

    private String extractInventedHelperTypeForFallback(ParseDiagnostic diagnostic, String fallbackType) {
        if (diagnostic == null) {
            return null;
        }
        String sourceSnippet = diagnostic.getSourceSnippet();
        if (sourceSnippet == null || sourceSnippet.isEmpty()) {
            return null;
        }
        String diagnosticMessage = diagnostic.getMessage();
        if (!isUnresolvedTypeDiagnostic(diagnostic)) {
            return null;
        }

        if (diagnosticMessage != null) {
            java.util.regex.Matcher explicitTypeMatcher = INVENTED_UNKNOWN_TYPE_PATTERN.matcher(diagnosticMessage);
            if (explicitTypeMatcher.find()) {
                return stripTypeDecorators(explicitTypeMatcher.group(1));
            }
        }

        String fallbackSimple = simpleName(fallbackType);
        java.util.regex.Matcher matcher = NEW_CALL_PATTERN.matcher(sourceSnippet);
        String candidate = null;
        while (matcher.find()) {
            String constructedType = stripTypeDecorators(matcher.group(1));
            if (constructedType == null || constructedType.isEmpty()) {
                continue;
            }
            if (!fallbackSimple.isEmpty() && fallbackSimple.equals(simpleName(constructedType))) {
                continue;
            }
            candidate = constructedType;
        }
        return candidate;
    }

    private String resolveFallbackTypeForRepairNote(ParseResult parseResult, String relevantStatements) {
        String explicitFallbackType = extractTypedFallbackDeclaredType(relevantStatements);
        if (explicitFallbackType != null && !explicitFallbackType.isEmpty()) {
            return explicitFallbackType;
        }

        List<ParseDiagnostic> resolutionDiagnostics = findRelevantFallbackResolutionDiagnostics(parseResult, null);
        if (resolutionDiagnostics.size() == 1) {
            String diagnosticType = extractFallbackTypeFromDiagnostic(resolutionDiagnostics.get(0));
            if (diagnosticType != null && !diagnosticType.isEmpty()) {
                return diagnosticType;
            }
        }

        for (String candidateType : extractTypedNullDeclarationTypes(relevantStatements)) {
            if (!findRelevantFallbackResolutionDiagnostics(parseResult, candidateType).isEmpty()) {
                return candidateType;
            }
        }
        return null;
    }

    private String buildNonInstantiableConstructionHint(String fallbackType,
                                                        InstantiationGuidance guidance) {
        String simple = simpleName(fallbackType);
        String expression = "new " + simple + "()";
        if ("interface".equals(guidance.kind)) {
            if (!guidance.concreteSubtypes.isEmpty()) {
                return "The type '" + simple + "' is an interface, so `" + expression
                        + "` is invalid. Prefer a concrete implementation such as "
                        + joinTop(guidance.concreteSubtypes, 3)
                        + ", or use Mockito.mock(" + simple + ".class).";
            }
            return "The type '" + simple + "' is an interface, so `" + expression
                    + "` is invalid. Replace it with Mockito.mock(" + simple
                    + ".class) or a known concrete implementation.";
        }
        if (!guidance.concreteSubtypes.isEmpty()) {
            return "The type '" + simple + "' is abstract, so `" + expression
                    + "` is invalid. Prefer a concrete subtype such as "
                    + joinTop(guidance.concreteSubtypes, 3)
                    + ", or use Mockito.mock(" + simple + ".class).";
        }
        return "The type '" + simple + "' is abstract, so `" + expression
                + "` is invalid. Replace it with a concrete subtype or Mockito.mock("
                + simple + ".class).";
    }

    private InstantiationGuidance resolveInstantiationGuidance(String fallbackType) {
        String simple = simpleName(fallbackType);
        if (simple.isEmpty()) {
            return null;
        }

        ContextTypeIndex context = ContextTypeIndex.fromSummary(sutContextSummary.get());
        if (!context.isEmpty() && context.isNonInstantiable(simple)) {
            return new InstantiationGuidance(context.getKind(simple), context.getConcreteSubtypes(simple));
        }

        Class<?> resolved = tryResolveRepairType(fallbackType);
        if (resolved == null) {
            return null;
        }
        if (resolved.isInterface()) {
            return new InstantiationGuidance("interface", Collections.<String>emptyList());
        }
        if (Modifier.isAbstract(resolved.getModifiers())) {
            return new InstantiationGuidance("abstract", Collections.<String>emptyList());
        }
        return null;
    }

    Class<?> tryResolveRepairType(String typeName) {
        String clean = stripTypeDecorators(typeName);
        if (clean.isEmpty()) {
            return null;
        }
        LinkedHashSet<ClassLoader> loaders = new LinkedHashSet<>();
        if (testParser != null && testParser.getClassLoader() != null) {
            loaders.add(testParser.getClassLoader());
        }
        ClassLoader threadLoader = Thread.currentThread().getContextClassLoader();
        if (threadLoader != null) {
            loaders.add(threadLoader);
        }
        ClassLoader localLoader = ExecutionFailureFormatter.class.getClassLoader();
        if (localLoader != null) {
            loaders.add(localLoader);
        }
        for (ClassLoader loader : loaders) {
            try {
                return Class.forName(clean, false, loader);
            } catch (ClassNotFoundException ignored) {
                // Try next loader.
            } catch (LinkageError ignored) {
                // Best-effort only.
            }
        }
        return null;
    }

    private List<ParseDiagnostic> findRelevantFallbackResolutionDiagnostics(ParseResult parseResult,
                                                                            String fallbackType) {
        if (parseResult == null || parseResult.getDiagnostics().isEmpty()) {
            return Collections.emptyList();
        }

        List<ParseDiagnostic> matchingType = new ArrayList<>();
        List<ParseDiagnostic> resolutionDiagnostics = new ArrayList<>();
        for (ParseDiagnostic diagnostic : parseResult.getDiagnostics()) {
            if (diagnostic == null || diagnostic.getMessage() == null) {
                continue;
            }
            if (!isFallbackResolutionDiagnostic(diagnostic)) {
                continue;
            }
            resolutionDiagnostics.add(diagnostic);
            if (matchesFallbackDiagnosticType(diagnostic, fallbackType)) {
                matchingType.add(diagnostic);
            }
        }

        List<ParseDiagnostic> selected = !matchingType.isEmpty()
                ? matchingType
                : (resolutionDiagnostics.size() == 1 ? resolutionDiagnostics : Collections.emptyList());
        if (selected.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(selected.subList(0, Math.min(MAX_FALLBACK_DIAGNOSTICS, selected.size())));
    }

    private static final class InstantiationGuidance {
        private final String kind;
        private final List<String> concreteSubtypes;

        private InstantiationGuidance(String kind, List<String> concreteSubtypes) {
            this.kind = kind;
            this.concreteSubtypes = concreteSubtypes == null
                    ? Collections.<String>emptyList()
                    : concreteSubtypes;
        }

        private boolean isNonInstantiable() {
            return "interface".equals(kind) || "abstract".equals(kind);
        }
    }

    private boolean isFallbackResolutionDiagnostic(ParseDiagnostic diagnostic) {
        if (diagnostic == null || diagnostic.getMessage() == null || diagnostic.getMessage().isEmpty()) {
            return false;
        }
        String lower = diagnostic.getMessage().toLowerCase();
        return lower.startsWith("no matching constructor:")
                || lower.startsWith("no matching method:")
                || lower.startsWith("no method named ")
                || lower.startsWith("no static method named ")
                || lower.startsWith("method argument mismatch:")
                || (lower.contains("llm_repair_action_required:") && isUnresolvedTypeDiagnostic(diagnostic));
    }

    private boolean matchesFallbackDiagnosticType(ParseDiagnostic diagnostic, String fallbackType) {
        if (diagnostic == null || fallbackType == null || fallbackType.isEmpty()) {
            return false;
        }
        String fullType = fallbackType.toLowerCase();
        String simpleType = simpleName(fallbackType).toLowerCase();
        String message = diagnostic.getMessage() == null ? "" : diagnostic.getMessage().toLowerCase();
        String sourceSnippet = diagnostic.getSourceSnippet() == null ? "" : diagnostic.getSourceSnippet().toLowerCase();
        return message.contains(fullType)
                || sourceSnippet.contains(fullType)
                || (!simpleType.isEmpty() && (message.contains(simpleType) || sourceSnippet.contains(simpleType)));
    }

    private String renderIndexedTestCaseExcerpt(ParseResult parseResult) {
        if (parseResult == null || parseResult.getTestCase() == null) {
            return "";
        }
        return renderIndexedStatementRange(parseResult, 0, parseResult.getTestCase().size());
    }

    private String renderIndexedStatementRange(ParseResult parseResult, int startInclusive, int endExclusive) {
        if (parseResult == null || parseResult.getTestCase() == null) {
            return "";
        }
        Map<Integer, String> renderedBlocks = renderIndexedStatementBlocks(parseResult);
        StringBuilder excerpt = new StringBuilder();
        int from = Math.max(0, startInclusive);
        int to = Math.min(parseResult.getTestCase().size(), Math.max(from, endExclusive));
        for (int i = from; i < to; i++) {
            String statementCode = renderedBlocks.get(i);
            if ((statementCode == null || statementCode.trim().isEmpty())) {
                statementCode = renderStatementRangeFallback(parseResult, i);
            }
            if (statementCode == null || statementCode.trim().isEmpty()) {
                continue;
            }
            if (excerpt.length() > 0) {
                excerpt.append("\n");
            }
            excerpt.append("// [").append(i).append("]\n")
                    .append(statementCode.trim());
        }
        return excerpt.toString();
    }

    private Map<Integer, String> renderIndexedStatementBlocks(ParseResult parseResult) {
        Map<Integer, String> blocks = new LinkedHashMap<>();
        String rendered = renderAnnotatedIndexedTestCase(parseResult);
        if (rendered == null || rendered.trim().isEmpty()) {
            return blocks;
        }

        List<String> pendingLines = new ArrayList<>();
        Integer currentIndex = null;
        List<String> currentLines = new ArrayList<>();
        for (String line : rendered.split("\\R", -1)) {
            java.util.regex.Matcher markerMatcher = EXCERPT_INDEX_MARKER_PATTERN.matcher(line);
            if (markerMatcher.matches()) {
                if (currentIndex != null) {
                    blocks.put(currentIndex, trimBoundaryBlankLines(currentLines));
                }
                currentIndex = Integer.parseInt(markerMatcher.group(1));
                currentLines = new ArrayList<>(pendingLines);
                pendingLines.clear();
                continue;
            }
            if (currentIndex == null) {
                pendingLines.add(line);
            } else {
                currentLines.add(line);
            }
        }
        if (currentIndex != null) {
            blocks.put(currentIndex, trimBoundaryBlankLines(currentLines));
        }
        return blocks;
    }

    private String renderAnnotatedIndexedTestCase(ParseResult parseResult) {
        if (parseResult == null || parseResult.getTestCase() == null) {
            return "";
        }
        try {
            org.evosuite.testcase.TestCase cloned = parseResult.getTestCase().clone();
            for (int i = 0; i < cloned.size(); i++) {
                org.evosuite.testcase.statements.Statement statement = cloned.getStatement(i);
                String markerComment = statement.getComment().isEmpty()
                        ? EXCERPT_INDEX_MARKER_PREFIX + i
                        : "\n" + EXCERPT_INDEX_MARKER_PREFIX + i;
                statement.addComment(markerComment);
            }
            org.evosuite.testcase.TestCodeVisitor visitor = new org.evosuite.testcase.TestCodeVisitor();
            cloned.accept(visitor);
            return visitor.getCode();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String renderStatementRangeFallback(ParseResult parseResult, int index) {
        try {
            return parseResult.getTestCase().getStatement(index).getCode();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String trimBoundaryBlankLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        int start = 0;
        while (start < lines.size() && lines.get(start).trim().isEmpty()) {
            start++;
        }
        int end = lines.size() - 1;
        while (end >= start && lines.get(end).trim().isEmpty()) {
            end--;
        }
        if (start > end) {
            return "";
        }
        return String.join("\n", lines.subList(start, end + 1));
    }

    private String extractTopLevelReceiverVariableName(String statementCode) {
        if (statementCode == null || statementCode.trim().isEmpty()) {
            return null;
        }
        try {
            String normalized = statementCode.trim();
            Statement parsed = StaticJavaParser.parseStatement(
                    normalized.endsWith(";") ? normalized : normalized + ";");
            if (!(parsed instanceof ExpressionStmt)) {
                return null;
            }
            Expression expression = ((ExpressionStmt) parsed).getExpression();
            if (!(expression instanceof MethodCallExpr)) {
                return null;
            }
            MethodCallExpr methodCallExpr = (MethodCallExpr) expression;
            if (!methodCallExpr.getScope().isPresent() || !(methodCallExpr.getScope().get() instanceof NameExpr)) {
                return null;
            }
            String receiverName = ((NameExpr) methodCallExpr.getScope().get()).getNameAsString();
            return isLikelyTypeReferenceName(receiverName) ? null : receiverName;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isLikelyTypeReferenceName(String name) {
        return name != null && !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    Integer resolveDiagnosticStatementPosition(ParseResult parseResult,
                                                       Integer reportedPosition,
                                                       Throwable thrown) {
        if (isValidStatementPosition(parseResult, reportedPosition)) {
            return reportedPosition;
        }
        if (thrown instanceof TestCaseExecutor.TimeoutExceeded) {
            Integer timeoutPosition = ((TestCaseExecutor.TimeoutExceeded) thrown).getStatementPosition();
            if (isValidStatementPosition(parseResult, timeoutPosition)) {
                return timeoutPosition;
            }
        }
        return null;
    }

    private String resolveDiagnosticStatementCode(ParseResult parseResult,
                                                  Integer statementPosition,
                                                  Throwable thrown) {
        if (thrown instanceof TestCaseExecutor.TimeoutExceeded) {
            String timeoutStatementCode = ((TestCaseExecutor.TimeoutExceeded) thrown).getStatementCode();
            if (timeoutStatementCode != null && !timeoutStatementCode.trim().isEmpty()) {
                return timeoutStatementCode.trim();
            }
        }
        if (isValidStatementPosition(parseResult, statementPosition)) {
            try {
                String statementCode = parseResult.getTestCase().getStatement(statementPosition).getCode();
                if (statementCode != null && !statementCode.trim().isEmpty()) {
                    return statementCode.trim();
                }
            } catch (Throwable ignored) {
                // best-effort diagnostic enrichment only
            }
        }
        return null;
    }

    private Integer findLaterAssertThrowsStatementPosition(ParseResult parseResult,
                                                           Integer failingPosition) {
        if (!isValidStatementPosition(parseResult, failingPosition)) {
            return null;
        }
        for (int i = failingPosition + 1; i < parseResult.getTestCase().size(); i++) {
            try {
                String code = parseResult.getTestCase().getStatement(i).getCode();
                if (code != null && code.contains("assertThrows(")) {
                    return i;
                }
            } catch (Throwable ignored) {
                // best-effort diagnostic enrichment only
            }
        }
        return null;
    }

    private boolean isValidStatementPosition(ParseResult parseResult, Integer position) {
        return parseResult != null
                && parseResult.getTestCase() != null
                && position != null
                && position >= 0
                && position < parseResult.getTestCase().size();
    }

    private String buildFailureStackExcerpt(Throwable throwable,
                                           ParseResult parseResult,
                                           Integer failingPosition) {
        if (throwable == null) {
            return "";
        }
        StackTraceElement[] trace = throwable.getStackTrace();

        String targetClass = Properties.TARGET_CLASS;
        String targetPackage = getSutPackage();
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        StackTraceElement targetFrame = null;
        boolean nonFrameworkFrameSelected = false;

        if (trace != null && trace.length > 0) {
            for (StackTraceElement frame : trace) {
                if (frame == null) {
                    continue;
                }
                String className = frame.getClassName();
                if (DependencyFailureAnalysis.isCutClassName(className, targetClass, targetPackage)) {
                    targetFrame = frame;
                    break;
                }
            }

            if (targetFrame != null) {
                selected.add(targetFrame.toString());
                nonFrameworkFrameSelected = true;
            }

            for (StackTraceElement frame : trace) {
                if (selected.size() >= MAX_STACK_EXCERPT_FRAMES) {
                    break;
                }
                if (frame == null) {
                    continue;
                }
                String className = frame.getClassName();
                if (DependencyFailureAnalysis.isEvoSuiteFrame(className)) {
                    continue;
                }
                if (isJdkOrFrameworkFrame(className)) {
                    selected.add("[Dependency Stack] " + frame.toString());
                    nonFrameworkFrameSelected = true;
                    // Stop after capturing the first dependency cause
                    break;
                }
                selected.add(frame.toString());
                nonFrameworkFrameSelected = true;
            }

            if (selected.isEmpty()) {
                for (StackTraceElement frame : trace) {
                    if (frame == null) {
                        continue;
                    }
                    selected.add(frame.toString());
                    if (selected.size() >= 2) {
                        break;
                    }
                }
            }
        }

        String syntheticInvocationFrame = buildSyntheticInvocationFrame(parseResult, failingPosition);
        if (targetFrame == null && syntheticInvocationFrame != null && !syntheticInvocationFrame.isEmpty()) {
            LinkedHashSet<String> withSynthetic = new LinkedHashSet<>();
            withSynthetic.add(syntheticInvocationFrame);
            withSynthetic.addAll(selected);
            selected = withSynthetic;
        }

        if (selected.isEmpty()) {
            return "";
        }

        StringBuilder excerpt = new StringBuilder("\nFailure stack excerpt:");
        int count = 0;
        for (String frame : selected) {
            if (count >= MAX_STACK_EXCERPT_FRAMES) {
                break;
            }
            excerpt.append("\n- at ").append(frame);
            count++;
        }
        return excerpt.toString();
    }

    private String buildSyntheticInvocationFrame(ParseResult parseResult, Integer failingPosition) {
        if (!isValidStatementPosition(parseResult, failingPosition)) {
            return null;
        }
        org.evosuite.testcase.statements.Statement statement =
                parseResult.getTestCase().getStatement(failingPosition);
        if (statement == null) {
            return null;
        }

        org.evosuite.utils.generic.GenericAccessibleObject<?> accessibleObject = statement.getAccessibleObject();
        if (accessibleObject instanceof org.evosuite.utils.generic.GenericMethod) {
            org.evosuite.utils.generic.GenericMethod method =
                    (org.evosuite.utils.generic.GenericMethod) accessibleObject;
            String declaringClass = method.getDeclaringClass().getCanonicalName();
            if (declaringClass == null || declaringClass.isEmpty()) {
                declaringClass = method.getDeclaringClass().getName();
            }
            return declaringClass + "." + method.getName() + "(failing statement)";
        }

        String statementCode = resolveDiagnosticStatementCode(parseResult, failingPosition, null);
        String invocationFromCode = extractInvocationFromStatementCode(statementCode);
        if (invocationFromCode == null || invocationFromCode.isEmpty()) {
            return null;
        }
        return invocationFromCode + "(failing statement)";
    }

    private String extractInvocationFromStatementCode(String statementCode) {
        if (statementCode == null || statementCode.trim().isEmpty()) {
            return null;
        }
        try {
            String normalized = statementCode.trim();
            Statement parsed = StaticJavaParser.parseStatement(
                    normalized.endsWith(";") ? normalized : normalized + ";");
            if (!(parsed instanceof ExpressionStmt)) {
                return null;
            }
            Expression expression = ((ExpressionStmt) parsed).getExpression();
            if (!(expression instanceof MethodCallExpr)) {
                return null;
            }
            MethodCallExpr methodCallExpr = (MethodCallExpr) expression;
            if (!methodCallExpr.getScope().isPresent()) {
                return methodCallExpr.getNameAsString();
            }
            return methodCallExpr.getScope().get().toString() + "." + methodCallExpr.getNameAsString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isJdkOrFrameworkFrame(String className) {
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("org.junit.")
                || className.startsWith("org.mockito.");
    }
}
