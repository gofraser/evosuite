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

import org.evosuite.junit.writer.TestSuiteWriterUtils;
import org.evosuite.runtime.vnet.NonFunctionalRequirementRule;

/**
 * JUnit3TestAdapter class.
 *
 * @author fraser
 */
public class JUnit3TestAdapter implements UnitTestAdapter {

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

    @Override
    public Class<?> testAnnotation() {
        return org.junit.Test.class;
    }

    @Override
    public Class<?> beforeAll() {
        return org.junit.BeforeClass.class;
    }

    @Override
    public Class<?> beforeEach() {
        return org.junit.Before.class;
    }

    @Override
    public Class<?> afterAll() {
        return org.junit.AfterClass.class;
    }

    @Override
    public Class<?> afterEach() {
        return org.junit.After.class;
    }

    @Override
    public void addNFR(StringBuilder builder) {
        builder.append(TestSuiteWriterUtils.METHOD_SPACE);
        builder.append("@").append(org.junit.Rule.class.getCanonicalName()).append("\n");
        builder.append(TestSuiteWriterUtils.METHOD_SPACE);
        builder.append("public ").append(NonFunctionalRequirementRule.class.getName())
                .append(" nfr = new ").append(NonFunctionalRequirementRule.class.getName()).append("();\n\n");
    }

}
