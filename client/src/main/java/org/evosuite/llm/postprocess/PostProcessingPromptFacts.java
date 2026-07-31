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

import java.util.List;

/**
 * Immutable facts consumed by the production prompt renderer.
 */
interface PostProcessingPromptFacts {

    List<LlmPostProcessingPromptContext.StatementContext> getStatements();

    List<LlmPostProcessingPromptContext.Observation> getObservations();

    List<LlmPostProcessingPromptContext.CallableMember> getCallableMembers();

    List<LlmPostProcessingPromptContext.ExceptionContext> getExceptions();

    List<LlmPostProcessingPromptContext.CandidateFact> getCandidateFacts();

    List<LlmPostProcessingPromptContext.RelationalOpportunity> getRelationalOpportunities();
}
