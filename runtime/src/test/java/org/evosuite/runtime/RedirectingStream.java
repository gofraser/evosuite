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
package org.evosuite.runtime;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/** Wraps the stream it is given, like a logger that redirects System.out on construction. */
final class RedirectingStream extends PrintStream {
    final PrintStream inner;

    RedirectingStream(PrintStream inner) {
        super(new ByteArrayOutputStream());
        this.inner = inner;
    }

    static int depth(PrintStream s) {
        int d = 0;
        while (s instanceof RedirectingStream) {
            d++;
            s = ((RedirectingStream) s).inner;
        }
        return d;
    }
}
