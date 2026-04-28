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

import com.examples.with.different.packagename.AbstractEnumInInnerClass;
import com.examples.with.different.packagename.AbstractEnumUser;
import com.examples.with.different.packagename.EnumInInnerClass;
import com.examples.with.different.packagename.EnumUser;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.evosuite.Properties;
import org.evosuite.assertion.EqualsAssertion;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ClassPrimitiveStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.EnumPrimitiveStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.UninterpretedStatement;
import org.evosuite.testcase.statements.numeric.CharPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.NullReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.Person;
import org.evosuite.utils.generic.WildcardTypeImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.nio.charset.StandardCharsets;
import java.awt.HeadlessException;
import javax.swing.JMenuItem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Iterator;
import java.util.Set;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Created by Andrea Arcuri on 02/07/15.
 */
public class TestCodeVisitorTest {

    public static <T extends FakeAbstractClass> T foo(T obj) {
        return obj;
    }

    public static <T> T bar(T obj) {
        return obj;
    }

    public static void consumeLinkedListIntegerClass(Class<LinkedList<Integer>> cls) {
        // no-op
    }

    public static class ClassWithGeneric<T extends FakeAbstractClass> {
        public T hello(T obj) {
            return obj;
        }
    }

    public static class FakeServlet extends FakeAbstractClass {
        private static final long serialVersionUID = 1L;

        public FakeServlet() {
        }
    }

    public abstract static class FakeAbstractClass {

    }

    public interface MessageHandler {
        String handle();
    }

    public static class MessageMultiplexer implements MessageHandler {
        @Override
        public String handle() {
            return "ok";
        }
    }

    public static class Country {
        public String bar = "bar";

        public static class CountryNameCode {
            public String foo = "foo";
        }
    }

    public static class SnippetNestedOwner {
        public enum ChangeEventType {
            USER
        }
    }

    public static class SnippetRelatedOwner {
        public SnippetRelatedOwner(SnippetDeepType deepType, java.sql.Timestamp timestamp) {
            // no-op
        }
    }

    public static class SnippetDeepType {
        public SnippetDeepType(java.security.CodeSource codeSource) {
            // no-op
        }
    }

    public static class SnippetPackageKnownType {
        // no-op
    }

    public static class SnippetPackageSiblingType {
        // no-op
    }

    public static class PublicTypeWithPackagePrivateCtor {
        PublicTypeWithPackagePrivateCtor(String value) {
            // no-op
        }
    }

    @Test
    public void testInnerClassWithNameSubset() {
        TestCodeVisitor visitor = new TestCodeVisitor();
        assertEquals("TestCodeVisitorTest", visitor.getClassName(getClass()));
        assertEquals("TestCodeVisitorTest.Country", visitor.getClassName(Country.class));
        assertEquals("TestCodeVisitorTest.Country.CountryNameCode", visitor.getClassName(Country.CountryNameCode.class));
    }

    @Test
    public void testGenerics_methodWithExtends() throws NoSuchMethodException, ConstructionFailedException {

        //first construct a test case for the Generic method
        TestCase tc = new DefaultTestCase();
        TestFactory.getInstance().addConstructor(tc,
                new GenericConstructor(FakeServlet.class.getDeclaredConstructor(), FakeServlet.class), 0, 0);
        VariableReference genericClass = TestFactory.getInstance().addConstructor(tc,
                new GenericConstructor(ClassWithGeneric.class.getDeclaredConstructor(), ClassWithGeneric.class), 1, 0);


        Method m = ClassWithGeneric.class.getDeclaredMethod("hello", FakeAbstractClass.class);
        GenericMethod gm = new GenericMethod(m, ClassWithGeneric.class);
        TestFactory.getInstance().addMethodFor(tc, genericClass, gm, 2);


        //Check if generic types were correctly analyzed/inferred
        Type[] types = gm.getParameterTypes();
        assertEquals(1, types.length); //only 1 input
        Type type = types[0];
        Assertions.assertNotNull(type);
        TypeVariable<?> tv = (TypeVariable<?>) type;
        assertEquals(1, tv.getBounds().length);

        Class<?> upper = (Class<?>) tv.getBounds()[0];
        assertEquals(FakeAbstractClass.class, upper);


        //Finally, visit the test
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor); //should not throw exception        
    }

    @Test
    public void testGenerics_staticMethod() throws NoSuchMethodException, ConstructionFailedException {

        //first construct a test case for the Generic method
        TestCase tc = new DefaultTestCase();
        TestFactory.getInstance().addConstructor(tc,
                new GenericConstructor(Object.class.getDeclaredConstructor(), Object.class), 0, 0);

        Method m = TestCodeVisitorTest.class.getDeclaredMethod("bar", Object.class);
        GenericMethod gm = new GenericMethod(m, TestCodeVisitorTest.class);
        TestFactory.getInstance().addMethod(tc, gm, 1, 0);


        //Check if generic types were correctly analyzed/inferred
        Type[] types = gm.getParameterTypes();

        assertEquals(1, types.length); //only 1 input
        Type type = types[0];
        Assertions.assertNotNull(type);
        WildcardTypeImpl wt = (WildcardTypeImpl) type;
        assertEquals(0, wt.getLowerBounds().length);
        assertEquals(1, wt.getUpperBounds().length);

        Class<?> upper = (Class<?>) wt.getUpperBounds()[0];
        assertEquals(Object.class, upper);

        //Finally, visit the test
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor); //should not throw exception
        System.out.println(visitor.getCode());
    }

    @Test
    public void testGenerics_staticMethodWithExtends() throws NoSuchMethodException, ConstructionFailedException {

        //first construct a test case for the Generic method
        TestCase tc = new DefaultTestCase();
        TestFactory.getInstance().addConstructor(tc,
                new GenericConstructor(FakeServlet.class.getDeclaredConstructor(), FakeServlet.class), 0, 0);

        Method m = TestCodeVisitorTest.class.getDeclaredMethod("foo", FakeAbstractClass.class);
        GenericMethod gm = new GenericMethod(m, TestCodeVisitorTest.class);
        TestFactory.getInstance().addMethod(tc, gm, 1, 0);


        //Check if generic types were correctly analyzed/inferred
        Type[] types = gm.getParameterTypes();
        assertEquals(1, types.length); //only 1 input
        Type type = types[0];
        Assertions.assertNotNull(type);
        WildcardTypeImpl wt = (WildcardTypeImpl) type;
        assertEquals(0, wt.getLowerBounds().length);
        assertEquals(1, wt.getUpperBounds().length);

        Class<?> upper = (Class<?>) wt.getUpperBounds()[0];
        assertEquals(Object.class, upper);

        //Finally, visit the test
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor); //should not throw exception
    }

    @Test
    public void testClashingImportNames() throws NoSuchMethodException, ConstructionFailedException {
        TestCase tc = new DefaultTestCase();
        TestFactory.getInstance().addConstructor(tc,
                new GenericConstructor(com.examples.with.different.packagename.otherpackage.ExampleWithInnerClass.class.getDeclaredConstructor(), com.examples.with.different.packagename.otherpackage.ExampleWithInnerClass.class), 0, 0);
        TestFactory.getInstance().addConstructor(tc,
                new GenericConstructor(com.examples.with.different.packagename.subpackage.ExampleWithInnerClass.class.getDeclaredConstructor(), com.examples.with.different.packagename.subpackage.ExampleWithInnerClass.class), 1, 0);
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        System.out.println(visitor.getCode());
        Set<Class<?>> imports = visitor.getImports();

        // Imported
        assertTrue(imports.contains(com.examples.with.different.packagename.otherpackage.ExampleWithInnerClass.class));

        // Not imported as the fully qualified name is used
        assertFalse(imports.contains(com.examples.with.different.packagename.subpackage.ExampleWithInnerClass.class));
        assertEquals("ExampleWithInnerClass", visitor.getClassName(com.examples.with.different.packagename.otherpackage.ExampleWithInnerClass.class));
        assertEquals("com.examples.with.different.packagename.subpackage.ExampleWithInnerClass", visitor.getClassName(com.examples.with.different.packagename.subpackage.ExampleWithInnerClass.class));
    }

    @Test
    public void testClashingImportNamesSubClasses() throws NoSuchMethodException, ConstructionFailedException {
        TestCase tc = new DefaultTestCase();
        TestFactory.getInstance().addConstructor(tc,
                new GenericConstructor(com.examples.with.different.packagename.otherpackage.ExampleWithInnerClass.Foo.class.getDeclaredConstructor(), com.examples.with.different.packagename.otherpackage.ExampleWithInnerClass.Foo.class), 0, 0);
        TestFactory.getInstance().addConstructor(tc,
                new GenericConstructor(com.examples.with.different.packagename.subpackage.ExampleWithInnerClass.Bar.class.getDeclaredConstructor(), com.examples.with.different.packagename.subpackage.ExampleWithInnerClass.Bar.class), 1, 0);
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        System.out.println(visitor.getCode());
        Set<Class<?>> imports = visitor.getImports();

        // Imported
        assertTrue(imports.contains(com.examples.with.different.packagename.otherpackage.ExampleWithInnerClass.class));

        // Not imported as the fully qualified name is used
        assertFalse(imports.contains(com.examples.with.different.packagename.subpackage.ExampleWithInnerClass.class));
        assertEquals("ExampleWithInnerClass", visitor.getClassName(com.examples.with.different.packagename.otherpackage.ExampleWithInnerClass.class));
        assertEquals("com.examples.with.different.packagename.subpackage.ExampleWithInnerClass", visitor.getClassName(com.examples.with.different.packagename.subpackage.ExampleWithInnerClass.class));
        assertEquals("ExampleWithInnerClass.Foo", visitor.getClassName(com.examples.with.different.packagename.otherpackage.ExampleWithInnerClass.Foo.class));
        assertEquals("com.examples.with.different.packagename.subpackage.ExampleWithInnerClass.Bar", visitor.getClassName(com.examples.with.different.packagename.subpackage.ExampleWithInnerClass.Bar.class));
    }

    @Test
    public void testUninterpretedStatementAddsStandardCharsetsImport() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new UninterpretedStatement(tc, "String s = StandardCharsets.UTF_8.name();"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Set<Class<?>> imports = visitor.getImports();
        assertTrue(imports.contains(StandardCharsets.class));
    }

    @Test
    public void testUninterpretedStatementReturnExpressionAddsStandardCharsetsImport() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new UninterpretedStatement(
                tc,
                byte[].class,
                "byte[] __llm_fallback0 = null;",
                Collections.emptyMap(),
                "\"hello\".getBytes(StandardCharsets.UTF_8)"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Set<Class<?>> imports = visitor.getImports();
        assertTrue(imports.contains(StandardCharsets.class));
    }

    @Test
    public void testUninterpretedStatementReturnExpressionAddsGeneralClassImport() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new UninterpretedStatement(
                tc,
                byte[].class,
                "byte[] __llm_fallback0 = null;",
                Collections.emptyMap(),
                "Base64.getDecoder().decode(\"aGVsbG8=\")"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Set<Class<?>> imports = visitor.getImports();
        assertTrue(imports.contains(Base64.class));
    }

    @Test
    public void testUninterpretedStatementAddsImportForClassLiteralAndCastType() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new UninterpretedStatement(tc,
                "java.lang.reflect.Method m = MainMenu.class.getDeclaredMethod(\"addShortcutAndIcon\", JMenuItem.class, String.class);\n"
                        + "m.invoke(null, (JMenuItem) null, \"any\");"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Set<Class<?>> imports = visitor.getImports();
        assertTrue(imports.contains(JMenuItem.class));
    }

    @Test
    public void testUninterpretedStatementAddsImportForConstructorUseInLoop() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new UninterpretedStatement(tc,
                "for (int i = 0; i < 2; i++) {\n"
                        + "  Object item = new JMenuItem();\n"
                        + "}"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Set<Class<?>> imports = visitor.getImports();
        assertTrue(imports.contains(JMenuItem.class));
    }

    @Test
    public void testUninterpretedStatementAddsImportForPlainGenericDeclaration() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new UninterpretedStatement(tc,
                "try {\n"
                        + "  List results = luceneSearcher0.search(\"description:x\");\n"
                        + "  assertNotNull(results);\n"
                        + "} catch (LuceneException ex) {\n"
                        + "  assertNotNull(ex);\n"
                        + "}"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Set<Class<?>> imports = visitor.getImports();
        assertTrue(imports.contains(java.util.List.class));
    }

    @Test
    public void testUninterpretedStatementAddsImportForThrowsClauseType() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new UninterpretedStatement(tc,
                "Object reader = new Object() {\n"
                        + "  String read() throws IOException {\n"
                        + "    return null;\n"
                        + "  }\n"
                        + "};"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Set<Class<?>> imports = visitor.getImports();
        assertTrue(imports.contains(IOException.class));
    }

    @Test
    public void testUninterpretedStatementAddsImportsForAnonymousClassGenericReturnType() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new UninterpretedStatement(tc,
                "Object adminApp = new Object() {\n"
                        + "  public Vector<Locale> getGroups() {\n"
                        + "    return null;\n"
                        + "  }\n"
                        + "};"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Set<Class<?>> imports = visitor.getImports();
        assertTrue(imports.contains(java.util.Vector.class));
        assertTrue(imports.contains(java.util.Locale.class));
    }

    @Test
    public void testUninterpretedStatementAddsImportForGenericArgumentOfQualifiedReturnType() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new UninterpretedStatement(tc,
                "Object lib = new Object() {\n"
                        + "  public java.util.List<JButton> getButtons() {\n"
                        + "    return Collections.emptyList();\n"
                        + "  }\n"
                        + "};"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Set<Class<?>> imports = visitor.getImports();
        assertTrue(imports.contains(Collections.class));
        assertTrue(imports.contains(javax.swing.JButton.class));
    }

    @Test
    public void testUninterpretedStatementNormalizesBinaryInnerClassLiteral() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new UninterpretedStatement(tc,
                "assertThrows(org.evosuite.runtime.System$SystemExitException.class, () -> framework.MainClass.main(new String[] { \"-unknown\" }));"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("org.evosuite.runtime.System.SystemExitException.class"));
        assertFalse(code.contains("System$SystemExitException.class"));
    }

    @Test
    public void testUninterpretedStatementAddsImportForQualifiedJunitAssertions() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new UninterpretedStatement(tc,
                "Assertions.assertArrayEquals(new String[] {}, new String[] {});"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Set<Class<?>> imports = visitor.getImports();
        assertTrue(imports.contains(Assertions.class));
    }

    @Test
    public void testUninterpretedStatementAddsImportForNestedEnumSimpleName() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new ClassPrimitiveStatement(tc, SnippetNestedOwner.class));
        tc.addStatement(new UninterpretedStatement(tc,
                "assertEquals(ChangeEventType.USER, ChangeEventType.USER);"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Set<Class<?>> imports = visitor.getImports();
        assertTrue(imports.contains(SnippetNestedOwner.ChangeEventType.class));
    }

    @Test
    public void testUninterpretedStatementAddsImportForRelatedApiTypeSimpleName() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new ClassPrimitiveStatement(tc, SnippetRelatedOwner.class));
        tc.addStatement(new UninterpretedStatement(tc, "Timestamp ts = null;"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Set<Class<?>> imports = visitor.getImports();
        assertTrue(imports.contains(java.sql.Timestamp.class));
    }

    @Test
    public void testUninterpretedStatementAddsImportForTransitivelyRelatedTypeSimpleName() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new ClassPrimitiveStatement(tc, SnippetRelatedOwner.class));
        tc.addStatement(new UninterpretedStatement(tc, "CodeSource cs = null;"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Set<Class<?>> imports = visitor.getImports();
        assertTrue(imports.contains(java.security.CodeSource.class));
    }

    @Test
    public void testUninterpretedStatementAddsImportForInstanceofType() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new UninterpretedStatement(tc,
                "for (java.awt.Component c : panel.getComponents()) {\n"
                        + "  if (c instanceof JScrollPane) {\n"
                        + "    // no-op\n"
                        + "  }\n"
                        + "}"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Set<Class<?>> imports = visitor.getImports();
        assertTrue(imports.contains(javax.swing.JScrollPane.class));
    }

    @Test
    public void testUninterpretedStatementAddsImportForQualifiedMockitoUsage() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new UninterpretedStatement(tc,
                "Mockito.when(flag).thenReturn(true);"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Set<Class<?>> imports = visitor.getImports();
        assertTrue(imports.stream().anyMatch(c ->
                        "Mockito".equals(c.getSimpleName())
                                && c.getName().endsWith(".mockito.Mockito")),
                "Expected Mockito class import for qualified Mockito usage");
    }

    @Test
    public void testUninterpretedStatementAddsImportForJndiSearchControlsSimpleName() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new UninterpretedStatement(tc,
                "doThrow(new NamingException(\"fail\")).when(ldapContext0)"
                        + ".search(anyString(), anyString(), any(SearchControls.class));"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Set<Class<?>> imports = visitor.getImports();
        assertTrue(imports.contains(javax.naming.directory.SearchControls.class),
                "Expected SearchControls import for simple-name class literal usage");
    }

    @Test
    public void testUninterpretedStatementAddsImportForJavaLangReflectFieldSimpleName() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new UninterpretedStatement(tc,
                "Field f = object0.getClass().getDeclaredField(\"x\");"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Set<Class<?>> imports = visitor.getImports();
        assertTrue(imports.contains(java.lang.reflect.Field.class),
                "Expected Field import for simple-name declaration usage");
    }

    @Test
    public void testUninterpretedStatementAddsImportForSiblingTypeInKnownPackage() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new ClassPrimitiveStatement(tc, javax.xml.parsers.DocumentBuilder.class));
        tc.addStatement(new UninterpretedStatement(tc, "DocumentBuilderFactory x = null;"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Set<Class<?>> imports = visitor.getImports();
        assertTrue(imports.contains(javax.xml.parsers.DocumentBuilderFactory.class),
                "Expected sibling type import resolved from known package");
    }

    @Test
    public void testLlmUninterpretedStatementPromotesUndeclaredNewAssignment() {
        TestCase tc = new DefaultTestCase();
        UninterpretedStatement stmt = new UninterpretedStatement(tc,
                "try {\n"
                        + "  s = new Scanner(\"x\");\n"
                        + "} catch (Exception e) {\n"
                        + "  fail(e.getMessage());\n"
                        + "}");
        stmt.setParsedFromLlm(true);
        tc.addStatement(stmt);

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("Scanner s = new Scanner(\"x\");"),
                "Undeclared constructor assignment should be promoted to declaration:\n" + code);
    }

    @Test
    public void testDetachedVoidNullReferenceRendersAsNullLiteral() {
        TestCase tc = new DefaultTestCase();
        TestCodeVisitor visitor = new TestCodeVisitor();
        NullReference nullRef = new NullReference(tc, Void.class);
        assertEquals("null", visitor.getVariableName(nullRef));
    }

    @Test
    public void testAssignmentDeclarationDoesNotUseNullKeywordAsVariableName() {
        TestCase tc = new DefaultTestCase();
        NullReference target = new NullReference(tc, Object.class);
        NullReference value = new NullReference(tc, Object.class);
        tc.addStatement(new AssignmentStatement(tc, target, value));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        assertFalse(code.contains("Object null = null;"));
        assertTrue(code.contains("Object nullRef"));
    }

    @Test
    public void testCastAndBoxingInArray() {
        // short[] shortArray0 = new short[5];
        // Long[] longArray0 = new Long[5];
        // longArray0[0] = (Long) shortArray0[1]; <-- this gives a compile error
        TestCase tc = new DefaultTestCase();
        ArrayStatement shortArrayStatement = new ArrayStatement(tc, short[].class, 5);
        tc.addStatement(shortArrayStatement);
        ArrayStatement longArrayStatement = new ArrayStatement(tc, Long[].class, 5);
        tc.addStatement(longArrayStatement);

        ArrayIndex longIndex = new ArrayIndex(tc, longArrayStatement.getArrayReference(), 0);
        ArrayIndex shortIndex = new ArrayIndex(tc, shortArrayStatement.getArrayReference(), 1);
        AssignmentStatement assignmentStatement = new AssignmentStatement(tc, longIndex, shortIndex);
        tc.addStatement(assignmentStatement);
        String code = tc.toCode();
        System.out.println(tc);
        assertFalse(code.contains("longArray0[0] = (Long) shortArray0[1]"));
    }

    @Test
    public void testFunctionalMockWithAssignableRawAndTargetTypes() {
        TestCase tc = new DefaultTestCase();
        FunctionalMockStatement mockStatement = new FunctionalMockStatement(
                tc, MessageMultiplexer.class, GenericClassFactory.get(MessageHandler.class));
        tc.addStatement(mockStatement);

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("mock("));
        assertTrue(code.contains("MessageMultiplexer"));
    }

    @Test
    public void testWrapperCastInArray() {
        // Short[] shortArray0 = new Short[5];
        // Integer[] integerArray0 = new Integer[9];
        // integerArray0[0] = (Integer) shortArray0[3];
        TestCase tc = new DefaultTestCase();
        ArrayStatement shortArrayStatement = new ArrayStatement(tc, Short[].class, 5);
        tc.addStatement(shortArrayStatement);
        ArrayStatement intArrayStatement = new ArrayStatement(tc, Integer[].class, 9);
        tc.addStatement(intArrayStatement);

        ArrayIndex intIndex = new ArrayIndex(tc, intArrayStatement.getArrayReference(), 0);
        ArrayIndex shortIndex = new ArrayIndex(tc, shortArrayStatement.getArrayReference(), 3);
        AssignmentStatement assignmentStatement = new AssignmentStatement(tc, intIndex, shortIndex);
        tc.addStatement(assignmentStatement);
        String code = tc.toCode();
        System.out.println(tc);
        assertFalse(code.contains("integerArray0[0] = (Integer) shortArray0[3]"));
    }

    @Test
    public void testInnerClassEnum() throws Throwable {

        //first construct a test case for the Generic method
        TestCase tc = new DefaultTestCase();
        VariableReference userObject = TestFactory.getInstance().addConstructor(tc,
                new GenericConstructor(EnumUser.class.getDeclaredConstructor(), EnumUser.class), 0, 0);

        EnumPrimitiveStatement primitiveStatement = new EnumPrimitiveStatement(tc, EnumInInnerClass.AnEnum.class);
        primitiveStatement.setValue(EnumInInnerClass.AnEnum.FOO);
        VariableReference enumObject = tc.addStatement(primitiveStatement);

        Method m = EnumUser.class.getDeclaredMethod("foo", EnumInInnerClass.AnEnum.class);
        GenericMethod gm = new GenericMethod(m, EnumUser.class);
        MethodStatement ms = new MethodStatement(tc, gm, userObject, Arrays.asList(enumObject));
        tc.addStatement(ms);

        //Finally, visit the test
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor); //should not throw exception
        String code = visitor.getCode();
        assertTrue(code.contains("= EnumInInnerClass.AnEnum.FOO"));
    }

    /*
     * There are some weird enum constructs in Closure, so we need to check that enum names
     * don't contain the name of the anonymous class they might represent
     */
    @Test
    public void testInnerClassAbstractEnum() throws NoSuchMethodException, ConstructionFailedException {

        //first construct a test case for the Generic method
        TestCase tc = new DefaultTestCase();
        VariableReference userObject = TestFactory.getInstance().addConstructor(tc,
                new GenericConstructor(AbstractEnumUser.class.getDeclaredConstructor(), AbstractEnumUser.class), 0, 0);

        EnumPrimitiveStatement primitiveStatement = new EnumPrimitiveStatement(tc, AbstractEnumInInnerClass.AnEnum.class);
        primitiveStatement.setValue(AbstractEnumInInnerClass.AnEnum.FOO);
        VariableReference enumObject = tc.addStatement(primitiveStatement);

        Method m = AbstractEnumUser.class.getDeclaredMethod("foo", AbstractEnumInInnerClass.AnEnum.class);
        GenericMethod gm = new GenericMethod(m, AbstractEnumUser.class);
        MethodStatement ms = new MethodStatement(tc, gm, userObject, Arrays.asList(enumObject));
        tc.addStatement(ms);

        //Finally, visit the test
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor); //should not throw exception
        String code = visitor.getCode();
        System.out.println(code);
        assertFalse(code.contains("= AbstractEnumInInnerClass.AnEnum.1.FOO"));
        assertTrue(code.contains("= AbstractEnumInInnerClass.AnEnum.FOO"));
    }

    @Test
    public void testClashingNestedEnumSimpleNamesUseCanonicalNameForSecond() throws ConstructionFailedException {
        TestCase tc = new DefaultTestCase();

        EnumPrimitiveStatement firstEnum = new EnumPrimitiveStatement(tc, EnumInInnerClass.AnEnum.class);
        firstEnum.setValue(EnumInInnerClass.AnEnum.FOO);
        tc.addStatement(firstEnum);

        EnumPrimitiveStatement secondEnum = new EnumPrimitiveStatement(tc, AbstractEnumInInnerClass.AnEnum.class);
        secondEnum.setValue(AbstractEnumInInnerClass.AnEnum.FOO);
        tc.addStatement(secondEnum);

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();
        Set<Class<?>> imports = visitor.getImports();

        assertTrue(code.contains("EnumInInnerClass.AnEnum.FOO"));
        assertTrue(code.contains("AbstractEnumInInnerClass.AnEnum.FOO"));
        assertFalse(imports.contains(EnumInInnerClass.AnEnum.class),
                "Qualified nested enum usage should not import the nested enum type");
        assertFalse(imports.contains(AbstractEnumInInnerClass.AnEnum.class),
                "Conflicting nested enum should use canonical name instead of a clashing import");
    }

    @Test
    public void testMethodRenamingWithHeuristics() throws Exception{

        Properties.getInstance().setValue("variable_naming_strategy", Properties.VariableNamingStrategy.HEURISTICS_BASED);
        TestCase tc = new DefaultTestCase();
        // new Person()
        VariableReference person = TestFactory.getInstance().addConstructor(tc,
                new GenericConstructor(Person.class.getDeclaredConstructor(), Person.class), 0, 0);
        //getFixedId()
        Method m0 = Person.class.getDeclaredMethod("getFixedId");
        GenericMethod gm0 = new GenericMethod(m0, Person.class);
        MethodStatement ms0 = new MethodStatement(tc, gm0, person, Arrays.asList());
        VariableReference var0 = tc.addStatement(ms0);
        //setAge();
        Method m1 = Person.class.getDeclaredMethod("setAge", int.class);
        GenericMethod gm1 = new GenericMethod(m1, Person.class);
        MethodStatement ms1 = new MethodStatement(tc, gm1, person, Arrays.asList(var0));
        tc.addStatement(ms1);
        //getAge();
        Method m2 = Person.class.getDeclaredMethod("getAge");
        GenericMethod gm2 = new GenericMethod(m2, Person.class);
        MethodStatement ms2 = new MethodStatement(tc, gm2, person, Arrays.asList());
        VariableReference var1 = tc.addStatement(ms2);
        AssignmentStatement as0 = new AssignmentStatement(tc, var0, var1);
        tc.addStatement(as0);
        //isAdult();
        Method m3 = Person.class.getDeclaredMethod("isAdult");
        GenericMethod gm3 = new GenericMethod(m3, Person.class);
        MethodStatement ms3 = new MethodStatement(tc, gm3, person, Arrays.asList());
        VariableReference var2 = tc.addStatement(ms3);
        AssignmentStatement as1 = new AssignmentStatement(tc, var2, var2);
        tc.addStatement(as1);


        //Finally, visit the test
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor); //should not throw exception
        String code = visitor.getCode();
        System.out.println(code);

        assertTrue(code.contains("int age = person.getAge()"));
        assertFalse(code.contains("int int1 = person0.getAge()"));
        assertTrue(code.contains("int fixedId = person.getFixedId()"));
        assertFalse(code.contains("int int0 = person0.getFixedId()"));
        assertTrue(code.contains("boolean adult = person.isAdult()"));
        assertFalse(code.contains("boolean boolean0 = person0.isAdult()"));
    }

    @Test
    public void testMethodRenamingWithTypes() throws Exception{

        Properties.getInstance().setValue("variable_naming_strategy", Properties.VariableNamingStrategy.TYPE_BASED);
        TestCase tc = new DefaultTestCase();
        // new Person()
        VariableReference person = TestFactory.getInstance().addConstructor(tc,
                new GenericConstructor(Person.class.getDeclaredConstructor(), Person.class), 0, 0);
        //getFixedId()
        Method m0 = Person.class.getDeclaredMethod("getFixedId");
        GenericMethod gm0 = new GenericMethod(m0, Person.class);
        MethodStatement ms0 = new MethodStatement(tc, gm0, person, Arrays.asList());
        VariableReference var0 = tc.addStatement(ms0);
        //setAge();
        Method m1 = Person.class.getDeclaredMethod("setAge", int.class);
        GenericMethod gm1 = new GenericMethod(m1, Person.class);
        MethodStatement ms1 = new MethodStatement(tc, gm1, person, Arrays.asList(var0));
        tc.addStatement(ms1);
        //getAge();
        Method m2 = Person.class.getDeclaredMethod("getAge");
        GenericMethod gm2 = new GenericMethod(m2, Person.class);
        MethodStatement ms2 = new MethodStatement(tc, gm2, person, Arrays.asList());
        VariableReference var1 = tc.addStatement(ms2);
        AssignmentStatement as0 = new AssignmentStatement(tc, var0, var1);
        tc.addStatement(as0);
        //isAdult();
        Method m3 = Person.class.getDeclaredMethod("isAdult");
        GenericMethod gm3 = new GenericMethod(m3, Person.class);
        MethodStatement ms3 = new MethodStatement(tc, gm3, person, Arrays.asList());
        VariableReference var2 = tc.addStatement(ms3);
        AssignmentStatement as1 = new AssignmentStatement(tc, var2, var2);
        tc.addStatement(as1);


        //Finally, visit the test
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor); //should not throw exception
        String code = visitor.getCode();
        System.out.println(code);

        assertFalse(code.contains("int age = person.getAge()"));
        assertTrue(code.contains("int int1 = person0.getAge()"));
        assertFalse(code.contains("int fixedId = person.getFixedId()"));
        assertTrue(code.contains("int int0 = person0.getFixedId()"));
        assertFalse(code.contains("boolean adult = person.isAdult()"));
        assertTrue(code.contains("boolean boolean0 = person0.isAdult()"));
    }

    @Test
    public void testClassLiteralParameterCastUsesRawClass() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference classVar = tc.addStatement(new ClassPrimitiveStatement(tc, LinkedList.class));

        Method method = TestCodeVisitorTest.class.getDeclaredMethod("consumeLinkedListIntegerClass", Class.class);
        GenericMethod genericMethod = new GenericMethod(method, TestCodeVisitorTest.class);
        MethodStatement methodStatement = new MethodStatement(tc, genericMethod, null, Arrays.asList(classVar));
        tc.addStatement(methodStatement);

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("(Class) "));
        assertFalse(code.contains("(Class<LinkedList<Integer>>) "));
    }

    @Test
    public void testPrimitiveClassLiteralDeclarationUsesWildcardClassType() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new ClassPrimitiveStatement(tc, boolean.class));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("Class<?> "));
        assertTrue(code.contains("boolean.class"));
        assertFalse(code.contains("Class<boolean>"));
    }

    @Test
    public void testSafeErasureSupportsWildcardTypeImpl() throws Exception {
        TestCodeVisitor visitor = new TestCodeVisitor();
        Method safeErasure = TestCodeVisitor.class.getDeclaredMethod("safeErasure", Type.class);
        safeErasure.setAccessible(true);

        WildcardTypeImpl wildcard = new WildcardTypeImpl(new Type[]{Number.class}, new Type[]{});
        Class<?> erased = (Class<?>) safeErasure.invoke(visitor, wildcard);

        assertEquals(Number.class, erased);
    }

    @Test
    public void testUnresolvedTypeVariableParameterWithPrimitiveAvoidsObjectCast() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference charVar = tc.addStatement(new CharPrimitiveStatement(tc, 'a'));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Method getParameterString = TestCodeVisitor.class.getDeclaredMethod(
                "getParameterString", Type[].class, java.util.List.class, boolean.class, boolean.class, int.class);
        getParameterString.setAccessible(true);

        TypeVariable<?> e = Vector.class.getTypeParameters()[0];
        String parameterString = (String) getParameterString.invoke(
                visitor,
                new Type[]{e},
                Arrays.asList(charVar),
                false,
                false,
                0);

        assertTrue(parameterString.contains("char0"));
        assertFalse(parameterString.contains("(Object)"));
    }

    @Test
    public void testNullParameterWithoutOverloadDoesNotAddReferenceCast() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference nullVar = tc.addStatement(new NullStatement(tc, Object.class));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Method getParameterString = TestCodeVisitor.class.getDeclaredMethod(
                "getParameterString", Type[].class, java.util.List.class, boolean.class, boolean.class, int.class);
        getParameterString.setAccessible(true);

        String parameterString = (String) getParameterString.invoke(
                visitor,
                new Type[]{CharSequence.class},
                Arrays.asList(nullVar),
                false,
                false,
                0);

        assertEquals("null", parameterString);
    }

    @Test
    public void testNullParameterWithOverloadKeepsReferenceCast() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference nullVar = tc.addStatement(new NullStatement(tc, Object.class));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Method getParameterString = TestCodeVisitor.class.getDeclaredMethod(
                "getParameterString", Type[].class, java.util.List.class, boolean.class, boolean.class, int.class);
        getParameterString.setAccessible(true);

        String parameterString = (String) getParameterString.invoke(
                visitor,
                new Type[]{CharSequence.class},
                Arrays.asList(nullVar),
                false,
                true,
                0);

        assertEquals("(CharSequence) null", parameterString);
    }

    @Test
    public void testNullParameterWithOverloadAndTypeVariableAvoidsObjectCast() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference nullVar = tc.addStatement(new NullStatement(tc, Object.class));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Method getParameterString = TestCodeVisitor.class.getDeclaredMethod(
                "getParameterString", Type[].class, java.util.List.class, boolean.class, boolean.class, int.class);
        getParameterString.setAccessible(true);

        TypeVariable<?> e = Vector.class.getTypeParameters()[0];
        String parameterString = (String) getParameterString.invoke(
                visitor,
                new Type[]{e},
                Arrays.asList(nullVar),
                false,
                true,
                0);

        assertEquals("null", parameterString);
        assertFalse(parameterString.contains("(Object)"));
    }

    @Test
    public void testUninterpretedNullVariableWithTypeVariableRendersAsNullLiteral() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference nullObjectVar = tc.addStatement(
                new UninterpretedStatement(tc, Object.class, "Object object0 = null;"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        Method getParameterString = TestCodeVisitor.class.getDeclaredMethod(
                "getParameterString", Type[].class, java.util.List.class, boolean.class, boolean.class, int.class);
        getParameterString.setAccessible(true);

        TypeVariable<?> e = Vector.class.getTypeParameters()[0];
        String parameterString = (String) getParameterString.invoke(
                visitor,
                new Type[]{e},
                Arrays.asList(nullObjectVar),
                false,
                false,
                0);

        assertEquals("null", parameterString);
        assertFalse(parameterString.contains("object0"));
        assertFalse(parameterString.contains("(Object)"));
    }

    @Test
    public void testParameterizedReceiverWithRawMethodOwnerAvoidsObjectNullCast() throws Exception {
        TestCase tc = new DefaultTestCase();
        Type parameterizedArrayList = TypeUtils.parameterize(ArrayList.class, String.class);
        VariableReference listVar = TestFactory.getInstance().addConstructor(
                tc,
                new GenericConstructor(ArrayList.class.getDeclaredConstructor(), parameterizedArrayList),
                0,
                0);
        VariableReference nullVar = tc.addStatement(new NullStatement(tc, Object.class));

        Method addMethod = ArrayList.class.getMethod("add", Object.class);
        GenericMethod rawOwnerAdd = new GenericMethod(addMethod, ArrayList.class);
        tc.addStatement(new MethodStatement(tc, rawOwnerAdd, listVar, Arrays.asList(nullVar)));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains(".add(null);"), "Expected parameterized receiver to render plain null:\n" + code);
        assertFalse(code.contains(".add((Object) null);"),
                "Parameterized receiver should not emit erased Object null casts:\n" + code);
    }

    @Test
    public void testRawReceiverWithParameterizedMethodOwnerCastsGenericReturn() throws Exception {
        TestCase tc = new DefaultTestCase();
        Type parameterizedArrayList = TypeUtils.parameterize(ArrayList.class, String.class);
        VariableReference listVar = TestFactory.getInstance().addConstructor(
                tc,
                new GenericConstructor(ArrayList.class.getDeclaredConstructor(), parameterizedArrayList),
                0,
                0);

        Method iteratorMethod = ArrayList.class.getMethod("iterator");
        GenericMethod typedIteratorMethod = new GenericMethod(iteratorMethod, parameterizedArrayList);
        VariableReference iteratorVar = tc.addStatement(
                new MethodStatement(tc, typedIteratorMethod, listVar, Collections.emptyList()));
        iteratorVar.setType(Iterator.class);

        Method nextMethod = Iterator.class.getMethod("next");
        GenericMethod typedNextMethod = new GenericMethod(nextMethod, TypeUtils.parameterize(Iterator.class, String.class));
        VariableReference nextValue = tc.addStatement(
                new MethodStatement(tc, typedNextMethod, iteratorVar, Collections.emptyList()));
        Method lengthMethod = String.class.getMethod("length");
        tc.addStatement(new MethodStatement(tc, new GenericMethod(lengthMethod, String.class), nextValue, Collections.emptyList()));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("Iterator "),
                "Expected raw Iterator receiver declaration in regression setup:\n" + code);
        assertTrue(code.contains("= (String)") && code.contains(".next();"),
                "Raw generic receiver should cast generic return to compile safely:\n" + code);
    }

    @Test
    public void testParameterizedReceiverWithRawMethodOwnerAvoidsObjectCastForPrimitiveValue() throws Exception {
        TestCase tc = new DefaultTestCase();
        Type parameterizedArrayList = TypeUtils.parameterize(ArrayList.class, Integer.class);
        VariableReference listVar = TestFactory.getInstance().addConstructor(
                tc,
                new GenericConstructor(ArrayList.class.getDeclaredConstructor(), parameterizedArrayList),
                0,
                0);
        VariableReference intVar = tc.addStatement(new IntPrimitiveStatement(tc, 1));

        Method addMethod = ArrayList.class.getMethod("add", Object.class);
        GenericMethod rawOwnerAdd = new GenericMethod(addMethod, ArrayList.class);
        tc.addStatement(new MethodStatement(tc, rawOwnerAdd, listVar, Arrays.asList(intVar)));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains(".add("), "Expected add call:\n" + code);
        assertFalse(code.contains(".add((Object)"),
                "Parameterized receiver should not emit erased Object casts for concrete values:\n" + code);
    }

    @Test
    public void testParameterizedReceiverWithRawMethodOwnerAvoidsObjectCastForReferenceValue() throws Exception {
        TestCase tc = new DefaultTestCase();
        Type parameterizedArrayList = TypeUtils.parameterize(ArrayList.class, String.class);
        VariableReference listVar = TestFactory.getInstance().addConstructor(
                tc,
                new GenericConstructor(ArrayList.class.getDeclaredConstructor(), parameterizedArrayList),
                0,
                0);
        VariableReference stringVar = tc.addStatement(new StringPrimitiveStatement(tc, "value"));

        Method addMethod = ArrayList.class.getMethod("add", Object.class);
        GenericMethod rawOwnerAdd = new GenericMethod(addMethod, ArrayList.class);
        tc.addStatement(new MethodStatement(tc, rawOwnerAdd, listVar, Arrays.asList(stringVar)));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains(".add("), "Expected add call:\n" + code);
        assertFalse(code.contains(".add((Object)"),
                "Parameterized receiver should not emit erased Object casts for reference values:\n" + code);
    }

    @Test
    public void testParameterizedReceiverWithRawMethodOwnerAvoidsObjectCastForSubtypeReferenceValue() throws Exception {
        TestCase tc = new DefaultTestCase();
        Type parameterizedArrayList = TypeUtils.parameterize(ArrayList.class, CharSequence.class);
        VariableReference listVar = TestFactory.getInstance().addConstructor(
                tc,
                new GenericConstructor(ArrayList.class.getDeclaredConstructor(), parameterizedArrayList),
                0,
                0);
        VariableReference stringVar = tc.addStatement(new StringPrimitiveStatement(tc, "value"));

        Method addMethod = ArrayList.class.getMethod("add", Object.class);
        GenericMethod rawOwnerAdd = new GenericMethod(addMethod, ArrayList.class);
        tc.addStatement(new MethodStatement(tc, rawOwnerAdd, listVar, Arrays.asList(stringVar)));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains(".add("), "Expected add call:\n" + code);
        assertFalse(code.contains(".add((Object)"),
                "Parameterized receiver should not emit erased Object casts for subtype reference values:\n" + code);
    }

    @Test
    public void testAssertionReferencingFutureVariableIsDeferredUntilVariableIsDeclared() {
        TestCase tc = new DefaultTestCase();
        VariableReference int0 = tc.addStatement(new IntPrimitiveStatement(tc, 1));
        VariableReference int1 = tc.addStatement(new IntPrimitiveStatement(tc, 2));

        EqualsAssertion assertion = new EqualsAssertion();
        assertion.setSource(int0);
        assertion.setDest(int1);
        assertion.setValue(Boolean.FALSE);
        tc.getStatement(int0.getStPosition()).addAssertion(assertion);

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        String int0Name = visitor.getVariableName(int0);
        String int1Name = visitor.getVariableName(int1);
        String assertionCode = "assertFalse(" + int0Name + " == " + int1Name + ");";

        assertTrue(code.contains(assertionCode));
        assertTrue(code.indexOf(int1Name + " = ") < code.indexOf(assertionCode));
    }

    @Test
    public void testGenerateFailAssertionSkipsHeadlessException() {
        TestCodeVisitor visitor = new TestCodeVisitor();
        String fail = visitor.generateFailAssertion(null, new HeadlessException());
        assertEquals("", fail);
    }

    @Test
    public void testGenerateFailAssertionKeepsNonHeadlessException() {
        TestCodeVisitor visitor = new TestCodeVisitor();
        String fail = visitor.generateFailAssertion(null, new IllegalArgumentException("boom"));
        assertTrue(fail.contains("Expecting exception: IllegalArgumentException"));
    }

    @Test
    public void testGenerateFailAssertionSkipsPrivateAccessIllegalArgumentException() {
        TestCodeVisitor visitor = new TestCodeVisitor();
        IllegalArgumentException ex = new IllegalArgumentException("boom");
        ex.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("org.evosuite.runtime.PrivateAccess",
                        "setVariable",
                        "PrivateAccess.java",
                        101)
        });

        String fail = visitor.generateFailAssertion(null, ex);
        assertEquals("", fail);
    }

    @Test
    public void testPackagePrivateConstructorInDifferentGeneratedPackageUsesReflection() throws Exception {
        String oldClassPrefix = Properties.CLASS_PREFIX;
        Properties.CLASS_PREFIX = "different.generated.package";
        try {
            TestCase tc = new DefaultTestCase();
            StringPrimitiveStatement arg = new StringPrimitiveStatement(tc, "x");
            tc.addStatement(arg);

            GenericConstructor constructor = new GenericConstructor(
                    PublicTypeWithPackagePrivateCtor.class.getDeclaredConstructor(String.class),
                    PublicTypeWithPackagePrivateCtor.class);
            ConstructorStatement ctorStmt = new ConstructorStatement(
                    tc,
                    constructor,
                    Collections.singletonList(arg.getReturnValue()));
            tc.addStatement(ctorStmt);

            TestCodeVisitor visitor = new TestCodeVisitor();
            tc.accept(visitor);
            String code = visitor.getCode();

            assertTrue(code.contains("getDeclaredConstructor("));
            assertTrue(code.contains(".setAccessible(true);"));
            assertTrue(code.contains(".newInstance("));
        } finally {
            Properties.CLASS_PREFIX = oldClassPrefix;
        }
    }

    @Test
    public void testGetImportsSkipsNonPublicClassOutsideGeneratedPackage() throws Exception {
        String oldClassPrefix = Properties.CLASS_PREFIX;
        Properties.CLASS_PREFIX = "different.generated.package";
        try {
            TestCodeVisitor visitor = new TestCodeVisitor();
            Class<?> nodeClass = Class.forName("java.util.stream.Node");

            visitor.getClassName(nodeClass);

            Set<Class<?>> imports = visitor.getImports();
            assertFalse(imports.contains(nodeClass));
        } finally {
            Properties.CLASS_PREFIX = oldClassPrefix;
        }
    }
}
