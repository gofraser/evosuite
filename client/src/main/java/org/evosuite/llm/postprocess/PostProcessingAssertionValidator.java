/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm.postprocess;

import org.evosuite.assertion.TemplateCodeAssertion;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutableSnippetEngine;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.variable.VariableReference;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates accepted assertion proposals against observed and stable scopes.
 *
 * <p>Structural and expression validation happens before this stage. This
 * component owns only execution-dependent checks for non-throwing tests.</p>
 */
final class PostProcessingAssertionValidator {

    private final LlmPostProcessor.StabilityExecutionRunner stabilityExecutionRunner;
    private final LlmPostProcessor.AssertionEvaluationRunner assertionEvaluationRunner;

    PostProcessingAssertionValidator(
            LlmPostProcessor.StabilityExecutionRunner stabilityExecutionRunner,
            LlmPostProcessor.AssertionEvaluationRunner assertionEvaluationRunner) {
        this.stabilityExecutionRunner = stabilityExecutionRunner;
        this.assertionEvaluationRunner = assertionEvaluationRunner;
    }

    static LlmPostProcessor.AssertionEvaluationRunner defaultEvaluationRunner() {
        return new TemplateAssertionEvaluationRunner();
    }

    ValidatedEditPlan validate(
            LlmPostProcessingResponse response,
            TestCase validationTest,
            ExecutionResult executionResult,
            TestCase reusableStabilityTest,
            ExecutionResult reusableStabilityExecutionResult,
            PostProcessingOptions options) {
        if (response.getAssertions().isEmpty()) {
            return ValidatedEditPlan.success(response);
        }

        TestCase stabilityTest = reusableStabilityTest;
        ExecutionResult stabilityExecutionResult = reusableStabilityExecutionResult;
        if (stabilityTest == null) {
            stabilityTest = validationTest == null ? null : validationTest.clone();
            if (stabilityTest != null) {
                stabilityTest.removeAssertions();
            }
            stabilityExecutionResult = stabilityTest == null
                    ? null
                    : stabilityExecutionRunner.execute(stabilityTest);
        }
        return filterAssertionsByValidatedScopes(response, validationTest, executionResult,
                stabilityTest, stabilityExecutionResult, options);
    }

    private ValidatedEditPlan filterAssertionsByValidatedScopes(
            LlmPostProcessingResponse response,
            TestCase validationTest,
            ExecutionResult executionResult,
            TestCase stabilityTest,
            ExecutionResult stabilityExecutionResult,
            PostProcessingOptions options) {
        if (response.getAssertions().isEmpty()) {
            return ValidatedEditPlan.success(response);
        }
        if (validationTest == null || validationTest.size() == 0
                || executionResult == null || executionResult.getFinalScope() == null
                || executionResult.hasTimeout() || executionResult.hasTestException()
                || !executionResult.noThrownExceptions()) {
            return ValidatedEditPlan.rejectedAll(response,
                    LlmPostProcessingParseResult.DiagnosticCode.OBSERVED_EXECUTION,
                    "Original observed final scope is unavailable");
        }
        if (stabilityTest == null || stabilityTest.size() == 0
                || stabilityExecutionResult == null || stabilityExecutionResult.getFinalScope() == null
                || stabilityExecutionResult.hasTimeout()
                || stabilityExecutionResult.hasTestException()
                || !stabilityExecutionResult.noThrownExceptions()) {
            return ValidatedEditPlan.rejectedAll(response,
                    LlmPostProcessingParseResult.DiagnosticCode.STABILITY_EXECUTION,
                    "Stability re-execution did not produce a normal final scope");
        }

        LlmPostProcessingReferences validationReferences = LlmPostProcessingReferences.from(validationTest);
        LlmPostProcessingReferences stabilityReferences = LlmPostProcessingReferences.from(stabilityTest);
        Scope originalFinalScope = executionResult.getFinalScope();
        Scope stabilityFinalScope = stabilityExecutionResult.getFinalScope();
        LlmPostProcessingResponse filtered = response.withoutAssertions();
        List<LlmPostProcessingParseResult.Diagnostic> diagnostics = new ArrayList<>();
        for (LlmPostProcessingResponse.AssertionProposal proposal : response.getAssertions()) {
            LlmPostProcessor.EvaluationOutcome originalOutcome = assertionEvaluationRunner.evaluate(
                    proposal, validationTest, validationReferences, originalFinalScope, options);
            if (!originalOutcome.isAccepted()) {
                diagnostics.add(validationDiagnostic(proposal, originalOutcome,
                        "Assertion rejected against original observed final scope"));
                continue;
            }

            LlmPostProcessor.EvaluationOutcome stabilityOutcome = assertionEvaluationRunner.evaluate(
                    proposal, stabilityTest, stabilityReferences, stabilityFinalScope, options);
            if (!stabilityOutcome.isAccepted()) {
                diagnostics.add(validationDiagnostic(proposal, stabilityOutcome.asStabilityFailure(),
                        "Assertion rejected against stability final scope"));
                continue;
            }
            filtered.addAssertion(proposal);
        }
        return ValidatedEditPlan.create(filtered, diagnostics);
    }

    private static LlmPostProcessingParseResult.Diagnostic validationDiagnostic(
            LlmPostProcessingResponse.AssertionProposal proposal,
            LlmPostProcessor.EvaluationOutcome outcome,
            String defaultMessage) {
        String id = proposal == null ? "?" : proposal.getAssertionId();
        String message = outcome.message() == null ? defaultMessage : outcome.message();
        if (outcome.diagnosticReason() == null) {
            return new LlmPostProcessingParseResult.Diagnostic(outcome.diagnosticCode(),
                    "assertions[" + id + "]", message);
        }
        return new LlmPostProcessingParseResult.Diagnostic(outcome.diagnosticCode(),
                "assertions[" + id + "]", message, outcome.diagnosticReason());
    }

    private static final class TemplateAssertionEvaluationRunner
            implements LlmPostProcessor.AssertionEvaluationRunner {
        @Override
        public LlmPostProcessor.EvaluationOutcome evaluate(
                LlmPostProcessingResponse.AssertionProposal proposal,
                TestCase validationTest,
                LlmPostProcessingReferences references,
                Scope finalScope,
                PostProcessingOptions options) {
            try {
                TemplateCodeAssertion assertion = LlmPostProcessingEditApplier
                        .toTemplateAssertionForValidation(proposal, references);
                if (assertion == null || validationTest == null || validationTest.size() == 0
                        || finalScope == null) {
                    return LlmPostProcessor.EvaluationOutcome.safetyFailure(
                            LlmPostProcessingParseResult.DiagnosticCode.OBSERVED_EXECUTION,
                            "Assertion could not be bound to the final scope");
                }
                assertion.setStatement(validationTest.getStatement(validationTest.size() - 1));
                Map<String, Type> variableTypes = new LinkedHashMap<>();
                Map<String, Object> variableValues = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> entry : assertion.getBindings().entrySet()) {
                    int position = entry.getValue();
                    if (position < 0 || position >= validationTest.size()) {
                        return LlmPostProcessor.EvaluationOutcome.safetyFailure(
                                LlmPostProcessingParseResult.DiagnosticCode.OBSERVED_EXECUTION,
                                "Assertion binding is outside the test");
                    }
                    VariableReference variable = validationTest.getStatement(position).getReturnValue();
                    if (variable == null) {
                        return LlmPostProcessor.EvaluationOutcome.safetyFailure(
                                LlmPostProcessingParseResult.DiagnosticCode.OBSERVED_EXECUTION,
                                "Assertion binding has no variable");
                    }
                    Class<?> accessibleType = OracleTypeAccessibility.accessibleView(
                            variable.getVariableClass(), options.targetClass());
                    variableTypes.put(entry.getKey(),
                            accessibleType == null ? variable.getType() : accessibleType);
                    variableValues.put(entry.getKey(), variable.getObject(finalScope));
                }
                return ExecutableSnippetEngine.INSTANCE.evaluateAssertion(assertion.render(null),
                        variableTypes, variableValues,
                        options.assertionPolicy().compileTimeoutMs(),
                        options.assertionPolicy().evalTimeoutMs())
                        ? LlmPostProcessor.EvaluationOutcome.accepted()
                        : LlmPostProcessor.EvaluationOutcome.observedFailure(
                        "Assertion did not hold in the final scope");
            } catch (ExecutableSnippetEngine.AssertionCompilationTimeoutException e) {
                return LlmPostProcessor.EvaluationOutcome.compileFailure(e.getMessage());
            } catch (ExecutableSnippetEngine.AssertionEvaluationTimeoutException e) {
                return LlmPostProcessor.EvaluationOutcome.observedFailure(e.getMessage());
            } catch (RuntimeException | AssertionError e) {
                return LlmPostProcessor.EvaluationOutcome.compileFailure(e.getMessage());
            } catch (Throwable e) {
                return LlmPostProcessor.EvaluationOutcome.observedFailure(e.getMessage());
            }
        }
    }
}
