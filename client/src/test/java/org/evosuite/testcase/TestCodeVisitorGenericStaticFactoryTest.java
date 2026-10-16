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

import com.examples.with.different.packagename.generic.Box;
import com.googlecode.gentyref.TypeToken;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the {@code SetUniqueList.setUniqueList(List)} bug (Defects4J Collections-21f):
 * a static factory method whose return type is the declaring (generic) class parameterized
 * by the method's <em>own</em> type variable -- e.g. {@code static <E> Box<E> of(List<E> list)}
 * on {@code class Box<E>} -- was always printed with a raw declaration
 * ({@code Box box0 = Box.of(...)}), even once {@code E} had been concretely bound to
 * {@code String}.
 *
 * <p>The cause was {@link TestCodeVisitor#getMethodReturnDeclarationType}: it falls back to
 * printing the method's erased {@code java.lang.reflect.Method#getReturnType()} whenever the
 * resolved return type still "depends on generic type information". Its helper,
 * {@code dependsOnGenericTypeInformation}, recursed into a parameterized type's
 * <em>raw type</em> to decide that -- but the raw type of any parameterized type is, by
 * definition, a {@code Class} that declares type parameters (that is what makes it
 * parameterizable), so the check was true for every parameterized type, concrete or not.
 * {@code Box<String>} was reported as "still generic" purely because {@code Box<E>} has a
 * class type parameter, even though {@code String} is fully resolved.</p>
 *
 * <p>Since the printed variable is raw, any later call on it is resolved by javac under
 * raw-type erasure, so downstream code such as {@code String s = box0.get(0)} fails to
 * compile ("incompatible types: Object cannot be converted to String") even though the
 * variable's tracked type was {@code Box<String>} all along.</p>
 */
public class TestCodeVisitorGenericStaticFactoryTest {

    @Test
    public void staticFactoryReturnDeclarationIsNotRaw() throws Exception {
        DefaultTestCase test = new DefaultTestCase();

        Type listOfStringType = new TypeToken<ArrayList<String>>() {}.getType();
        Constructor<?> arrayListCtor = ArrayList.class.getConstructor();
        VariableReference arrayListVar = test.addStatement(new ConstructorStatement(
                test, new GenericConstructor(arrayListCtor, listOfStringType), Arrays.asList()));

        Method of = Box.class.getMethod("of", List.class);
        GenericMethod factory = new GenericMethod(of, Box.class);
        // Mirrors what TestCluster#getRandomTestCall (and StatementFactory#bindTypeParameters)
        // already do before a call reaches this point: the method's own <E> is bound to a
        // concrete type -- here, String, matching the List<String> argument.
        factory.setTypeParameters(Collections.singletonList(GenericClassFactory.get(String.class)));

        MethodStatement factoryCall = new MethodStatement(test, factory, null, Arrays.asList(arrayListVar));
        VariableReference boxVar = test.addStatement(factoryCall);

        // A subsequent call whose return type is inferred from boxVar's resolved type, just
        // like SetUniqueList0.set(0, ...) in the D4J reproduction.
        Method get = Box.class.getMethod("get", int.class);
        GenericMethod getMethod = new GenericMethod(get, boxVar.getGenericClass());
        VariableReference zero = test.addStatement(new IntPrimitiveStatement(test, 0));
        VariableReference stringVar = test.addStatement(
                new MethodStatement(test, getMethod, boxVar, Arrays.asList(zero)));

        // Reference stringVar so its declaration actually gets printed.
        Method length = String.class.getMethod("length");
        GenericMethod lengthMethod = new GenericMethod(length, stringVar.getGenericClass());
        test.addStatement(new MethodStatement(test, lengthMethod, stringVar, Collections.emptyList()));

        String code = test.toCode();

        assertTrue(code.contains("Box<String> "),
                "Factory call must declare Box<String>, not a raw Box, got:\n" + code);
        assertFalse(code.matches("(?s).*\\bBox box\\d+ = .*"),
                "Factory call declaration must not be raw, got:\n" + code);

        // The downstream call on the (now correctly typed) receiver must itself be typed
        // String, not Object -- otherwise "String s = box0.get(0)" still fails to compile.
        assertTrue(code.matches("(?s).*\\bString \\w+ = box\\d+\\.get\\(.*"),
                "box0.get(...) must be declared as String, got:\n" + code);
    }
}
