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
package org.evosuite.testcase;

import com.examples.with.different.packagename.generic.GenericConstructorTwoConsistentParameters;
import com.googlecode.gentyref.TypeToken;
import org.evosuite.Properties;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic unit test for the {@code StatementFactory.addConstructor} half of commit
 * 3f7bb5186 ("Bind owner type variables before satisfying generic call parameters").
 *
 * <p>An earlier version of this fixture (since removed; see {@link
 * TestMutatorGenericConstructorOwnerBindingTest}'s javadoc for the full story) exercised the same
 * bug (Defects4J Collections-21: a constructor such as {@code SetUniqueList(List<E>, Set<E>)}
 * reaching parameter resolution with E unbound, so the two parameters can be satisfied with
 * unrelated instantiations of E) but offered the mutator a self-consistent List/Set pair for every
 * instantiation it might land on. That fixture cannot reliably distinguish fixed from reverted
 * code: each parameter is resolved independently by a uniform random pick among same-raw-type
 * candidates, so the two picks coincide only by chance, regardless of whether E was ever bound.
 *
 * <p>This test closes that hole by offering only <em>one</em> candidate for each parameter, and
 * making the two mismatched (an {@code ArrayList<Integer>} and a {@code LinkedHashSet<String>}):
 * there is no self-consistent pair available at all, so no selection strategy can stumble onto a
 * correct answer by symmetry. Disabling mocking and leaving the test cluster empty means no third
 * alternative can be fabricated either. Bound E then has exactly two possible outcomes: the
 * (single) owner-binding attempt fails and the call falls through to the old per-parameter
 * resolution, in which case it reuses the only candidates available -- the mismatched pair,
 * reproducing the bug -- or E is bound to a single type up front, in which case satisfying the
 * second parameter against the mismatched candidate is impossible and construction must fail.
 * Either way, "construction succeeded with a List and a Set of different element types" is exactly
 * the bug, and that is the only outcome this test rejects.
 */
class StatementFactoryGenericConstructorOwnerBindingTest {

    private boolean originalMockIfNoGenerator;
    private double originalFunctionalMockingProbability;

    @BeforeEach
    void disableMockFallback() {
        // Block the "mock when no generator exists" escape hatch so that the only two ways to
        // satisfy a List<?>/Set<?> parameter are reusing the single existing candidate of that
        // raw type, or (if E was bound first) failing outright when no candidate matches the
        // bound element type. A mock would be a third, uninformative way out that proves nothing
        // about owner-type binding either way.
        originalMockIfNoGenerator = Properties.MOCK_IF_NO_GENERATOR;
        originalFunctionalMockingProbability = Properties.P_FUNCTIONAL_MOCKING;
        Properties.MOCK_IF_NO_GENERATOR = false;
        Properties.P_FUNCTIONAL_MOCKING = 0.0;
    }

    @AfterEach
    void restoreMockFallback() {
        Properties.MOCK_IF_NO_GENERATOR = originalMockIfNoGenerator;
        Properties.P_FUNCTIONAL_MOCKING = originalFunctionalMockingProbability;
    }

    @Test
    void addConstructorNeverMixesElementTypesAcrossParameters() throws Exception {
        DefaultTestCase test = new DefaultTestCase();

        VariableReference intList = addListOf(test, Integer.class);
        VariableReference stringSet = addSetOf(test, String.class);
        int position = test.size();

        Class<?> sutClass = GenericConstructorTwoConsistentParameters.class;
        Constructor<?> ctor = sutClass.getConstructor(List.class, Set.class);
        GenericConstructor unboundConstructor = new GenericConstructor(ctor, sutClass);

        VariableReference result;
        try {
            result = TestFactory.getInstance().addConstructor(
                    test, unboundConstructor, null, position, GenerationContext.fromDepth(0));
        } catch (ConstructionFailedException refusedToMixTypes) {
            // Correct: with no consistent List/Set pair available, refusing to construct is the
            // safe outcome. Nothing further to check.
            return;
        }

        ConstructorStatement constructorStatement =
                (ConstructorStatement) test.getStatement(result.getStPosition());
        List<VariableReference> params = constructorStatement.getParameterReferences();
        VariableReference listVar = params.get(0);
        VariableReference setVar = params.get(1);

        assertTrue(
                !(listVar.equals(intList) && setVar.equals(stringSet)),
                "Construction succeeded by pairing the mismatched ArrayList<Integer> with the "
                        + "LinkedHashSet<String>, exactly the Collections-21 bug (E bound to two "
                        + "different types at once): " + constructorStatement.getCode());

        // If it did not reproduce the exact bug above but still succeeded, the two parameters'
        // element types must genuinely agree -- it must have resolved E to a single type and then
        // either found a matching candidate (there is none here) or generated one.
        Type listArg = ((ParameterizedType) listVar.getGenericClass().getType()).getActualTypeArguments()[0];
        Type setArg = ((ParameterizedType) setVar.getGenericClass().getType()).getActualTypeArguments()[0];
        assertTrue(listArg.equals(setArg),
                "List and Set arguments must share one instantiation of E, got " + listArg
                        + " and " + setArg + ": " + constructorStatement.getCode());
    }

    private static VariableReference addListOf(DefaultTestCase test, Class<?> elementType) throws Exception {
        Type ownerType = elementType == Integer.class
                ? new TypeToken<ArrayList<Integer>>() {}.getType()
                : new TypeToken<ArrayList<String>>() {}.getType();
        Constructor<?> ctor = ArrayList.class.getConstructor();
        ConstructorStatement cs = new ConstructorStatement(test,
                new GenericConstructor(ctor, ownerType), Arrays.asList());
        return test.addStatement(cs);
    }

    private static VariableReference addSetOf(DefaultTestCase test, Class<?> elementType) throws Exception {
        Type ownerType = elementType == Integer.class
                ? new TypeToken<LinkedHashSet<Integer>>() {}.getType()
                : new TypeToken<LinkedHashSet<String>>() {}.getType();
        Constructor<?> ctor = LinkedHashSet.class.getConstructor();
        ConstructorStatement cs = new ConstructorStatement(test,
                new GenericConstructor(ctor, ownerType), Arrays.asList());
        return test.addStatement(cs);
    }
}
