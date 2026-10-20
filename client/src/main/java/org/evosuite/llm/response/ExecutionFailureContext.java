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

/**
 * Per-test execution failure: the formatted error string, the originating
 * throwable (used for dependency-code lookup), and the failing statement
 * position when known.
 */
final class ExecutionFailureContext {

    final String errorMessage;
    final Throwable throwable;
    final Integer failingPosition;

    ExecutionFailureContext(String errorMessage, Throwable throwable, Integer failingPosition) {
        this.errorMessage = errorMessage;
        this.throwable = throwable;
        this.failingPosition = failingPosition;
    }
}
