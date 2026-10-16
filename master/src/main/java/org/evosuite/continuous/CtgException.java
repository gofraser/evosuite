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
package org.evosuite.continuous;

/**
 * Continuous test generation could not do its job, eg because it cannot write its data or the generated
 * tests. EvoSuite then exits with an error, so that a caller such as the Maven plugin fails the build.
 *
 * <p>A class for which no tests could be generated is not such an error: the run reports it and goes on.</p>
 */
public class CtgException extends RuntimeException {

    private static final long serialVersionUID = 6125893048327811540L;

    public CtgException(String message) {
        super(message);
    }

    public CtgException(String message, Throwable cause) {
        super(message, cause);
    }
}
