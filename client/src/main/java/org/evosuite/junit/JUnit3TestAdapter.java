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
package org.evosuite.junit;

/**
 * JUnit3TestAdapter class. Differs from JUnit 4 only in the imports and the class and method
 * definitions; the annotation classes and the non-functional requirement rule are shared.
 *
 * @author fraser
 */
public class JUnit3TestAdapter extends JUnit4TestAdapter {

    /**
     * {@inheritDoc}
     */
    @Override
    public String getImports() {
        return "import junit.framework.TestCase;\n";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getClassDefinition(String testName) {
        return "public class " + testName + " extends TestCase";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getMethodDefinition(String testName) {
        return "public void " + testName + "() ";
    }

}
