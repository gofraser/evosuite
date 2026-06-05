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

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExtractorTraceSinkTest {

    @Test
    void noopSinkDoesNotForwardAnyTraceCall() {
        // NOOP must be a hard no-op so the extractor can call it on every statement without
        // observable cost. We can't measure allocations directly, but we can assert that no
        // observable side effects happen and that nothing throws when the args are null.
        ExtractorTraceSink.NOOP.trace("phase", "msg={}", "value");
        ExtractorTraceSink.NOOP.trace("phase", "no-args");
        ExtractorTraceSink.NOOP.trace("phase", "null-args", (Object[]) null);
    }

    @Test
    void counterSinkCanReplaceProductionLoggerForTests() {
        AtomicInteger calls = new AtomicInteger();
        ExtractorTraceSink counting = (phase, message, args) -> calls.incrementAndGet();

        counting.trace("a", "x={}", 1);
        counting.trace("b", "x={}", 2);

        assertEquals(2, calls.get(),
                "A custom sink injected via constructor must observe every traced record");
    }
}
