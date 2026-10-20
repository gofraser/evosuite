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

import java.util.List;

/**
 * Sends plain message lists through {@link LlmService#query(LlmRequest)} and
 * returns the text of the reply.
 */
public final class LlmServiceTestQueries {

    private LlmServiceTestQueries() {
    }

    public static String query(LlmService service, List<LlmMessage> messages, LlmFeature feature) {
        return service.query(LlmRequest.of(messages, feature)).getText();
    }

    public static String query(LlmService service, List<LlmMessage> messages, LlmFeature feature,
                               long deadlineNanos) {
        return service.query(LlmRequest.of(messages, feature).withDeadlineNanos(deadlineNanos)).getText();
    }
}
