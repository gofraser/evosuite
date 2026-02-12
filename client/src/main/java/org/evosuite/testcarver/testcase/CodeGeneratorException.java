/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.testcarver.testcase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CodeGeneratorException extends RuntimeException {

    private static final long serialVersionUID = -4032911019839769269L;

    private static final Logger LOGGER = LoggerFactory.getLogger(CodeGeneratorException.class);

    public CodeGeneratorException(final String msg) {
        super(msg);
    }

    public CodeGeneratorException(final Throwable e) {
        super(e);
    }

    /**
     * Checks if the given expression is true, otherwise throws a CodeGeneratorException.
     *
     * @param expr    the expression to check
     * @param msg     the error message format string
     * @param msgArgs the arguments for the error message
     * @throws CodeGeneratorException if the expression is false
     */
    public static void check(final boolean expr, final String msg, final Object... msgArgs)
            throws CodeGeneratorException {
        if (!expr) {
            final String finalMsg = String.format(msg, msgArgs);
            LOGGER.info(finalMsg);
            throw new CodeGeneratorException(finalMsg);
        }
    }


    /**
     * Propagates an error by logging it and throwing a CodeGeneratorException.
     *
     * @param t       the throwable to propagate (optional)
     * @param msg     the error message format string
     * @param msgArgs the arguments for the error message
     * @throws CodeGeneratorException always
     */
    public static void propagateError(final Throwable t, final String msg, final Object... msgArgs)
            throws CodeGeneratorException {
        final String finalMsg = String.format(msg, msgArgs);

        if (t == null) {
            LOGGER.info(finalMsg);
        } else {
            LOGGER.error(finalMsg, t);
        }

        throw new CodeGeneratorException(finalMsg);
    }

    /**
     * Propagates an error by logging it and throwing a CodeGeneratorException.
     *
     * @param msg     the error message format string
     * @param msgArgs the arguments for the error message
     * @throws CodeGeneratorException always
     */
    public static void propagateError(final String msg, final Object... msgArgs)
            throws CodeGeneratorException {
        propagateError(null, msg, msgArgs);
    }
}
