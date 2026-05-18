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

import org.evosuite.coverage.exception.ExceptionCoverageTestFitness;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.LongPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.ShortPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Tries to reproduce the OptionalDataException cascade observed in long
 * runs on JCLO. The pattern we are chasing: serializing a DefaultTestCase
 * with a mix of Statements whose retval types include primitives, arrays,
 * wrappers and a few parameterized collections; the master-side
 * GenericClass.readObject hits eof=true on ownerType for the simple types
 * even though the writer wrote name+FALSE for them.
 */
public class TestSerializationDesyncReproTest {

    @Test
    public void roundTripsWithMixedGenericClassReferences() throws Exception {
        DefaultTestCase test = buildSampleTest();
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(test);

        byte[] bytes = serialize(chromosome);
        TestChromosome restored = (TestChromosome) deserialize(bytes);

        Assertions.assertNotNull(restored);
        Assertions.assertNotNull(restored.getTestCase());
        Assertions.assertEquals(test.size(), restored.getTestCase().size(),
                "Round-tripped test case must have the same number of statements");
    }

    @Test
    public void roundTripsWithExceptionCoverageFitness() throws Exception {
        // This mirrors what happens during search when a test throws or
        // times out: MultiCriteriaManager creates ExceptionCoverageTestFitness
        // goals and registers them in the chromosome's fitness map. Each goal
        // holds a GenericClass (the exception class), which then travels via
        // RMI when the master collects statistics on the chromosome.
        DefaultTestCase test = buildSampleTest();
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(test);

        ExceptionCoverageTestFitness goal1 = new ExceptionCoverageTestFitness(
                "edu.mscd.cs.jclo.JCLO",
                "parse([Ljava/lang/String;)V",
                IllegalArgumentException.class,
                ExceptionCoverageTestFitness.ExceptionType.EXPLICIT);
        chromosome.setFitness(goal1, 0.0);
        chromosome.setCoverage(goal1, 1.0);

        ExceptionCoverageTestFitness goal2 = new ExceptionCoverageTestFitness(
                "edu.mscd.cs.jclo.JCLO",
                "makeObject(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;",
                NumberFormatException.class,
                ExceptionCoverageTestFitness.ExceptionType.IMPLICIT);
        chromosome.setFitness(goal2, 0.0);
        chromosome.setCoverage(goal2, 1.0);

        byte[] bytes = serialize(chromosome);
        TestChromosome restored = (TestChromosome) deserialize(bytes);

        Assertions.assertNotNull(restored);
        Assertions.assertNotNull(restored.getTestCase());
    }

    @Test
    public void roundTripsWithObjectArrayParam() throws Exception {
        // Mirror the JCLO pattern: a constructor that takes a String[] and
        // method calls that return primitives/arrays. The reader-side log
        // showed [Ljava.lang.String;, [I, [S, [J etc. failing -- those are
        // exactly the kinds of references this test produces.
        DefaultTestCase test = buildArrayHeavyTest();
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(test);

        byte[] bytes = serialize(chromosome);
        TestChromosome restored = (TestChromosome) deserialize(bytes);

        Assertions.assertNotNull(restored);
        Assertions.assertEquals(test.size(), restored.getTestCase().size());
    }

    private static DefaultTestCase buildSampleTest() throws Exception {
        DefaultTestCase test = new DefaultTestCase();

        // String literal
        StringPrimitiveStatement sps = new StringPrimitiveStatement(test, "hello");
        test.addStatement(sps);

        // int, short, long literals — match JCLO primitives in failure set
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new ShortPrimitiveStatement(test, (short) 9));
        test.addStatement(new LongPrimitiveStatement(test, 11L));

        // Object()
        Constructor<?> objCtor = Object.class.getConstructor();
        ConstructorStatement cs = new ConstructorStatement(test,
                new GenericConstructor(objCtor, Object.class),
                new ArrayList<>());
        test.addStatement(cs);

        // String.length() — returns int
        Method strLength = String.class.getMethod("length");
        VariableReference stringRef = sps.getReturnValue();
        MethodStatement ms = new MethodStatement(test,
                new GenericMethod(strLength, String.class),
                stringRef,
                Collections.emptyList());
        test.addStatement(ms);

        // List<Long> via Collections.singletonList (parameterized)
        Method singletonList = Collections.class.getMethod("singletonList", Object.class);
        LongPrimitiveStatement longArg = new LongPrimitiveStatement(test, 42L);
        test.addStatement(longArg);
        MethodStatement listMs = new MethodStatement(test,
                new GenericMethod(singletonList, Collections.class),
                null,
                Collections.singletonList(longArg.getReturnValue()));
        test.addStatement(listMs);

        return test;
    }

    private static DefaultTestCase buildArrayHeavyTest() throws Exception {
        DefaultTestCase test = new DefaultTestCase();

        // Mimic JCLO: callers that pass String[] to methods that return primitives/arrays.
        // Arrays.asList(String...) — returns List<String>, takes String[]
        StringPrimitiveStatement sps = new StringPrimitiveStatement(test, "foo");
        test.addStatement(sps);
        StringPrimitiveStatement sps2 = new StringPrimitiveStatement(test, "bar");
        test.addStatement(sps2);

        Method asList = Arrays.class.getMethod("asList", Object[].class);
        // Build an ArrayStatement-like wrapper? Skip — keep it simple.
        // Instead, call String.split(",") which returns String[]
        Method split = String.class.getMethod("split", String.class);
        MethodStatement splitMs = new MethodStatement(test,
                new GenericMethod(split, String.class),
                sps.getReturnValue(),
                Collections.singletonList(sps2.getReturnValue()));
        test.addStatement(splitMs);

        // Integer.parseInt(String) — returns int
        Method parseInt = Integer.class.getMethod("parseInt", String.class);
        MethodStatement parseMs = new MethodStatement(test,
                new GenericMethod(parseInt, Integer.class),
                null,
                Collections.singletonList(sps.getReturnValue()));
        test.addStatement(parseMs);

        return test;
    }

    private static byte[] serialize(Object o) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(o);
        }
        return baos.toByteArray();
    }

    private static Object deserialize(byte[] bytes) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return ois.readObject();
        }
    }
}
