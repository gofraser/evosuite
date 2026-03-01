/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.llm.prompt;

import org.evosuite.Properties;
import org.evosuite.llm.LlmMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable result of {@link PromptBuilder#buildWithMetadata()},
 * carrying both the chat messages and context metadata for trace recording.
 */
public final class PromptResult {

    private final List<LlmMessage> messages;
    private final Properties.LlmSutContextMode sutContextMode;
    private final boolean contextUnavailable;

    /** Constructs an immutable prompt result with associated context metadata. */
    public PromptResult(List<LlmMessage> messages,
                        Properties.LlmSutContextMode sutContextMode,
                        boolean contextUnavailable) {
        this.messages = messages == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(messages));
        this.sutContextMode = sutContextMode;
        this.contextUnavailable = contextUnavailable;
    }

    public List<LlmMessage> getMessages() {
        return messages;
    }

    /**
     * Context mode actually used (may differ from configured mode after fallback),
     * or null if withSutContext was not called.
     */
    public Properties.LlmSutContextMode getSutContextMode() {
        return sutContextMode;
    }

    /** True if context extraction failed and no fallback was available. */
    public boolean isContextUnavailable() {
        return contextUnavailable;
    }
}
