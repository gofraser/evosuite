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

import com.examples.with.different.packagename.generic.GenericMethodTwoConsistentParameters;
import com.googlecode.gentyref.TypeToken;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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
 * Method-branch companion to {@link TestMutatorGenericConstructorOwnerBindingTest}, covering the
 * part of {@code TestMutator.changeCall} that commit 3f7bb5186 left untested: the non-static
 * method branch (around lines 82-94) that binds a generic method's owner type variables from its
 * callee, exactly as the constructor branch binds them from the owner class itself.
 *
 * <p>{@link GenericMethodTwoConsistentParameters#merge(List, Set)} mirrors
 * {@link com.examples.with.different.packagename.generic.GenericConstructorTwoConsistentParameters}'s
 * constructor: a non-static method with no type parameters of its own, declared on a generic
 * class, whose two parameters both mention the class type variable E. As in the sibling tests,
 * exactly one List candidate and one Set candidate are offered, with deliberately different
 * element types, so there is no self-consistent pair to stumble onto: reverting the owner-binding
 * hunk reuses the only two candidates there are -- the mismatched pair -- reproducing the
 * Collections-21-style bug deterministically, whereas the fix either refuses to bind E to two
 * things at once (an unavoidable exception, since {@code changeCall} has no fallback generation
 * path of its own) or, if it somehow still succeeds, must have chosen a single consistent type for
 * both parameters.
 */
class TestMutatorGenericMethodOwnerBindingTest {

    @Test
    void changeCallNeverMixesElementTypesAcrossMethodParameters() throws Exception {
        DefaultTestCase test = new DefaultTestCase();

        VariableReference intList = addListOf(test, Integer.class);
        VariableReference stringSet = addSetOf(test, String.class);

        // A callee instance for the non-static method: getRandomNonNullNonPrimitiveObject only
        // ever picks among existing variables, it never fabricates one, so without this the
        // method branch could not even be reached.
        Class<?> sutClass = GenericMethodTwoConsistentParameters.class;
        Constructor<?> calleeCtor = sutClass.getConstructor();
        ConstructorStatement calleeStatement = new ConstructorStatement(test,
                new GenericConstructor(calleeCtor, sutClass), Arrays.asList());
        test.addStatement(calleeStatement);

        VariableReference placeholder = test.addStatement(new NullStatement(test, Boolean.class));
        int position = placeholder.getStPosition();

        Method method = sutClass.getMethod("merge", List.class, Set.class);
        GenericMethod unboundMethod = new GenericMethod(method, sutClass);

        try {
            TestFactory.getInstance().changeCall(test, test.getStatement(position), unboundMethod);
        } catch (Exception refusedToMixTypes) {
            // Correct: with no consistent List/Set pair available, refusing to construct is the
            // safe outcome. Nothing further to check.
            return;
        }

        Statement replaced = test.getStatement(position);
        assertInstanceOf(MethodStatement.class, replaced);
        MethodStatement methodStatement = (MethodStatement) replaced;
        List<VariableReference> params = methodStatement.getParameterReferences();
        VariableReference listVar = params.get(0);
        VariableReference setVar = params.get(1);

        assertTrue(
                !(listVar.equals(intList) && setVar.equals(stringSet)),
                "Call succeeded by pairing the mismatched ArrayList<Integer> with the "
                        + "LinkedHashSet<String>, exactly the Collections-21-style bug (E bound to "
                        + "two different types at once): " + methodStatement.getCode());

        Type listArg = ((ParameterizedType) listVar.getGenericClass().getType()).getActualTypeArguments()[0];
        Type setArg = ((ParameterizedType) setVar.getGenericClass().getType()).getActualTypeArguments()[0];
        assertTrue(listArg.equals(setArg),
                "List and Set arguments must share one instantiation of E, got " + listArg
                        + " and " + setArg + ": " + methodStatement.getCode());
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
