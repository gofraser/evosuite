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
import java.util.List;

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
    private final List<LlmPostProcessingPromptContext.StatementContext> statements;
    private final List<LlmPostProcessingPromptContext.Observation> observations;
    private final List<LlmPostProcessingPromptContext.CallableMember> callableMembers;
    private final List<LlmPostProcessingPromptContext.ExceptionContext> exceptions;
    private final List<LlmPostProcessingPromptContext.CandidateFact> candidateFacts;
    private final List<LlmPostProcessingPromptContext.RelationalOpportunity> relationalOpportunities;

    private OracleContext(LlmPostProcessingReferences references,
                          LlmPostProcessingResponseParser.ParseContext parseContext,
                          List<LlmPostProcessingPromptContext.StatementContext> statements,
                          List<LlmPostProcessingPromptContext.Observation> observations,
                          List<LlmPostProcessingPromptContext.CallableMember> callableMembers,
                          List<LlmPostProcessingPromptContext.ExceptionContext> exceptions,
                          List<LlmPostProcessingPromptContext.CandidateFact> candidateFacts,
                          List<LlmPostProcessingPromptContext.RelationalOpportunity> relationalOpportunities) {
        this.references = references;
        this.parseContext = parseContext;
        this.statements = immutableCopy(statements);
        this.observations = immutableCopy(observations);
        this.callableMembers = immutableCopy(callableMembers);
        this.exceptions = immutableCopy(exceptions);
        this.candidateFacts = immutableCopy(candidateFacts);
        this.relationalOpportunities = immutableCopy(relationalOpportunities);
    }

    static OracleContext from(LlmPostProcessingPromptContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Oracle context source must not be null");
        }
        return new OracleContext(
                context.getReferences(),
                context.toParseContext(),
                context.getStatements(),
                context.getObservations(),
                context.getCallableMembers(),
                context.getExceptions(),
                context.getCandidateFacts(),
                context.getRelationalOpportunities());
    }

    LlmPostProcessingReferences getReferences() {
        return references;
    }

    LlmPostProcessingResponseParser.ParseContext toParseContext() {
        return parseContext;
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
