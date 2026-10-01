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
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic companion to {@link TestMutatorGenericConstructorTest} and {@link
 * StatementFactoryGenericConstructorOwnerBindingTest}, covering the third of the three call sites
 * commit 3f7bb5186 touches: {@code TestMutator.changeCall}'s constructor branch.
 *
 * <p>{@code TestMutatorGenericConstructorTest} offers the mutator a self-consistent List/Set pair
 * for each of several instantiations of E, which (as the sibling class docs explain) lets an
 * unbound, per-parameter resolution land on a matching pair by construction, not because E was
 * ever actually bound. This test instead offers exactly one List candidate and one Set candidate,
 * of deliberately different element types, so there is no self-consistent pair to stumble onto:
 * reverting the owner-binding hunk in {@code TestMutator} reuses the only two candidates there
 * are -- the mismatched pair -- reproducing the Collections-21 bug deterministically, whereas the
 * fix either refuses to bind E to two things at once (an unavoidable
 * {@code ConstructionFailedException}, since {@code changeCall} has no fallback generation path
 * of its own) or, if it somehow still succeeds, must have chosen a single consistent type for
 * both parameters.
 */
class TestMutatorGenericConstructorOwnerBindingTest {

    @Test
    void changeCallNeverMixesElementTypesAcrossParameters() throws Exception {
        DefaultTestCase test = new DefaultTestCase();

        VariableReference intList = addListOf(test, Integer.class);
        VariableReference stringSet = addSetOf(test, String.class);

        VariableReference placeholder = test.addStatement(
                new NullStatement(test, GenericConstructorTwoConsistentParameters.class));
        int position = placeholder.getStPosition();

        Class<?> sutClass = GenericConstructorTwoConsistentParameters.class;
        Constructor<?> ctor = sutClass.getConstructor(List.class, Set.class);
        GenericConstructor unboundConstructor = new GenericConstructor(ctor, sutClass);

        try {
            TestFactory.getInstance().changeCall(test, test.getStatement(position), unboundConstructor);
        } catch (Exception refusedToMixTypes) {
            // Correct: with no consistent List/Set pair available, refusing to construct is the
            // safe outcome. Nothing further to check.
            return;
        }

        Statement replaced = test.getStatement(position);
        assertInstanceOf(ConstructorStatement.class, replaced);
        ConstructorStatement constructorStatement = (ConstructorStatement) replaced;
        List<VariableReference> params = constructorStatement.getParameterReferences();
        VariableReference listVar = params.get(0);
        VariableReference setVar = params.get(1);

        assertTrue(
                !(listVar.equals(intList) && setVar.equals(stringSet)),
                "Construction succeeded by pairing the mismatched ArrayList<Integer> with the "
                        + "LinkedHashSet<String>, exactly the Collections-21 bug (E bound to two "
                        + "different types at once): " + constructorStatement.getCode());

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
