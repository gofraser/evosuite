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
 */
package org.evosuite.llm.postprocess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable oracle facts captured for one finalized test.
 *
 * <p>This type deliberately contains no prompt serialization methods. It is a
 * stable hand-off between context capture, prompt rendering, and response
 * validation.</p>
 */
final class OracleContext implements PostProcessingPromptFacts {

    private final LlmPostProcessingReferences references;
    private final LlmPostProcessingResponseParser.ParseContext parseContext;
    private final PromptVariantCapabilities capabilities;
    private final PostProcessingOptions options;
    private final List<LlmPostProcessingPromptContext.StatementContext> statements;
    private final List<LlmPostProcessingPromptContext.Observation> observations;
    private final List<LlmPostProcessingPromptContext.CallableMember> callableMembers;
    private final List<LlmPostProcessingPromptContext.ExceptionContext> exceptions;
    private final List<LlmPostProcessingPromptContext.CandidateFact> candidateFacts;
    private final List<LlmPostProcessingPromptContext.RelationalOpportunity> relationalOpportunities;

    private OracleContext(LlmPostProcessingReferences references,
                          PromptVariantCapabilities capabilities,
                          PostProcessingOptions options,
                          List<LlmPostProcessingPromptContext.StatementContext> statements,
                          List<LlmPostProcessingPromptContext.Observation> observations,
                          List<LlmPostProcessingPromptContext.CallableMember> callableMembers,
                          List<LlmPostProcessingPromptContext.ExceptionContext> exceptions,
                          List<LlmPostProcessingPromptContext.CandidateFact> candidateFacts,
                          List<LlmPostProcessingPromptContext.RelationalOpportunity> relationalOpportunities) {
        this.references = references;
        this.capabilities = capabilities;
        this.options = options;
        this.statements = immutableCopy(statements);
        this.observations = immutableCopy(observations);
        this.callableMembers = immutableCopy(callableMembers);
        this.exceptions = immutableCopy(exceptions);
        this.candidateFacts = immutableCopy(candidateFacts);
        this.relationalOpportunities = immutableCopy(relationalOpportunities);
        this.parseContext = buildParseContext();
    }

    static OracleContext from(LlmPostProcessingPromptContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Oracle context source must not be null");
        }
        return new OracleContext(
                context.getReferences(),
                context.getCapabilities(),
                context.getOptions(),
                context.getStatements(),
                context.getObservations(),
                context.getCallableMembers(),
                context.getExceptions(),
                context.getCandidateFacts(),
                context.getRelationalOpportunities());
    }

    @Override
    public LlmPostProcessingReferences getReferences() {
        return references;
    }

    LlmPostProcessingResponseParser.ParseContext toParseContext() {
        return parseContext;
    }

    private LlmPostProcessingResponseParser.ParseContext buildParseContext() {
        Set<LlmPostProcessingResponseParser.CallableMethod> callableMethods = new LinkedHashSet<>();
        for (LlmPostProcessingPromptContext.CallableMember member : callableMembers) {
            callableMethods.add(new LlmPostProcessingResponseParser.CallableMethod(
                    member.getReceiverId(), member.getOwnerType(),
                    LlmPostProcessingPromptContext.methodName(member.getSignature()),
                    LlmPostProcessingPromptContext.methodDescriptor(member.getSignature()),
                    member.getReturnType()));
        }

        Map<String, String> variableTypes = new LinkedHashMap<>();
        for (LlmPostProcessingPromptContext.StatementContext statement : statements) {
            if (statement.getVariableId() != null && statement.getDeclaredType() != null) {
                variableTypes.put(statement.getVariableId(), statement.getDeclaredType());
            }
        }

        Set<String> expressionVariableIds = new LinkedHashSet<>(references.getVariableIds());
        String throwingStatementId = exceptions.isEmpty() ? null : exceptions.get(0).getStatementId();
        if (throwingStatementId != null) {
            expressionVariableIds.add("e0");
            variableTypes.put("e0", "java.lang.Throwable");
            callableMethods.add(new LlmPostProcessingResponseParser.CallableMethod(
                    "e0", "java.lang.Throwable", "getMessage", "()Ljava/lang/String;",
                    "java.lang.String"));
            callableMethods.add(new LlmPostProcessingResponseParser.CallableMethod(
                    "e0", "java.lang.Throwable", "getCause", "()Ljava/lang/Throwable;",
                    "java.lang.Throwable"));
        }

        Set<String> observedCandidateKeys = new LinkedHashSet<>();
        Map<String, LlmPostProcessingResponseParser.SelectableCandidate> selectableCandidates =
                new LinkedHashMap<>();
        for (LlmPostProcessingPromptContext.CandidateFact fact : candidateFacts) {
            if (fact.getAssertionKey() != null) {
                observedCandidateKeys.add(fact.getAssertionKey());
            }
            if (LlmPostProcessingPromptContext.isCandidateSelectable(
                    fact, capabilities.hasAssertableTypesOnly(), capabilities.hasStabilityLabels())) {
                selectableCandidates.put(fact.getCandidateId(),
                        LlmPostProcessingPromptContext.candidateWithDefaultPlacement(
                                fact, throwingStatementId));
            }
        }

        Set<String> setupInputVariableIds = new LinkedHashSet<>();
        for (LlmPostProcessingPromptContext.Observation observation : observations) {
            if ("INPUT".equals(observation.getProvenance()) && observation.getVariableId() != null) {
                setupInputVariableIds.add(observation.getVariableId());
            }
        }
        return LlmPostProcessingResponseParser.production(
                references.getStatementIds(), expressionVariableIds, callableMethods,
                observedCandidateKeys, setupInputVariableIds, variableTypes,
                selectableCandidates, throwingStatementId, options);
    }

    @Override
    public List<LlmPostProcessingPromptContext.StatementContext> getStatements() {
        return statements;
    }

    @Override
    public List<LlmPostProcessingPromptContext.Observation> getObservations() {
        return observations;
    }

    @Override
    public List<LlmPostProcessingPromptContext.CallableMember> getCallableMembers() {
        return callableMembers;
    }

    @Override
    public List<LlmPostProcessingPromptContext.ExceptionContext> getExceptions() {
        return exceptions;
    }

    @Override
    public List<LlmPostProcessingPromptContext.CandidateFact> getCandidateFacts() {
        return candidateFacts;
    }

    @Override
    public List<LlmPostProcessingPromptContext.RelationalOpportunity> getRelationalOpportunities() {
        return relationalOpportunities;
    }

    private static <T> List<T> immutableCopy(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
