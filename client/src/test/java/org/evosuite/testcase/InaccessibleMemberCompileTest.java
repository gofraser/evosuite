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

import org.evosuite.Properties;
import org.evosuite.testcase.accessfixture.PropertyTable;
import org.evosuite.testcase.accessfixture.TableBase;
import org.evosuite.testcase.accessfixture.other.Exposer;
import org.evosuite.testcase.accessfixture.other.OpenTableSwt;
import org.evosuite.testcase.accessfixture.other.TableSwt;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.numeric.BooleanPrimitiveStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generated tests live in the package of the class under test and are not subclasses of
 * anything, so javac rejects any use of a member or type that is private, or protected in
 * another package. Each case drives the real {@link TestCodeVisitor} over a hand-built test
 * and compiles the rendered statements with javac, in the package
 * {@code org.evosuite.testcase.accessfixture} that the fixtures treat as the test's package.
 */
public class InaccessibleMemberCompileTest {

    private static final String TEST_PACKAGE = TableBase.class.getPackage().getName();

    private String originalTargetClass;
    private String originalClassPrefix;
    private String originalSubPrefix;
    private String originalProjectPrefix;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        originalClassPrefix = Properties.CLASS_PREFIX;
        originalSubPrefix = Properties.SUB_PREFIX;
        originalProjectPrefix = Properties.PROJECT_PREFIX;

        Properties.TARGET_CLASS = TableBase.class.getName();
        Properties.resetTargetClass();
        Properties.CLASS_PREFIX = TEST_PACKAGE;
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.CLASS_PREFIX = originalClassPrefix;
        Properties.SUB_PREFIX = originalSubPrefix;
        Properties.PROJECT_PREFIX = originalProjectPrefix;
        Properties.resetTargetClass();
    }

    // -- private nested classes as type arguments ---------------------------------------------

    private VariableReference addExposer(TestCase tc) throws Exception {
        return tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Exposer.class.getConstructor(), Exposer.class),
                Collections.emptyList()));
    }

    private VariableReference call(TestCase tc, Class<?> owner, String name, VariableReference callee,
                                   Class<?>... parameterTypes) throws Exception {
        return tc.addStatement(new MethodStatement(tc,
                new GenericMethod(owner.getDeclaredMethod(name, parameterTypes), owner), callee,
                Collections.emptyList()));
    }

    @Test
    public void privateNestedTypeArgumentIsRenderedAsWildcard() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference exposer = addExposer(tc);
        VariableReference map = call(tc, Exposer.class, "hiddenMap", exposer);
        // A follow-up call on the declared variable must still compile.
        call(tc, java.util.Map.class, "size", map);

        String code = render(tc);
        assertFalse(code.matches("(?s).*\\bHidden\\b.*"), code);
        assertCompiles(code);
    }

    @Test
    public void privateNestedTypeArgumentUsesAccessibleSuperclassBound() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference exposer = addExposer(tc);
        VariableReference list = call(tc, Exposer.class, "hiddenList", exposer);
        call(tc, java.util.List.class, "size", list);

        String code = render(tc);
        assertFalse(code.contains("HiddenChild"), code);
        assertCompiles(code);
    }

    @Test
    public void hiddenTypeNestedInsideTypeArgumentIsWildcardedAtTheTopLevel() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference exposer = addExposer(tc);
        VariableReference nested = call(tc, Exposer.class, "nestedHidden", exposer);
        call(tc, java.util.List.class, "size", nested);

        String code = render(tc);
        assertFalse(code.matches("(?s).*\\bHidden\\b.*"), code);
        assertCompiles(code);
    }

    private void assignField(TestCase tc, VariableReference owner, String fieldName,
                             VariableReference value) throws Exception {
        java.lang.reflect.Field field = Exposer.class.getDeclaredField(fieldName);
        tc.addStatement(new org.evosuite.testcase.statements.AssignmentStatement(tc,
                new org.evosuite.testcase.variable.FieldReference(tc,
                        new org.evosuite.utils.generic.GenericField(field, Exposer.class), owner),
                value));
    }

    @Test
    public void hiddenTypeArgumentVariableCanBeAssignedToFieldOfRealType() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference exposer = addExposer(tc);
        VariableReference map = call(tc, Exposer.class, "hiddenMap", exposer);
        assignField(tc, exposer, "dict", map);

        assertCompiles(render(tc));
    }

    @Test
    public void hiddenTypeArgumentVariableCanBeAssignedToSupertypeFieldWithCast() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference exposer = addExposer(tc);
        VariableReference map = call(tc, Exposer.class, "hiddenNestedMap", exposer);
        assignField(tc, exposer, "m", map);

        String code = render(tc);
        assertFalse(code.contains("?"), code);
        assertCompiles(code);
    }

    @Test
    public void hiddenTypeArgumentVariableCanBePassedAsArgument() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference exposer = addExposer(tc);
        VariableReference map = call(tc, Exposer.class, "hiddenMap", exposer);
        tc.addStatement(new MethodStatement(tc,
                new GenericMethod(Exposer.class.getDeclaredMethod("takeDict",
                        java.util.LinkedHashMap.class), Exposer.class), exposer,
                Collections.singletonList(map)));

        assertCompiles(render(tc));
    }

    private static Class<?> hiddenClass() throws Exception {
        return Class.forName(Exposer.class.getName() + "$Hidden");
    }

    private static Class<?> hiddenChildClass() throws Exception {
        return Class.forName(Exposer.class.getName() + "$HiddenChild");
    }

    private VariableReference callOnDict(TestCase tc, VariableReference dict, String name,
                                         Class<?>... parameterTypes) throws Exception {
        return callOnDict(tc, dict, hiddenClass(), name, parameterTypes);
    }

    private VariableReference callOnDict(TestCase tc, VariableReference dict, Class<?> valueType,
                                         String name, Class<?>... parameterTypes) throws Exception {
        java.lang.reflect.Type owner = org.apache.commons.lang3.reflect.TypeUtils.parameterize(
                java.util.LinkedHashMap.class, String.class, valueType);
        java.lang.reflect.Method method = java.util.HashMap.class.getMethod(name, parameterTypes);
        return tc.addStatement(new MethodStatement(tc, new GenericMethod(method, owner), dict,
                parameterTypes.length == 0 ? Collections.<VariableReference>emptyList()
                        : Collections.singletonList(tc.addStatement(
                        new StringPrimitiveStatement(tc, "k")))));
    }

    @Test
    public void valueReadFromRawReceiverOfHiddenTypeCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference exposer = addExposer(tc);
        VariableReference dict = call(tc, Exposer.class, "hiddenMap", exposer);
        VariableReference value = callOnDict(tc, dict, "get", Object.class);
        call(tc, Object.class, "hashCode", value);

        String code = render(tc);
        assertCompiles(code);
    }

    @Test
    public void valueOfHiddenSubclassReadFromRawReceiverIsCastToAccessibleSuperclass() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference exposer = addExposer(tc);
        VariableReference dict = call(tc, Exposer.class, "hiddenMap", exposer);
        VariableReference value = callOnDict(tc, dict, hiddenChildClass(), "get", Object.class);
        call(tc, java.util.Date.class, "getTime", value);

        String code = render(tc);
        assertTrue(code.contains("Date date0 = (Date)") || code.contains("(Date)"), code);
        assertCompiles(code);
    }

    @Test
    public void viewsOfRawReceiverOfHiddenTypeCompile() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference exposer = addExposer(tc);
        VariableReference dict = call(tc, Exposer.class, "hiddenMap", exposer);
        call(tc, java.util.Set.class, "size", callOnDict(tc, dict, "keySet"));
        call(tc, java.util.Set.class, "size", callOnDict(tc, dict, "entrySet"));
        call(tc, java.util.Collection.class, "size", callOnDict(tc, dict, "values"));

        String code = render(tc);
        assertCompiles(code);
    }

    @Test
    public void valueReturnedByPrivateFieldReadAsRawReceiverIsCast() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference exposer = addExposer(tc);
        java.lang.reflect.Field field = Exposer.class.getDeclaredField("child");
        VariableReference child = new org.evosuite.testcase.variable.FieldReference(tc,
                new org.evosuite.utils.generic.GenericField(field, Exposer.class), exposer);
        java.lang.reflect.Type owner = org.apache.commons.lang3.reflect.TypeUtils.parameterize(
                java.util.Hashtable.class, String.class, Exposer.class);
        java.lang.reflect.Method put = java.util.Hashtable.class.getMethod("put", Object.class,
                Object.class);
        VariableReference previous = tc.addStatement(new MethodStatement(tc,
                new GenericMethod(put, owner), child, Arrays.<VariableReference>asList(
                        tc.addStatement(new StringPrimitiveStatement(tc, "k")), exposer)));
        call(tc, Exposer.class, "hiddenMap", previous);

        String code = render(tc);
        assertTrue(code.contains("PrivateAccess.getVariable"), code);
        assertCompiles(code);
    }

    // -- protected methods overridden in another package --------------------------------------

    private String renderSetSort(Class<?> receiverClass) throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference table = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(receiverClass.getConstructor(), receiverClass),
                Collections.emptyList()));
        VariableReference column = tc.addStatement(new StringPrimitiveStatement(tc, "name"));
        VariableReference ascending = tc.addStatement(new BooleanPrimitiveStatement(tc, true));
        tc.addStatement(new MethodStatement(tc,
                new GenericMethod(TableBase.class.getDeclaredMethod("setSort", String.class,
                        boolean.class), TableBase.class),
                table, Arrays.asList(column, ascending)));
        return render(tc);
    }

    @Test
    public void protectedMethodOverriddenInOtherPackageIsCalledThroughDeclaringClass() throws Exception {
        String code = renderSetSort(TableSwt.class);
        assertTrue(code.contains("((TableBase)"), code);
        assertCompiles(code);
    }

    @Test
    public void publicOverrideNeedsNoCast() throws Exception {
        String code = renderSetSort(OpenTableSwt.class);
        assertFalse(code.contains("(TableBase)"), code);
        assertCompiles(code);
    }

    // -- private inner class of a generic class ----------------------------------------------

    private String renderGetProperty(java.lang.reflect.Type tableType) throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference table = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(PropertyTable.class.getConstructor(), tableType),
                Collections.emptyList()));
        VariableReference name = tc.addStatement(new StringPrimitiveStatement(tc, "name"));
        VariableReference property = tc.addStatement(new MethodStatement(tc,
                new GenericMethod(PropertyTable.class.getDeclaredMethod("getProperty", String.class),
                        tableType), table, Collections.singletonList(name)));
        call(tc, Object.class, "hashCode", property);
        return render(tc);
    }

    @Test
    public void privateInnerClassOfParameterizedOwnerIsNotNamed() throws Exception {
        String code = renderGetProperty(org.apache.commons.lang3.reflect.TypeUtils.parameterize(
                PropertyTable.class, String.class));
        assertFalse(code.matches("(?s).*PropertyTable(<\\w+>)?\\.Property\\b.*"), code);
        assertCompiles(code);
    }

    @Test
    public void privateInnerClassOfRawOwnerIsNotNamed() throws Exception {
        String code = renderGetProperty(PropertyTable.class);
        assertFalse(code.matches("(?s).*PropertyTable(<\\w+>)?\\.Property\\b.*"), code);
        assertCompiles(code);
    }

    // -- javac grounding ----------------------------------------------------------------------

    private static String render(TestCase tc) {
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        StringBuilder imports = new StringBuilder();
        for (Class<?> imported : visitor.getImports()) {
            String canonical = imported.getCanonicalName();
            if (canonical != null) {
                imports.append("import ").append(canonical).append(";\n");
            }
        }
        return imports + "\n" + visitor.getCode();
    }

    private static void assertCompiles(String importsAndCode) {
        int split = importsAndCode.indexOf("\n\n");
        String imports = importsAndCode.substring(0, split);
        String code = importsAndCode.substring(split + 2);
        String className = "InaccessibleMemberProbe" + System.nanoTime();
        StringBuilder source = new StringBuilder();
        source.append("package ").append(TEST_PACKAGE).append(";\n").append(imports).append("\n");
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-inaccessible-member-compile", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
