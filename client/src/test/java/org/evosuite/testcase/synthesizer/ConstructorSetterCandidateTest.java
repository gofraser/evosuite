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
package org.evosuite.testcase.synthesizer;

import com.examples.with.different.packagename.PrivateParameterTypeFixture;
import org.evosuite.Properties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConstructorSetterCandidateTest {

    @Test
    public void constructorTakingASourcePrivateTypeIsNotASetterCandidate() throws Exception {
        String previous = Properties.CLASS_PREFIX;
        try {
            Properties.CLASS_PREFIX = PrivateParameterTypeFixture.class.getPackage().getName();
            // No test can name Secret, so no test can write the argument for this constructor.
            assertFalse(DataDependencyUtil.isConstructorSetterCandidate(
                    PrivateParameterTypeFixture.class.getConstructor(
                            PrivateParameterTypeFixture.secretType())));
            assertTrue(DataDependencyUtil.isConstructorSetterCandidate(
                    ConstructorSetterCandidateTest.class.getConstructor()));
        } finally {
            Properties.CLASS_PREFIX = previous;
        }
    }
}
