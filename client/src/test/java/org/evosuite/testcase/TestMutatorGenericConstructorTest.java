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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Reproduces the SetUniqueList(List&lt;E&gt;, Set&lt;E&gt;) bug (Defects4J Collections-21f):
 * {@link TestMutator#changeCall} replaced a statement with a constructor call whose two
 * parameters both mention the class type variable E, but picked each parameter's variable
 * independently because E was never bound before the parameter types were widened to
 * wildcards. The two arguments then ended up with unrelated (and mutually incompatible)
 * instantiations of E, and the printed constructor call's inferred type argument could only
 * match one of them, so javac rejected the generated statement.
 */
public class TestMutatorGenericConstructorTest {

    @Test
    public void changeCallBindsOwnerTypeVariableConsistentlyAcrossParameters() throws Exception {
        DefaultTestCase test = new DefaultTestCase();

        // Existing variables the mutator can choose from: a self-consistent List/Set pair for
        // each of a few instantiations of E, so that whichever instantiation the fix settles on,
        // a matching pair exists. Critically, no *mixed* pair is offered (e.g. a List<Integer>
        // can only be completed by a Set<Integer>, never by a Set<String>).
        VariableReference intList = addListOf(test, Integer.class);
        VariableReference intSet = addSetOf(test, Integer.class);
        VariableReference stringList = addListOf(test, String.class);
        VariableReference stringSet = addSetOf(test, String.class);
        VariableReference objectList = addListOf(test, Object.class);
        VariableReference objectSet = addSetOf(test, Object.class);

        // Placeholder statement that changeCall() will replace, mirroring how
        // TestMutator#changeRandomCall picks a raw, un-instantiated GenericConstructor from
        // TestCluster#getGenerators and hands it to changeCall().
        VariableReference placeholder = test.addStatement(
                new NullStatement(test, GenericConstructorTwoConsistentParameters.class));
        int position = placeholder.getStPosition();

        Class<?> sutClass = GenericConstructorTwoConsistentParameters.class;
        Constructor<?> ctor = sutClass.getConstructor(List.class, Set.class);
        GenericConstructor unboundConstructor = new GenericConstructor(ctor, sutClass);

        TestFactory.getInstance().changeCall(test, test.getStatement(position), unboundConstructor);

        Statement replaced = test.getStatement(position);
        assertInstanceOf(ConstructorStatement.class, replaced);
        ConstructorStatement constructorStatement = (ConstructorStatement) replaced;

        // The owner's type variable E must have been bound to a single concrete type: both
        // parameter types share the same type argument.
        Type[] parameterTypes = constructorStatement.getConstructor().getParameterTypes();
        Type listArg = ((ParameterizedType) parameterTypes[0]).getActualTypeArguments()[0];
        Type setArg = ((ParameterizedType) parameterTypes[1]).getActualTypeArguments()[0];
        assertEquals(listArg, setArg,
                "List and Set parameters must share the same instantiation of E: " + constructorStatement.getCode());

        // And the arguments actually used in the statement must be exactly the matching pair
        // for whichever instantiation was chosen -- never a List<X> paired with a Set<Y != X>.
        List<VariableReference> params = constructorStatement.getParameterReferences();
        VariableReference listVar = params.get(0);
        VariableReference setVar = params.get(1);
        if (listArg.equals(Integer.class)) {
            assertEquals(intList, listVar);
            assertEquals(intSet, setVar);
        } else if (listArg.equals(String.class)) {
            assertEquals(stringList, listVar);
            assertEquals(stringSet, setVar);
        } else {
            assertEquals(objectList, listVar);
            assertEquals(objectSet, setVar);
        }
    }

    private static VariableReference addListOf(DefaultTestCase test, Class<?> elementType) throws Exception {
        Type ownerType = elementType == Integer.class
                ? new TypeToken<ArrayList<Integer>>() {}.getType()
                : elementType == String.class
                        ? new TypeToken<ArrayList<String>>() {}.getType()
                        : new TypeToken<ArrayList<Object>>() {}.getType();
        Constructor<?> ctor = ArrayList.class.getConstructor();
        ConstructorStatement cs = new ConstructorStatement(test,
                new GenericConstructor(ctor, ownerType), Arrays.asList());
        return test.addStatement(cs);
    }

    private static VariableReference addSetOf(DefaultTestCase test, Class<?> elementType) throws Exception {
        Type ownerType = elementType == Integer.class
                ? new TypeToken<LinkedHashSet<Integer>>() {}.getType()
                : elementType == String.class
                        ? new TypeToken<LinkedHashSet<String>>() {}.getType()
                        : new TypeToken<LinkedHashSet<Object>>() {}.getType();
        Constructor<?> ctor = LinkedHashSet.class.getConstructor();
        ConstructorStatement cs = new ConstructorStatement(test,
                new GenericConstructor(ctor, ownerType), Arrays.asList());
        return test.addStatement(cs);
    }
}
