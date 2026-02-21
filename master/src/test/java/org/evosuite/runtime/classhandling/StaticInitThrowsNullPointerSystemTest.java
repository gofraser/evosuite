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
package org.evosuite.runtime.classhandling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.SystemTestBase;
import org.evosuite.result.TestGenerationResult;
import org.junit.jupiter.api.Test;
import com.examples.with.different.packagename.reset.StaticInitThrowsNullPointer;

public class StaticInitThrowsNullPointerSystemTest extends SystemTestBase {

	/*
	 * These tests are based on issues found on project 44_summa, which is using the lucene API.
	 * those have issues when for example classes uses org.apache.lucene.util.Constants which has:
	 * 
	  try {
	    Collections.class.getMethod("emptySortedSet");
	  } catch (NoSuchMethodException nsme) {
	    v8 = false;
	  }
	  *
	  * in its static initializer
	 */

    @Test
    public void testWithNoReset() {
        runTheTest(false);
    }

    @Test
    public void testWithReset() {
        runTheTest(true);
    }

    @SuppressWarnings("unchecked")
    private void runTheTest(boolean reset) {
        Properties.RESET_STATIC_FIELDS = reset;
        EvoSuite evosuite = new EvoSuite();

        String targetClass = StaticInitThrowsNullPointer.class
                .getCanonicalName();

        Properties.TARGET_CLASS = targetClass;
        String[] command = new String[]{"-generateSuite", "-class",
                targetClass};

        Object result = evosuite.parseCommandLine(command);
        assertTrue(result instanceof List);
        List<List<TestGenerationResult<?>>> list = (List<List<TestGenerationResult<?>>>) result;
        assertEquals(1, list.size());
        assertEquals(1, list.get(0).size());

        TestGenerationResult<?> generationResult = list.get(0).get(0);
        assertEquals(TestGenerationResult.Status.ERROR, generationResult.getTestGenerationStatus());
        assertTrue(generationResult.getErrorMessage() != null);
        assertTrue(
                generationResult.getErrorMessage().contains("NullPointerException")
                        || generationResult.getErrorMessage().contains("ExceptionInInitializerError")
                        || generationResult.getErrorMessage().contains("Could not load target class"));
    }
}
