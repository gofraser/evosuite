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

import org.evosuite.runtime.testdata.*;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.utils.Randomness;

/**
 * Utility class for handling environment statements.
 *
 * @author arcuri
 * @see org.evosuite.runtime.testdata.EnvironmentDataList
 */
public class EnvironmentStatements {

    /**
     * Checks if the given class is an environment data type.
     *
     * @param clazz the class to check.
     * @return true if the class is an environment data type, false otherwise.
     */
    public static boolean isEnvironmentData(Class<?> clazz) {
        for (Class<?> env : EnvironmentDataList.getListOfClasses()) {
            if (clazz.equals(env)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates a new PrimitiveStatement for the given environment data type.
     *
     * @param clazz the environment data class.
     * @param tc    the test case context.
     * @return a new PrimitiveStatement instance.
     * @throws IllegalArgumentException if the class is not an environment data type.
     */
    public static PrimitiveStatement<?> getStatement(Class<?> clazz, TestCase tc) throws IllegalArgumentException {
        if (!isEnvironmentData(clazz)) {
            throw new IllegalArgumentException("Class " + clazz.getName() + " is not an environment data type");
        }

        if (clazz.equals(EvoSuiteFile.class)) {
            String fileName = Randomness.choice(tc.getAccessedEnvironment().getViewOfAccessedFiles());
            return new FileNamePrimitiveStatement(tc, new EvoSuiteFile(fileName));
        } else if (clazz.equals(EvoSuiteLocalAddress.class)) {
            return new LocalAddressPrimitiveStatement(tc);
        } else if (clazz.equals(EvoSuiteRemoteAddress.class)) {
            return new RemoteAddressPrimitiveStatement(tc);
        } else if (clazz.equals(EvoSuiteURL.class)) {
            return new UrlPrimitiveStatement(tc);
        }

        throw new RuntimeException("EvoSuite bug: unhandled class " + clazz.getName());
    }
}
