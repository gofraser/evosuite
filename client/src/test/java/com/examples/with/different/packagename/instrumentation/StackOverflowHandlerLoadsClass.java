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
package com.examples.with.different.packagename.instrumentation;

/**
 * Mimics Jackson's {@code BeanSerializerBase.serializeFields}: a recursive method that
 * overflows the stack and, inside its own {@link StackOverflowError} handler, resolves a
 * class it has not touched before ({@link StackOverflowHandlerHelper}). At that point almost
 * no stack is left, so loading and instrumenting the helper class can itself overflow.
 */
public class StackOverflowHandlerLoadsClass {

    public static String probe(int n) {
        try {
            return probe(n + 1);
        } catch (StackOverflowError e) {
            // First use of the helper class happens here, with the stack nearly exhausted.
            return StackOverflowHandlerHelper.describe(n);
        }
    }

    public static String twice() {
        return probe(0).concat("/").concat(probe(0));
    }
}
