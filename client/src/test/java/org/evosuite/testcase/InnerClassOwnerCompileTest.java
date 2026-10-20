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

import org.apache.commons.lang3.reflect.TypeUtils;
import org.evosuite.Properties;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * For {@code LazyList.LazyListIterator}: a non-static inner class of a generic
 * outer class was declared as the raw {@code LazyList.LazyListIterator}, so {@code next()}
 * returned Object and the assignment to a typed variable did not compile. The declaration
 * has to carry the owner's type arguments ({@code LazyList<Integer>.LazyListIterator}).
 */
public class InnerClassOwnerCompileTest {

    public static class Outer<E> {
        public E value;

        public class Inner implements Iterator<E> {
            public boolean hasNext() {
                return false;
            }

            public E next() {
                return value;
            }

            public void set(E element) {
                value = element;
            }
        }
    }

    public static class Derived extends Outer<String> {
    }

    public static class Holder<U> {
        public static class NestedDerived extends Outer<String> {
        }
    }

    public static Outer.Inner rawInner() {
        return new Outer<String>().new Inner();
    }

    public static Outer<? extends CharSequence>.Inner charsInner() {
        return new Outer<String>().new Inner();
    }

    public static Outer<Integer>.Inner integerInner() {
        return new Outer<Integer>().new Inner();
    }

    public static int useChars(CharSequence value) {
        return value == null ? 0 : value.length();
    }

    public static int useString(String value) {
        return value == null ? 0 : value.length();
    }

    public static int use(Integer value) {
        return value == null ? 0 : value;
    }

    private String originalTargetClass;
    private double originalObjectReuseProbability;
    private long originalRandomSeed;

    @BeforeEach
    public void setUp() {
        originalRandomSeed = Randomness.getSeed();
        Randomness.setSeed(481516L);
        originalTargetClass = Properties.TARGET_CLASS;
        originalObjectReuseProbability = Properties.OBJECT_REUSE_PROBABILITY;
        Properties.TARGET_CLASS = InnerClassOwnerCompileTest.class.getName();
        Properties.OBJECT_REUSE_PROBABILITY = 1.0;
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Randomness.setSeed(originalRandomSeed);
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.OBJECT_REUSE_PROBABILITY = originalObjectReuseProbability;
        Properties.resetTargetClass();
    }

    @Test
    public void testNextOfInnerClassOfGenericOuterCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        Type outerType = TypeUtils.parameterize(Outer.class, Integer.class);
        VariableReference outer = addOuter(tc, outerType);
        Type innerType = TypeUtils.parameterizeWithOwner(outerType, Outer.Inner.class);
        VariableReference inner = addInner(tc, innerType, outer);
        GenericMethod next = new GenericMethod(Outer.Inner.class.getMethod("next"), innerType);
        VariableReference element = tc.addStatement(
                new MethodStatement(tc, next, inner, Arrays.asList()));
        GenericMethod use = new GenericMethod(
                InnerClassOwnerCompileTest.class.getMethod("use", Integer.class),
                InnerClassOwnerCompileTest.class);
        tc.addStatement(new MethodStatement(tc, use, null, Arrays.asList(element)));
        assertCompiles(tc);
    }

    /**
     * javac types {@code outer.new Inner()} from the declared type of {@code outer}; the
     * constructor model can carry a different instantiation of the outer's type parameter
     * (a LazyList<Integer> enclosing a model owner of LazyList<String>).
     */
    @Test
    public void testInnerDeclarationFollowsTheEnclosingInstanceType() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference outer = addOuter(tc, TypeUtils.parameterize(Outer.class, String.class));
        Type modelOwner = TypeUtils.parameterizeWithOwner(
                TypeUtils.parameterize(Outer.class, Integer.class), Outer.Inner.class);
        addInner(tc, modelOwner, outer);
        assertCompiles(tc);
    }

    @Test
    public void testNextThroughInnerDeclaredAgainstTheEnclosingInstanceCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference outer = addOuter(tc, TypeUtils.parameterize(Outer.class, String.class));
        Type modelOwner = TypeUtils.parameterizeWithOwner(
                TypeUtils.parameterize(Outer.class, Integer.class), Outer.Inner.class);
        VariableReference inner = addInner(tc, modelOwner, outer);
        GenericMethod next = new GenericMethod(Outer.Inner.class.getMethod("next"), modelOwner);
        VariableReference element = tc.addStatement(
                new MethodStatement(tc, next, inner, Arrays.asList()));
        GenericMethod use = new GenericMethod(
                InnerClassOwnerCompileTest.class.getMethod("use", Integer.class),
                InnerClassOwnerCompileTest.class);
        tc.addStatement(new MethodStatement(tc, use, null, Arrays.asList(element)));
        assertCompiles(tc);
    }

    /**
     * The enclosing instance's class only inherits the outer's type arguments
     * (Derived extends Outer&lt;String&gt;); a model owner of Outer&lt;Integer&gt;.Inner
     * disagrees with them and must not be declared.
     */
    @Test
    public void testInnerOfSubclassOfInstantiatedOuterDisagreeingModelCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference derived = addDerived(tc);
        Type modelOwner = TypeUtils.parameterizeWithOwner(
                TypeUtils.parameterize(Outer.class, Integer.class), Outer.Inner.class);
        addInner(tc, modelOwner, derived);
        assertCompiles(tc);
    }

    @Test
    public void testInnerOfSubclassOfInstantiatedOuterAgreeingModelKeepsTypeArgument()
            throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference derived = addDerived(tc);
        Type modelOwner = TypeUtils.parameterizeWithOwner(
                TypeUtils.parameterize(Outer.class, String.class), Outer.Inner.class);
        addInner(tc, modelOwner, derived);
        String code = assertCompiles(tc);
        assertTrue(code.contains("Outer<String>.Inner"), code);
    }

    /**
     * gentyref treats a static nested class of a generic outer (Holder.NestedDerived) as raw
     * and erases its supertype to Outer, which would declare the raw Outer.Inner and make
     * {@code String value = inner.next()} fail to compile.
     */
    @Test
    public void testInnerOfStaticNestedSubclassOfGenericHolderKeepsTypeArgument() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference derived = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Holder.NestedDerived.class.getConstructor(),
                        Holder.NestedDerived.class),
                Arrays.asList()));
        Type innerType = TypeUtils.parameterizeWithOwner(
                TypeUtils.parameterize(Outer.class, String.class), Outer.Inner.class);
        VariableReference inner = addInner(tc, innerType, derived);
        GenericMethod next = new GenericMethod(Outer.Inner.class.getMethod("next"), innerType);
        VariableReference element = tc.addStatement(
                new MethodStatement(tc, next, inner, Arrays.asList()));
        GenericMethod use = new GenericMethod(
                InnerClassOwnerCompileTest.class.getMethod("useString", String.class),
                InnerClassOwnerCompileTest.class);
        tc.addStatement(new MethodStatement(tc, use, null, Arrays.asList(element)));
        String code = assertCompiles(tc);
        assertTrue(code.contains("Outer<String>.Inner"), code);
    }

    @Test
    public void testFactoryBindsInnerOwnerToEnclosingVariableAndClone() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference outer = addOuter(tc, TypeUtils.parameterize(Outer.class, Integer.class));

        // A generator can carry a separately instantiated owner. The reflective constructor's
        // synthetic Outer parameter is erased, so only the actual argument can tell us that this
        // call is Outer<Integer>.Inner.
        Type modelOwner = TypeUtils.parameterizeWithOwner(
                TypeUtils.parameterize(Outer.class, String.class), Outer.Inner.class);
        GenericConstructor constructor = new GenericConstructor(
                Outer.Inner.class.getConstructor(Outer.class), modelOwner);
        VariableReference inner = TestFactory.getInstance().addConstructor(
                tc, constructor, tc.size(), 0);
        GenericMethod next = new GenericMethod(Outer.Inner.class.getMethod("next"),
                inner.getGenericClass());
        VariableReference element = TestFactory.getInstance().addMethodFor(
                tc, inner, next, tc.size(), new GenerationContext());
        GenericMethod use = new GenericMethod(
                InnerClassOwnerCompileTest.class.getMethod("use", Integer.class),
                InnerClassOwnerCompileTest.class);
        tc.addStatement(new MethodStatement(tc, use, null, Arrays.asList(element)));

        assertCompiles(tc);
        DefaultTestCase clone = tc.clone();
        assertCompiles(clone);

        Statement clonedInner = clone.getStatement(inner.getStPosition());
        TestFactory.getInstance().changeCall(clone, clonedInner,
                new GenericConstructor(Outer.Inner.class.getConstructor(Outer.class), modelOwner));
        assertCompiles(clone);
    }

    /**
     * Mutating {@code Outer<Integer>.Inner inner = o1.new Inner()} may pick the
     * {@code Outer<String>} instance; the variable must not be retyped under the existing
     * {@code inner.set(int0)}. Repeated so the random draw selects o2 at least once.
     */
    @Test
    public void testChangeCallDoesNotRetypeInnerUnderExistingUses() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();
        Type intOuter = TypeUtils.parameterize(Outer.class, Integer.class);
        addOuter(tc, intOuter);
        addOuter(tc, TypeUtils.parameterize(Outer.class, String.class));
        Type intInner = TypeUtils.parameterizeWithOwner(intOuter, Outer.Inner.class);
        VariableReference inner = addInner(tc, intInner, tc.getReturnValue(0));
        VariableReference int0 = tc.addStatement(new IntPrimitiveStatement(tc, 3));
        GenericMethod set = new GenericMethod(
                Outer.Inner.class.getMethod("set", Object.class), intInner);
        tc.addStatement(new MethodStatement(tc, set, inner, Arrays.asList(int0)));
        assertCompiles(tc);

        GenericConstructor ctor = new GenericConstructor(
                Outer.Inner.class.getConstructor(Outer.class), intInner);
        for (int i = 0; i < 30; i++) {
            DefaultTestCase clone = tc.clone();
            try {
                TestFactory.getInstance().changeCall(clone,
                        clone.getStatement(inner.getStPosition()), ctor);
            } catch (ConstructionFailedException expected) {
                // refusing a disagreeing enclosing instance is acceptable
            }
            assertCompiles(clone);
        }
    }

    @Test
    public void testChangeCallAcceptsRawInnerReturnVariable() throws Exception {
        assertSafeRebinding("rawInner", null);
    }

    @Test
    public void testChangeCallAcceptsBoundedWildcardInnerReturnVariable() throws Exception {
        assertSafeRebinding("charsInner", "useChars");
    }

    private static void assertSafeRebinding(String factoryName, String consumerName)
            throws Exception {
        DefaultTestCase tc = new DefaultTestCase();
        Type stringOuter = TypeUtils.parameterize(Outer.class, String.class);
        VariableReference outer = addOuter(tc, stringOuter);
        GenericMethod factory = new GenericMethod(
                InnerClassOwnerCompileTest.class.getMethod(factoryName),
                InnerClassOwnerCompileTest.class);
        MethodStatement creation = new MethodStatement(tc, factory, null, Arrays.asList());
        if (consumerName == null) {
            // The exact raw model differs from GenericClassFactory.get(Class), which adds
            // synthetic E to the enclosing Outer. Keep those unresolved E models strict.
            creation = new MethodStatement(tc, factory, null, Arrays.asList(),
                    new VariableReferenceImpl(tc, GenericClassFactory.get(
                            (Type) Outer.Inner.class, Outer.Inner.class)));
        }
        VariableReference inner = tc.addStatement(creation);
        Type returnType = inner.getType();
        if (consumerName == null) {
            VariableReference int0 = tc.addStatement(new IntPrimitiveStatement(tc, 3));
            tc.addStatement(new MethodStatement(tc,
                    new GenericMethod(Outer.Inner.class.getMethod("set", Object.class), inner.getGenericClass()),
                    inner, Arrays.asList(int0)));
        }
        VariableReference element = tc.addStatement(new MethodStatement(tc,
                new GenericMethod(Outer.Inner.class.getMethod("next"), returnType),
                inner, Arrays.asList()));
        if (consumerName != null) {
            tc.addStatement(new MethodStatement(tc, new GenericMethod(
                    InnerClassOwnerCompileTest.class.getMethod(consumerName, CharSequence.class),
                    InnerClassOwnerCompileTest.class), null, Arrays.asList(element)));
        }
        assertCompiles(tc);

        // Only one enclosing candidate exists. The mutation's proposed Integer owner must
        // bind to that String variable, which fits a raw or ? extends CharSequence result.
        Type proposedOwner = TypeUtils.parameterizeWithOwner(
                TypeUtils.parameterize(Outer.class, Integer.class), Outer.Inner.class);
        GenericConstructor replacement = new GenericConstructor(
                Outer.Inner.class.getConstructor(Outer.class), proposedOwner);
        assertCompiles(tc.clone());
        TestFactory.getInstance().changeCall(tc, tc.getStatement(inner.getStPosition()), replacement);
        ConstructorStatement changed = (ConstructorStatement) tc.getStatement(inner.getStPosition());
        assertEquals(returnType, changed.getReturnValue().getType());
        assertEquals(TypeUtils.parameterizeWithOwner(stringOuter, Outer.Inner.class),
                changed.getConstructor().getOwnerType());
        assertEquals(outer.getStPosition(), changed.getParameterReferences().get(0).getStPosition());
        assertCompiles(tc);
        DefaultTestCase clone = tc.clone();
        assertEquals(returnType, clone.getReturnValue(inner.getStPosition()).getType());
        assertCompiles(clone);
        TestFactory.getInstance().changeCall(clone,
                clone.getStatement(inner.getStPosition()), replacement);
        assertCompiles(clone);
        assertCompiles(clone.clone());
    }

    @Test
    public void testChangeCallRejectsConcreteOwnerMismatchWithOnlyForeignCandidate()
            throws Exception {
        DefaultTestCase tc = new DefaultTestCase();
        addOuter(tc, TypeUtils.parameterize(Outer.class, String.class));
        Type intInner = TypeUtils.parameterizeWithOwner(
                TypeUtils.parameterize(Outer.class, Integer.class), Outer.Inner.class);
        VariableReference inner = tc.addStatement(new MethodStatement(tc,
                new GenericMethod(InnerClassOwnerCompileTest.class.getMethod("integerInner"),
                        InnerClassOwnerCompileTest.class), null, Arrays.asList()));
        VariableReference int0 = tc.addStatement(new IntPrimitiveStatement(tc, 3));
        tc.addStatement(new MethodStatement(tc,
                new GenericMethod(Outer.Inner.class.getMethod("set", Object.class), intInner),
                inner, Arrays.asList(int0)));
        assertCompiles(tc);
        DefaultTestCase clone = tc.clone();
        GenericConstructor replacement = new GenericConstructor(
                Outer.Inner.class.getConstructor(Outer.class), intInner);
        assertThrows(ConstructionFailedException.class, () -> TestFactory.getInstance().changeCall(
                clone, clone.getStatement(inner.getStPosition()), replacement));
        assertEquals(intInner, clone.getReturnValue(inner.getStPosition()).getType());
        assertCompiles(clone);
        assertCompiles(clone.clone());
    }

    private static VariableReference addDerived(TestCase tc) throws Exception {
        return tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Derived.class.getConstructor(), Derived.class),
                Arrays.asList()));
    }

    private static VariableReference addOuter(TestCase tc, Type outerType) throws Exception {
        return tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Outer.class.getConstructor(), outerType),
                Arrays.asList()));
    }

    private static VariableReference addInner(TestCase tc, Type innerType, VariableReference outer)
            throws Exception {
        return tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Outer.Inner.class.getConstructor(Outer.class), innerType),
                Arrays.asList(outer)));
    }

    private static String assertCompiles(TestCase tc) {
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        StringBuilder source = new StringBuilder("package org.evosuite.testcase;\n");
        for (Class<?> imported : visitor.getImports()) {
            String canonical = imported.getCanonicalName();
            if (canonical != null) {
                source.append("import ").append(canonical).append(";\n");
            }
        }
        String className = "InnerOwnerProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-inner-owner", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
        return code;
    }
}
