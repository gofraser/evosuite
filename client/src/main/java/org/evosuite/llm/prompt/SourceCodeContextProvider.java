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

import org.evosuite.setup.TestCluster;

import java.util.Optional;

/**
 * Provides real source code context for the CUT using the existing SourceCodeProvider.
 */
public class SourceCodeContextProvider implements SutContextProvider {

    private final SourceCodeProvider sourceCodeProvider;

    public SourceCodeContextProvider() {
        this(new SourceCodeProvider());
    }

    public SourceCodeContextProvider(SourceCodeProvider sourceCodeProvider) {
        this.sourceCodeProvider = sourceCodeProvider;
    }

    @Override
    public Optional<String> getContext(String className, TestCluster cluster) {
        return sourceCodeProvider.getSourceCode(className);
    }

    @Override
    public String modeLabel() {
        return "Source code";
    }
}
