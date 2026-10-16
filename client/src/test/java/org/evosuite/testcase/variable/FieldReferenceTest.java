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
package org.evosuite.testcase.variable;

import com.examples.with.different.packagename.StaticCounterHolder;
import com.examples.with.different.packagename.StaticCounterHolderOwner;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.utils.ParameterizedTypeImpl;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FieldReferenceTest {

    private static class BaseLexem {}
    private static class ExecLexem extends BaseLexem {}
    private static class AdvLexem extends ExecLexem {}
    private static class Holder<T extends ExecLexem> {
        public ArrayList<T> fieldScope = new ArrayList<>();
    }

    @Test
    public void testGetObjectHandlesInaccessibleStaticField() throws Exception {
        assertThrows(CodeUnderTestException.class, () -> {
            Class<?> clazz;
            Field staticField = null;
            try {
                clazz = Class.forName("sun.print.RasterPrinterJob");
                for (Field candidate : clazz.getDeclaredFields()) {
                    if (Modifier.isStatic(candidate.getModifiers())) {
                        staticField = candidate;
                        break;
                    }
                }
            } catch (ClassNotFoundException e) {
                Assumptions.assumeTrue(false, "Assume failed: " + e.getMessage());
                return;
            }

            if (staticField == null) {
                Assumptions.assumeTrue(false, "No static field available");
                return;
            }

            FieldReference ref = new FieldReference(new DefaultTestCase(), new GenericField(staticField, clazz));
            ref.getObject(new Scope());
        });
    }

    @Test
    public void testReplacingSourceRebindsGenericFieldType() throws Exception {
        DefaultTestCase testCase = new DefaultTestCase();
        Field field = Holder.class.getField("fieldScope");

        VariableReference advHolder = new VariableReferenceImpl(testCase, holderOf(AdvLexem.class));
        VariableReference execHolder = new VariableReferenceImpl(testCase, holderOf(ExecLexem.class));

        FieldReference ref = new FieldReference(testCase, new GenericField(field, advHolder.getGenericClass()), advHolder);
        assertTrue(ref.getType().getTypeName().contains("AdvLexem"));

        ref.setAdditionalVariableReference(execHolder);
        assertTrue(ref.getType().getTypeName().contains("ExecLexem"));
    }

    public static class Named {
        public String name;
    }

    /**
     * A value of the SUT whose toString() cannot be called.
     */
    public static class Hostile {
        @Override
        public String toString() {
            throw new IllegalStateException("toString of the SUT was called");
        }
    }

    @Test
    public void testSetObjectOfMismatchingValueDoesNotCallToStringOfTheSut() throws Exception {
        DefaultTestCase testCase = new DefaultTestCase();
        VariableReference source = testCase.addStatement(new NullStatement(testCase, Named.class));
        Scope scope = new Scope();
        scope.setObject(source, new Named());
        FieldReference ref = new FieldReference(testCase,
                new GenericField(Named.class.getField("name"), Named.class), source);

        assertThrows(IllegalArgumentException.class, () -> ref.setObject(scope, new Hostile()));
    }

    @Test
    public void testSetObjectOnUnrelatedSourceDoesNotCallToStringOfTheSut() throws Exception {
        DefaultTestCase testCase = new DefaultTestCase();
        VariableReference source = testCase.addStatement(new NullStatement(testCase, Named.class));
        Scope scope = new Scope();
        scope.setObject(source, new Hostile());
        FieldReference ref = new FieldReference(testCase,
                new GenericField(Named.class.getField("name"), Named.class), source);

        assertThrows(CodeUnderTestException.class, () -> ref.setObject(scope, new Hostile()));
    }

    private static Type holderOf(Class<?> typeArgument) {
        return new ParameterizedTypeImpl(Holder.class, new Type[]{typeArgument}, null);
    }

    /**
     * Defines its own copy of {@link StaticCounterHolderOwner} and {@link StaticCounterHolder}, as the
     * re-instrumenting loader of assertion generation does for SUT classes.
     */
    private static class ReloadingClassLoader extends ClassLoader {
        ReloadingClassLoader() {
            super(FieldReferenceTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(StaticCounterHolderOwner.class.getName())
                    && !name.equals(StaticCounterHolder.class.getName())) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) {
                    return loaded;
                }
                String resource = name.replace('.', '/') + ".class";
                try (InputStream in = getParent().getResourceAsStream(resource)) {
                    byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        }
    }

    /**
     * A static field read through an instance expression ({@code owner.holder.counter})
     * keeps that expression as its source, and copying the reference copies it. Changing
     * the class loader must therefore move the source along, or the next clone of the
     * test pairs a field of the old loader with a variable of the new one.
     */
    @Test
    public void testChangeClassLoaderMovesSourceOfStaticField() throws Exception {
        DefaultTestCase testCase = new DefaultTestCase();
        VariableReference owner = testCase.addStatement(
                new NullStatement(testCase, StaticCounterHolderOwner.class));
        FieldReference holder = new FieldReference(testCase,
                new GenericField(StaticCounterHolderOwner.class.getField("holder"),
                        StaticCounterHolderOwner.class), owner);
        FieldReference counter = new FieldReference(testCase,
                new GenericField(StaticCounterHolder.class.getField("counter"),
                        StaticCounterHolder.class), holder);
        testCase.addStatement(new MethodStatement(testCase,
                new GenericMethod(Integer.class.getMethod("valueOf", int.class), Integer.class),
                null, Collections.singletonList(counter)));

        ClassLoader loader = new ReloadingClassLoader();
        testCase.changeClassLoader(loader);

        TestCase copy = assertDoesNotThrow(testCase::clone);
        assertSame(loader, holder.getField().getField().getDeclaringClass().getClassLoader());
        FieldReference copiedCounter = (FieldReference) copy.getStatement(1)
                .getVariableReferences().stream()
                .filter(v -> v instanceof FieldReference)
                .findFirst().orElseThrow(AssertionError::new);
        assertSame(loader, copiedCounter.getField().getField().getDeclaringClass().getClassLoader());
    }
}
