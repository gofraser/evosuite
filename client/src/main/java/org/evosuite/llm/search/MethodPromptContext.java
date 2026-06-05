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
package org.evosuite.llm.search;

import java.util.Collections;
import java.util.List;

/**
 * Tiny DTO bundling the prompt-facing labels for a goal method (execution key,
 * display label, declaring type, and overload variants) — used by the card
 * emitters when composing card titles and evidence lines.
 */
final class MethodPromptContext {
    final String executionKey;
    final String displayLabel;
    final String typeName;
    final List<String> overloadLabels;

    MethodPromptContext(String executionKey, String displayLabel, String typeName, List<String> overloadLabels) {
        this.executionKey = executionKey;
        this.displayLabel = displayLabel == null || displayLabel.isEmpty() ? executionKey : displayLabel;
        this.typeName = typeName == null ? "" : typeName;
        this.overloadLabels = overloadLabels == null ? Collections.<String>emptyList() : overloadLabels;
    }
}
