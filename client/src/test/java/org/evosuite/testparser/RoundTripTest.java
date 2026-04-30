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
package org.evosuite.testparser;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.PrivateFieldRoundTripFixture;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.numeric.*;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests: build a TestCase → generate source via TestCodeVisitor →
 * parse the source back → verify the resulting TestCase has the same structure.
 */
class RoundTripTest {

    private String generateCode(TestCase tc) {
        TestCodeVisitor visitor = new TestCodeVisitor();
        visitor.visitTestCase(tc);
        for (int i = 0; i < tc.size(); i++) {
            visitor.visitStatement(tc.getStatement(i));
        }
        return visitor.getCode();
    }

    private ParseResult parseCode(String code) {
        return parseCode(code, Collections.emptyList(), getClass().getClassLoader());
    }

    private ParseResult parseCode(String code, List<String> extraImports) {
        return parseCode(code, extraImports, getClass().getClassLoader());
    }

    private ParseResult parseCode(String code, List<String> extraImports, ClassLoader classLoader) {
        TestParser parser = new TestParser(classLoader);
        List<String> imports = new ArrayList<>(List.of(
                "import java.util.*;",
                "import java.util.concurrent.TimeUnit;"
        ));
        imports.addAll(extraImports);
        return parser.parseTestMethodBody(code, imports);
    }

    private Object executeCompiledSnippet(String code,
                                          String returnExpression,
                                          List<String> importLines) throws Throwable {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "System Java compiler is required for round-trip execution");

        Path tempDir = Files.createTempDirectory("evosuite-roundtrip");
        String className = "RoundTripSnippet" + System.nanoTime();
        Path sourceFile = tempDir.resolve(className + ".java");
        StringBuilder source = new StringBuilder();
        for (String importLine : importLines) {
            source.append(importLine).append("\n");
        }
        source.append("public class ").append(className).append(" {\n")
                .append("  public static Object run() throws Throwable {\n")
                .append(code).append("\n")
                .append("    return ").append(returnExpression).append(";\n")
                .append("  }\n")
                .append("}\n");
        Files.write(sourceFile, source.toString().getBytes(StandardCharsets.UTF_8));

        List<String> classpathEntries = buildRoundTripClasspathEntries();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            String classpath = String.join(System.getProperty("path.separator"), classpathEntries);
            int compilationResult = compiler.run(
                    null,
                    null,
                    err,
                    "-classpath",
                    classpath,
                    "-d",
                    tempDir.toString(),
                    sourceFile.toString());
            assertEquals(0, compilationResult,
                    "Generated reflective field access should compile:\n" + err.toString(StandardCharsets.UTF_8));

            List<URL> urls = new ArrayList<>();
            urls.add(tempDir.toUri().toURL());
            for (String entry : classpathEntries) {
                urls.add(Path.of(entry).toUri().toURL());
            }
            try (URLClassLoader loader =
                         new ChildFirstRoundTripClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader())) {
                Class<?> snippetClass = Class.forName(className, true, loader);
                return snippetClass.getMethod("run").invoke(null);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        } finally {
            Files.deleteIfExists(sourceFile);
            Files.deleteIfExists(tempDir.resolve(className + ".class"));
            Files.deleteIfExists(tempDir);
        }
    }

    private String codeSourcePath(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (Exception e) {
            throw new AssertionError("Could not resolve code source for " + type.getName(), e);
        }
    }

    private List<String> buildRoundTripClasspathEntries() {
        Set<String> entries = new LinkedHashSet<>();
        addProjectOutput(entries, "runtime/target/classes");
        addProjectOutput(entries, "client/target/test-classes");
        addProjectOutput(entries, "client/target/classes");
        String runtimeClasspath = System.getProperty("java.class.path");
        if (runtimeClasspath != null && !runtimeClasspath.isEmpty()) {
            for (String entry : runtimeClasspath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
                if (!entry.isEmpty()) {
                    entries.add(entry);
                }
            }
        }
        entries.add(codeSourcePath(org.evosuite.runtime.PrivateAccess.class));
        entries.add(codeSourcePath(PrivateFieldRoundTripFixture.class));
        entries.add(codeSourcePath(getClass()));
        return new ArrayList<>(entries);
    }

    private void addProjectOutput(Set<String> entries, String relativePath) {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path current = cwd; current != null; current = current.getParent()) {
            Path candidate = current.resolve(relativePath);
            if (Files.exists(candidate)) {
                entries.add(candidate.toString());
                return;
            }
        }
    }

    private ChildFirstRoundTripClassLoader createRoundTripClassLoader() throws Exception {
        List<String> classpathEntries = buildRoundTripClasspathEntries();
        List<URL> urls = new ArrayList<>();
        for (String entry : classpathEntries) {
            urls.add(Path.of(entry).toUri().toURL());
        }
        return new ChildFirstRoundTripClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
    }

    private static final class ChildFirstRoundTripClassLoader extends URLClassLoader {

        private ChildFirstRoundTripClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("org.evosuite.runtime.")
                    || name.startsWith("org.evosuite.testcase.PrivateFieldRoundTripFixture")) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try {
                            loaded = findClass(name);
                        } catch (ClassNotFoundException ignored) {
                            loaded = super.loadClass(name, false);
                        }
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    @Test
    void roundTripPrimitives() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 42));
        tc.addStatement(new DoublePrimitiveStatement(tc, 3.14));
        tc.addStatement(new BooleanPrimitiveStatement(tc, true));
        tc.addStatement(new StringPrimitiveStatement(tc, "hello"));

        String code = generateCode(tc);
        ParseResult result = parseCode(code);

        TestCase parsed = result.getTestCase();
        assertFalse(result.hasErrors(), "Round-trip should have no errors: " + result.getDiagnostics());
        assertEquals(tc.size(), parsed.size(), "Statement count should match");

        assertInstanceOf(IntPrimitiveStatement.class, parsed.getStatement(0));
        assertInstanceOf(DoublePrimitiveStatement.class, parsed.getStatement(1));
        assertInstanceOf(BooleanPrimitiveStatement.class, parsed.getStatement(2));
        assertInstanceOf(StringPrimitiveStatement.class, parsed.getStatement(3));

        assertEquals(42, ((IntPrimitiveStatement) parsed.getStatement(0)).getValue().intValue());
        assertEquals(3.14, ((DoublePrimitiveStatement) parsed.getStatement(1)).getValue(), 0.001);
        assertTrue(((BooleanPrimitiveStatement) parsed.getStatement(2)).getValue());
        assertEquals("hello", ((StringPrimitiveStatement) parsed.getStatement(3)).getValue());
    }

    @Test
    void roundTripConstructorAndMethod() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();

        // ArrayList list = new ArrayList();
        GenericConstructor gc = new GenericConstructor(
                ArrayList.class.getConstructor(),
                GenericClassFactory.get(ArrayList.class));
        VariableReference listRef = tc.addStatement(
                new ConstructorStatement(tc, gc, Collections.emptyList()));

        // list.clear();
        GenericMethod gm = new GenericMethod(
                ArrayList.class.getMethod("clear"),
                GenericClassFactory.get(ArrayList.class));
        tc.addStatement(new MethodStatement(tc, gm, listRef, Collections.emptyList()));

        String code = generateCode(tc);
        ParseResult result = parseCode(code);

        TestCase parsed = result.getTestCase();
        assertFalse(result.hasErrors(), "Round-trip should have no errors: " + result.getDiagnostics());
        assertEquals(2, parsed.size());
        assertInstanceOf(ConstructorStatement.class, parsed.getStatement(0));
        assertInstanceOf(MethodStatement.class, parsed.getStatement(1));
    }

    @Test
    void roundTripNullStatement() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new NullStatement(tc, Object.class));

        String code = generateCode(tc);
        ParseResult result = parseCode(code);

        TestCase parsed = result.getTestCase();
        assertFalse(result.hasErrors(), "Round-trip should have no errors: " + result.getDiagnostics());
        assertEquals(1, parsed.size());
        assertInstanceOf(NullStatement.class, parsed.getStatement(0));
    }

    @Test
    void roundTripArray() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new ArrayStatement(tc, int[].class, 3));

        String code = generateCode(tc);
        ParseResult result = parseCode(code);

        TestCase parsed = result.getTestCase();
        assertFalse(result.hasErrors(), "Round-trip should have no errors: " + result.getDiagnostics());
        assertEquals(1, parsed.size());
        assertInstanceOf(ArrayStatement.class, parsed.getStatement(0));
    }

    @Test
    void roundTripMethodWithArgs() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();

        // ArrayList list = new ArrayList();
        GenericConstructor gc = new GenericConstructor(
                ArrayList.class.getConstructor(),
                GenericClassFactory.get(ArrayList.class));
        VariableReference listRef = tc.addStatement(
                new ConstructorStatement(tc, gc, Collections.emptyList()));

        // String s = "hello";
        VariableReference strRef = tc.addStatement(
                new StringPrimitiveStatement(tc, "hello"));

        // list.add(s);
        GenericMethod gm = new GenericMethod(
                ArrayList.class.getMethod("add", Object.class),
                GenericClassFactory.get(ArrayList.class));
        tc.addStatement(new MethodStatement(tc, gm, listRef, List.of(strRef)));

        String code = generateCode(tc);
        ParseResult result = parseCode(code);

        TestCase parsed = result.getTestCase();
        assertFalse(result.hasErrors(), "Round-trip should have no errors: " + result.getDiagnostics());
        assertTrue(parsed.size() >= 3, "Should have at least 3 statements: " + parsed.size());
        assertInstanceOf(ConstructorStatement.class, parsed.getStatement(0));
        assertInstanceOf(StringPrimitiveStatement.class, parsed.getStatement(1));
        assertInstanceOf(MethodStatement.class, parsed.getStatement(2));
        assertEquals("add", ((MethodStatement) parsed.getStatement(2)).getMethodName());
    }

    @Test
    void roundTripUninterpretedStatement() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 5));
        tc.addStatement(new UninterpretedStatement(tc, "// custom code"));

        String code = generateCode(tc);
        ParseResult result = parseCode(code);

        TestCase parsed = result.getTestCase();
        assertFalse(result.hasErrors(), "Round-trip should have no errors: " + result.getDiagnostics());
        // UninterpretedStatement with a comment may not survive round-trip as a statement,
        // but the int should
        assertInstanceOf(IntPrimitiveStatement.class, parsed.getStatement(0));
        assertEquals(5, ((IntPrimitiveStatement) parsed.getStatement(0)).getValue().intValue());
    }

    @Test
    void roundTripStaticMethod() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();

        // int x = 42;
        VariableReference xRef = tc.addStatement(new IntPrimitiveStatement(tc, 42));

        // String s = String.valueOf(x);
        GenericMethod gm = new GenericMethod(
                String.class.getMethod("valueOf", int.class),
                GenericClassFactory.get(String.class));
        tc.addStatement(new MethodStatement(tc, gm, null, List.of(xRef)));

        String code = generateCode(tc);
        ParseResult result = parseCode(code);

        TestCase parsed = result.getTestCase();
        assertFalse(result.hasErrors(), "Round-trip should have no errors: " + result.getDiagnostics());
        assertEquals(2, parsed.size());
        assertInstanceOf(IntPrimitiveStatement.class, parsed.getStatement(0));
        assertInstanceOf(MethodStatement.class, parsed.getStatement(1));
        assertEquals("valueOf", ((MethodStatement) parsed.getStatement(1)).getMethodName());
    }

    @Test
    void roundTripMultipleMethods() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();

        // ArrayList list = new ArrayList();
        GenericConstructor gc = new GenericConstructor(
                ArrayList.class.getConstructor(),
                GenericClassFactory.get(ArrayList.class));
        VariableReference listRef = tc.addStatement(
                new ConstructorStatement(tc, gc, Collections.emptyList()));

        // int size = list.size();
        GenericMethod sizeMethod = new GenericMethod(
                ArrayList.class.getMethod("size"),
                GenericClassFactory.get(ArrayList.class));
        tc.addStatement(new MethodStatement(tc, sizeMethod, listRef, Collections.emptyList()));

        // boolean empty = list.isEmpty();
        GenericMethod isEmptyMethod = new GenericMethod(
                ArrayList.class.getMethod("isEmpty"),
                GenericClassFactory.get(ArrayList.class));
        tc.addStatement(new MethodStatement(tc, isEmptyMethod, listRef, Collections.emptyList()));

        String code = generateCode(tc);
        ParseResult result = parseCode(code);

        TestCase parsed = result.getTestCase();
        assertFalse(result.hasErrors(), "Round-trip should have no errors: " + result.getDiagnostics());
        assertEquals(3, parsed.size());
        assertInstanceOf(ConstructorStatement.class, parsed.getStatement(0));
        assertInstanceOf(MethodStatement.class, parsed.getStatement(1));
        assertInstanceOf(MethodStatement.class, parsed.getStatement(2));
        assertEquals("size", ((MethodStatement) parsed.getStatement(1)).getMethodName());
        assertEquals("isEmpty", ((MethodStatement) parsed.getStatement(2)).getMethodName());
    }

    @Test
    void roundTripReflectivePrivateFieldAccess() throws Throwable {
        DefaultTestCase tc = new DefaultTestCase();

        GenericConstructor ctor = new GenericConstructor(
                PrivateFieldRoundTripFixture.class.getConstructor(),
                GenericClassFactory.get(PrivateFieldRoundTripFixture.class));
        VariableReference holderRef = tc.addStatement(
                new ConstructorStatement(tc, ctor, Collections.emptyList()));

        Field labelField = PrivateFieldRoundTripFixture.class.getDeclaredField("label");
        VariableReference fieldRef = tc.addStatement(
                new FieldStatement(tc, new GenericField(labelField, PrivateFieldRoundTripFixture.class), holderRef));

        TestCodeVisitor visitor = new TestCodeVisitor();
        visitor.visitTestCase(tc);
        for (int i = 0; i < tc.size(); i++) {
            visitor.visitStatement(tc.getStatement(i));
        }
        String code = visitor.getCode();
        String returnExpression = visitor.getVariableName(fieldRef);

        assertTrue(code.contains("PrivateAccess.getVariable("),
                "Private field round-trip should emit reflective field access:\n" + code);
        assertEquals("label", executeCompiledSnippet(code, returnExpression, List.of(
                        "import org.evosuite.runtime.PrivateAccess;",
                        "import org.evosuite.testcase.PrivateFieldRoundTripFixture;"
                )),
                "Emitted reflective field access should execute to the private field value.\n"
                        + "Return expression: " + returnExpression + "\nCode:\n" + code);

        ParseResult result;
        try (ChildFirstRoundTripClassLoader roundTripLoader = createRoundTripClassLoader()) {
            result = parseCode(code, List.of(
                    "import org.evosuite.runtime.PrivateAccess;",
                    "import org.evosuite.testcase.PrivateFieldRoundTripFixture;"
            ), roundTripLoader);
        }
        assertFalse(result.hasErrors(), "Round-trip should parse reflective field access: " + result.getDiagnostics());

        TestCase parsed = result.getTestCase();
        assertTrue(parsed.size() >= tc.size(), "Reparsed reflective access may materialize helper arguments");
        assertInstanceOf(ConstructorStatement.class, parsed.getStatement(0));
        assertInstanceOf(MethodStatement.class, parsed.getStatement(parsed.size() - 1));
        assertEquals("getVariable", ((MethodStatement) parsed.getStatement(parsed.size() - 1)).getMethodName());

        TestCodeVisitor reparsedVisitor = new TestCodeVisitor();
        reparsedVisitor.visitTestCase(parsed);
        for (int i = 0; i < parsed.size(); i++) {
            reparsedVisitor.visitStatement(parsed.getStatement(i));
        }
        String reparsedCode = reparsedVisitor.getCode();

        assertTrue(reparsedCode.contains("PrivateAccess.getVariable("),
                "Re-emitted reparsed code should keep reflective field access:\n" + reparsedCode);
    }
}
