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

import org.evosuite.Properties;
import org.slf4j.Logger;

/**
 * Sink for {@link ProblemCardExtractor} structured trace records. Implementations
 * decide whether and how to emit; callers do not need to gate on a separate
 * {@code traceEnabled} flag.
 *
 * <p>The default no-op sink ({@link #NOOP}) is used in tests and any caller that
 * doesn't need extractor traces. The production sink ({@link DiagnosticLoggerSink})
 * defers the actual write decision to {@link DiagnosticTraceSupport}.
 */
public interface ExtractorTraceSink {

    /**
     * Emit (or skip) a structured trace record. {@code message} uses SLF4J-style
     * {@code {}} placeholders; the sink itself decides whether to format.
     */
    void trace(String phase, String message, Object... args);

    /** Sink that drops every record on the floor. */
    ExtractorTraceSink NOOP = new ExtractorTraceSink() {
        @Override
        public void trace(String phase, String message, Object... args) {
            // intentionally empty
        }
    };

    /** Sink that delegates to an SLF4J logger gated by {@link DiagnosticTraceSupport}. */
    final class DiagnosticLoggerSink implements ExtractorTraceSink {
        private final Logger logger;

        public DiagnosticLoggerSink(Logger logger) {
            if (logger == null) {
                throw new IllegalArgumentException("logger must not be null");
            }
            this.logger = logger;
        }

        @Override
        public void trace(String phase, String message, Object... args) {
            if (!DiagnosticTraceSupport.shouldWarnForCurrentTarget(logger)) {
                return;
            }
            Object[] prefixed = new Object[args == null ? 2 : args.length + 2];
            prefixed[0] = Properties.TARGET_CLASS;
            prefixed[1] = phase;
            if (args != null && args.length > 0) {
                System.arraycopy(args, 0, prefixed, 2, args.length);
            }
            logger.warn("extractor_trace target={} phase={} " + message, prefixed);
        }
    }
}
