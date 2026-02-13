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
package org.evosuite.testcase.statements.environment;


import org.evosuite.runtime.testdata.EvoSuiteURL;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.StringUtil;

/**
 * Primitive statement for URL.
 *
 * @author arcuri
 */
public class UrlPrimitiveStatement extends EnvironmentDataStatement<EvoSuiteURL> {

    private static final long serialVersionUID = 2062390100066807026L;

    /**
     * Constructor.
     *
     * @param tc the test case context.
     */
    public UrlPrimitiveStatement(TestCase tc) {
        this(tc, null);
        randomize();
    }


    /**
     * Constructor.
     *
     * @param tc    the test case context.
     * @param value the URL value.
     */
    public UrlPrimitiveStatement(TestCase tc, EvoSuiteURL value) {
        super(tc, EvoSuiteURL.class, value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getTestCode(String varName) {
        String testCode = "";
        VariableReference retval = getReturnValue();
        Object value = getValue();

        if (value != null) {
            String escapedURL = StringUtil.getEscapedString(((EvoSuiteURL) value).getUrl());
            testCode += ((Class<?>) retval.getType()).getSimpleName() + " "
                    + varName + " = new "
                    + ((Class<?>) retval.getType()).getSimpleName() + "(\""
                    + escapedURL + "\");\n";
        } else {
            testCode += ((Class<?>) retval.getType()).getSimpleName() + " "
                    + varName + " = null;\n";
        }
        return testCode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void delta() {
        randomize();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void zero() {

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void randomize() {
        String url = Randomness.choice(tc.getAccessedEnvironment().getViewOfRemoteURLs());
        if (url != null) {
            setValue(new EvoSuiteURL(url));
        } else {
            setValue(null);
        }
    }
}
