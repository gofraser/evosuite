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
package org.evosuite;

import org.evosuite.graphs.GraphPool;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

public class ContextResetterTest {

    @Test
    public void resetInstallsAFreshLoaderAndEmptiesThePools() {
        TestGenerationContext context = TestGenerationContext.getInstance();
        InstrumentingClassLoader before = context.getClassLoaderForSUT();
        GraphPool oldPool = GraphPool.getInstance(before);

        ContextResetter.reset();

        InstrumentingClassLoader after = context.getClassLoaderForSUT();
        assertNotSame(before, after);
        assertSame(after, TestGenerationContext.getClassLoader());
        assertNotSame(oldPool, GraphPool.getInstance(after));
    }
}
