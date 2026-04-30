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
package org.evosuite.testcase.execution;

import org.evosuite.Properties;
import org.evosuite.runtime.mock.MockFramework;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutableSnippetEngineImportFilterTest {

    @Test
    void addClassImportSkipsPrimitiveArrayTypes() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method addClassImport = ExecutableSnippetEngine.class.getDeclaredMethod(
                "addClassImport", Class.class, Map.class, Set.class);
        addClassImport.setAccessible(true);

        Map<String, String> simpleNameToFqn = new LinkedHashMap<>();
        Set<String> collidingSimpleNames = new LinkedHashSet<>();

        addClassImport.invoke(engine, byte[].class, simpleNameToFqn, collidingSimpleNames);
        addClassImport.invoke(engine, int[][].class, simpleNameToFqn, collidingSimpleNames);
        addClassImport.invoke(engine, byte.class, simpleNameToFqn, collidingSimpleNames);

        assertTrue(simpleNameToFqn.isEmpty(), "Primitive/array types must never become imports");
        assertTrue(collidingSimpleNames.isEmpty(), "No collisions expected for skipped types");
    }

    @Test
    void addClassImportStillAcceptsRegularPublicClasses() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method addClassImport = ExecutableSnippetEngine.class.getDeclaredMethod(
                "addClassImport", Class.class, Map.class, Set.class);
        addClassImport.setAccessible(true);

        Map<String, String> simpleNameToFqn = new LinkedHashMap<>();
        Set<String> collidingSimpleNames = new LinkedHashSet<>();

        addClassImport.invoke(engine, java.util.ArrayList.class, simpleNameToFqn, collidingSimpleNames);
        addClassImport.invoke(engine, java.util.ArrayList[].class, simpleNameToFqn, collidingSimpleNames);

        assertFalse(simpleNameToFqn.isEmpty(), "Regular public classes should still be imported");
        assertTrue(simpleNameToFqn.containsKey("ArrayList"),
                "Array component type should be imported for non-primitive arrays");
    }

    @Test
    void addClassImportSkipsDefaultPackageTypes() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method addClassImport = ExecutableSnippetEngine.class.getDeclaredMethod(
                "addClassImport", Class.class, Map.class, Set.class);
        addClassImport.setAccessible(true);

        Map<String, String> simpleNameToFqn = new LinkedHashMap<>();
        Set<String> collidingSimpleNames = new LinkedHashSet<>();
        Class<?> defaultPackageType = Class.forName("DefaultPackageType");

        addClassImport.invoke(engine, defaultPackageType, simpleNameToFqn, collidingSimpleNames);

        assertTrue(simpleNameToFqn.isEmpty(), "Default-package classes must not be imported");
        assertTrue(collidingSimpleNames.isEmpty(), "No collisions expected for skipped default-package type");
    }

    @Test
    void generatedSnippetIncludesCommonXmlImports() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method buildStatementClassSource = ExecutableSnippetEngine.class.getDeclaredMethod(
                "buildStatementClassSource", String.class, String.class, Map.class, String.class);
        buildStatementClassSource.setAccessible(true);

        String source = (String) buildStatementClassSource.invoke(
                engine, "k", "Document d = null;", Collections.emptyMap(), null);

        assertNotNull(source);
        assertTrue(source.contains("import java.util.*;"));
        assertTrue(source.contains("import javax.xml.parsers.*;"));
        assertTrue(source.contains("import org.w3c.dom.*;"));
        assertTrue(source.contains("import org.xml.sax.*;"));
    }

    @Test
    void generatedSnippetIncludesTargetClassPackageWildcardImport() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method buildStatementClassSource = ExecutableSnippetEngine.class.getDeclaredMethod(
                "buildStatementClassSource", String.class, String.class, Map.class, String.class);
        buildStatementClassSource.setAccessible(true);

        String previousTarget = Properties.TARGET_CLASS;
        try {
            Properties.TARGET_CLASS = "com.example.sut.TargetType";
            String source = (String) buildStatementClassSource.invoke(
                    engine, "k_pkg", "TargetType t = null;", Collections.emptyMap(), null);
            assertNotNull(source);
            assertTrue(source.contains("import com.example.sut.*;"));
        } finally {
            Properties.TARGET_CLASS = previousTarget;
        }
    }

    @Test
    void generatedSnippetIncludesMockCompatibilityHelper() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method buildStatementClassSource = ExecutableSnippetEngine.class.getDeclaredMethod(
                "buildStatementClassSource", String.class, String.class, Map.class, String.class);
        buildStatementClassSource.setAccessible(true);

        String source = (String) buildStatementClassSource.invoke(
                engine, "k2", "Object o = mock(Runnable.class);", Collections.emptyMap(), null);

        assertNotNull(source);
        assertTrue(source.contains("private static <T> T mock(Class<T> type)"));
        assertTrue(source.contains("defaultValue("));
    }

    @Test
    void executeStatementResolvesAnonymousClassMethodTypesFromSnippetContext() throws Throwable {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        String previousTarget = Properties.TARGET_CLASS;
        try {
            Properties.TARGET_CLASS = "org.evosuite.Properties";
            ExecutableSnippetEngine.StatementResult result = engine.executeStatement(
                    "org.evosuite.testcase.execution.CodeUnderTestException seed = null;\n"
                            + "Object controller = new Object() {\n"
                            + "  public EvosuiteError make() {\n"
                            + "    return null;\n"
                            + "  }\n"
                            + "};",
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    "controller");

            assertNotNull(result.getReturnValue());
        } finally {
            Properties.TARGET_CLASS = previousTarget;
        }
    }

    @Test
    void generatedSnippetAvoidsAmbiguousDateImportsFromBindings() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method buildStatementClassSource = ExecutableSnippetEngine.class.getDeclaredMethod(
                "buildStatementClassSource", String.class, String.class, Map.class, String.class);
        buildStatementClassSource.setAccessible(true);

        Class<?> bindingClass = Class.forName("org.evosuite.testcase.execution.ExecutableSnippetEngine$Binding");
        java.lang.reflect.Constructor<?> bindingCtor =
                bindingClass.getDeclaredConstructor(Type.class, Object.class);
        bindingCtor.setAccessible(true);

        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("utilDate", bindingCtor.newInstance(java.util.Date.class, null));
        bindings.put("sqlDate", bindingCtor.newInstance(java.sql.Date.class, null));

        String source = (String) buildStatementClassSource.invoke(
                engine, "k_date_collision", "int x = 1;", bindings, null);

        assertNotNull(source);
        assertFalse(source.contains("import java.sql.*;"),
                "Binding package wildcard imports can cause Date ambiguity");
        assertFalse(source.contains("import java.sql.Date;"),
                "Date single-type import should be skipped when colliding");
        assertFalse(source.contains("import java.util.Date;"),
                "Date single-type import should be skipped when colliding");
    }

    @Test
    void snippetImportFilterRejectsInternalJdkPackages() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method forbidden = ExecutableSnippetEngine.class.getDeclaredMethod(
                "isForbiddenSnippetImportPackage", String.class);
        forbidden.setAccessible(true);

        assertTrue((Boolean) forbidden.invoke(engine, "jdk.javadoc.internal.api"));
        assertTrue((Boolean) forbidden.invoke(engine, "sun.misc"));
        assertTrue((Boolean) forbidden.invoke(engine, "com.sun.tools.javac"));
        assertFalse((Boolean) forbidden.invoke(engine, "org.w3c.dom"));
        assertFalse((Boolean) forbidden.invoke(engine, "javax.xml.parsers"));
    }

    @Test
    void sanitizeSourceRemovesNotAStatementLines() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitize = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeSourceForKnownCompileIssues", String.class, String.class);
        sanitize.setAccessible(true);

        String source = "class X {\n"
                + "  void m() {\n"
                + "    int x = 0;\n"
                + "    x + 1;\n"
                + "  }\n"
                + "}\n";
        String diagnostics = "/tmp/X.java:4: error: not a statement";

        String sanitized = (String) sanitize.invoke(engine, source, diagnostics);
        assertNotNull(sanitized);
        assertTrue(sanitized.contains("removed uncompilable llm statement"));
        assertFalse(sanitized.contains("x + 1;"));
    }

    @Test
    void buildStatementClassSourceRemovesUnmatchedTopLevelClosingBracesFromBody() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method build = ExecutableSnippetEngine.class.getDeclaredMethod(
                "buildStatementClassSource", String.class, String.class, Map.class, String.class);
        build.setAccessible(true);
        Class<?> bindingClass = Class.forName("org.evosuite.testcase.execution.ExecutableSnippetEngine$Binding");
        java.lang.reflect.Constructor<?> bindingCtor =
                bindingClass.getDeclaredConstructor(Type.class, Object.class);
        bindingCtor.setAccessible(true);
        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("tracked", bindingCtor.newInstance(Object.class, null));

        String body = "int x = 1;\n"
                + "}\n"
                + "int y = 2;";
        String generated = (String) build.invoke(engine, "k_unmatched_brace", body, bindings, null);

        assertNotNull(generated);
        assertTrue(generated.contains("removed unmatched top-level closing brace"));
        assertTrue(generated.contains("__vars.put("),
                "Variable writeback should remain inside run(...) after sanitization");
    }

    @Test
    void executeStatementTemporarilyEnablesMockFrameworkWhenGuiMockingIsActive() throws Throwable {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        boolean previousReplaceGui = Properties.REPLACE_GUI;
        boolean previouslyEnabled = MockFramework.isEnabled();
        try {
            Properties.REPLACE_GUI = true;
            MockFramework.disable();

            ExecutableSnippetEngine.StatementResult result = engine.executeStatement(
                    "boolean enabled = org.evosuite.runtime.mock.MockFramework.isEnabled();",
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    "enabled");

            assertEquals(Boolean.TRUE, result.getReturnValue());
            assertFalse(MockFramework.isEnabled(),
                    "Snippet execution should restore the previous mock framework state");
        } finally {
            if (previouslyEnabled) {
                MockFramework.enable();
            } else {
                MockFramework.disable();
            }
            Properties.REPLACE_GUI = previousReplaceGui;
        }
    }

    @Test
    void sanitizeSourceReturnsNullForUnrelatedDiagnostics() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitize = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeSourceForKnownCompileIssues", String.class, String.class);
        sanitize.setAccessible(true);

        String source = "class X { void m() {} }";
        String diagnostics = "/tmp/X.java:1: error: method does not override or implement a method from a supertype";

        String sanitized = (String) sanitize.invoke(engine, source, diagnostics);
        assertNull(sanitized);
    }

    @Test
    void normalizeClasspathEntryStripsTrailingBackslashAndFilePrefix() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method normalize = ExecutableSnippetEngine.class.getDeclaredMethod(
                "normalizeClasspathEntry", String.class);
        normalize.setAccessible(true);

        String normalized = (String) normalize.invoke(engine, "file:/tmp/lib/\\");

        assertNotNull(normalized);
        assertFalse(normalized.endsWith("\\"));
        assertFalse(normalized.startsWith("file:"));
        assertTrue(normalized.contains("/tmp/lib"));
    }

    @Test
    void sanitizeClasspathSanitizesUnreadableJarFromDiagnostics() throws Exception {
        File tempDir = Files.createTempDirectory("evosuite-snippet-diagnostics-").toFile();
        tempDir.deleteOnExit();
        File safeJar = new File(tempDir, "a.jar");
        File malformedJar = new File(tempDir, "batik-squiggle.jar");
        createJarWithManifestClassPath(safeJar, null);
        createJarWithManifestClassPath(malformedJar, "\\");

        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitizeClasspath = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeClasspathForKnownCompileIssues", String.class, String.class);
        sanitizeClasspath.setAccessible(true);

        String cp = safeJar.getAbsolutePath() + File.pathSeparator + malformedJar.getAbsolutePath();
        String diagnostics = "error: error reading " + malformedJar.getAbsolutePath() + "; "
                + "java.net.URISyntaxException: Illegal character in path at index 29: file:/sut/lib/\\";

        String sanitized = (String) sanitizeClasspath.invoke(engine, cp, diagnostics);

        assertNotNull(sanitized);
        assertFalse(sanitized.contains(malformedJar.getAbsolutePath()));
        assertTrue(sanitized.contains(safeJar.getAbsolutePath()));
        assertTrue(sanitized.contains("evosuite-snippet-compiler-cp-"));
    }

    @Test
    void sanitizeClasspathEntryReplacesJarWithMalformedManifestClasspath() throws Exception {
        File malformedJar = File.createTempFile("evosuite-malformed-manifest-", ".jar");
        malformedJar.deleteOnExit();
        createJarWithManifestClassPath(malformedJar, "lib/\\ batik-squiggle.jar");

        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitizeEntry = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeClasspathEntryForCompiler", String.class);
        sanitizeEntry.setAccessible(true);

        String sanitized = (String) sanitizeEntry.invoke(engine, malformedJar.getAbsolutePath());

        assertNotNull(sanitized);
        assertFalse(sanitized.endsWith(".jar"),
                "Malformed manifest jar should be replaced with extracted directory");
        assertTrue(new File(sanitized).isDirectory(),
                "Sanitized snippet classpath entry should be an existing directory");
    }

    @Test
    void buildCompilationClasspathSanitizesMalformedJarFromUrlClassLoader() throws Exception {
        File malformedJar = File.createTempFile("evosuite-malformed-loader-", ".jar");
        malformedJar.deleteOnExit();
        createJarWithManifestClassPath(malformedJar, "lib/\\ batik-squiggle.jar");

        String previousCp = Properties.CP;
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{malformedJar.toURI().toURL()}, null)) {
            Properties.CP = "";

            ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
            Method buildClasspath = ExecutableSnippetEngine.class.getDeclaredMethod(
                    "buildCompilationClasspath", ClassLoader.class);
            buildClasspath.setAccessible(true);

            String classpath = (String) buildClasspath.invoke(engine, loader);

            assertNotNull(classpath);
            assertFalse(classpath.contains(malformedJar.getAbsolutePath()),
                    "Malformed jar from URLClassLoader should be replaced before reaching javac");
            assertTrue(classpath.contains("evosuite-snippet-compiler-cp-"),
                    "Expected sanitized extracted directory in snippet compiler classpath");
        } finally {
            Properties.CP = previousCp;
        }
    }

    @Test
    void buildCompilationClasspathSanitizesJarsReferencingMalformedManifestJar() throws Exception {
        File tempDir = Files.createTempDirectory("evosuite-malformed-loader-ref-").toFile();
        tempDir.deleteOnExit();
        File malformedJar = new File(tempDir, "batik-squiggle.jar");
        File referencingJar = new File(tempDir, "batik-script.jar");
        createJarWithManifestClassPath(malformedJar, "\\");
        createJarWithManifestClassPath(referencingJar, malformedJar.getName());

        String previousCp = Properties.CP;
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{referencingJar.toURI().toURL(), malformedJar.toURI().toURL()}, null)) {
            Properties.CP = "";

            ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
            Method buildClasspath = ExecutableSnippetEngine.class.getDeclaredMethod(
                    "buildCompilationClasspath", ClassLoader.class);
            buildClasspath.setAccessible(true);

            String classpath = (String) buildClasspath.invoke(engine, loader);

            assertNotNull(classpath);
            assertFalse(classpath.contains(referencingJar.getAbsolutePath()),
                    "Jar that still points at a malformed dependency must also be sanitized");
            assertFalse(classpath.contains(malformedJar.getAbsolutePath()),
                    "Malformed manifest jar should be replaced before reaching javac");
            assertTrue(classpath.contains("evosuite-snippet-compiler-cp-"),
                    "Expected sanitized extracted directories in snippet compiler classpath");
        } finally {
            Properties.CP = previousCp;
        }
    }

    @Test
    void buildCompilationClasspathExpandsManifestOnlyPathingJarAndPreservesSafeEntries() throws Exception {
        File tempDir = Files.createTempDirectory("evosuite-pathing-jar-").toFile();
        tempDir.deleteOnExit();
        File safeJar = new File(tempDir, "junit-jupiter-api.jar");
        File malformedJar = new File(tempDir, "batik-squiggle.jar");
        File pathingJar = new File(tempDir, "EvoSuite_pathingJar123.jar");
        createJarWithManifestClassPath(safeJar, null);
        createJarWithManifestClassPath(malformedJar, "\\");
        createJarWithManifestClassPath(pathingJar, safeJar.getName() + " " + malformedJar.getName());

        String previousCp = Properties.CP;
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{pathingJar.toURI().toURL()}, null)) {
            Properties.CP = "";

            ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
            Method buildClasspath = ExecutableSnippetEngine.class.getDeclaredMethod(
                    "buildCompilationClasspath", ClassLoader.class);
            buildClasspath.setAccessible(true);

            String classpath = (String) buildClasspath.invoke(engine, loader);

            assertNotNull(classpath);
            assertFalse(classpath.contains(pathingJar.getAbsolutePath()),
                    "Manifest-only pathing jars should be expanded instead of passed to javac");
            assertTrue(classpath.contains(safeJar.getAbsolutePath()),
                    "Safe manifest entries from the pathing jar must be preserved");
            assertFalse(classpath.contains(malformedJar.getAbsolutePath()),
                    "Malformed manifest dependency should still be sanitized after expansion");
            assertTrue(classpath.contains("evosuite-snippet-compiler-cp-"),
                    "Expanded malformed dependency should still be replaced with a sanitized directory");
        } finally {
            Properties.CP = previousCp;
        }
    }

    @Test
    void buildCompilerArgumentsDisablesAnnotationProcessing() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method buildCompilerArguments = ExecutableSnippetEngine.class.getDeclaredMethod(
                "buildCompilerArguments", String.class, Path.class);
        buildCompilerArguments.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) buildCompilerArguments.invoke(
                engine, "/tmp/a.jar", new File("/tmp/Snippet.java").toPath());

        assertNotNull(args);
        assertTrue(args.contains("-proc:none"),
                "Snippet compiler arguments should disable annotation processing");
    }

    @Test
    void sanitizeSourceRemovesCannotFindSymbolLines() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitize = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeSourceForKnownCompileIssues", String.class, String.class);
        sanitize.setAccessible(true);

        String source = "class X {\n"
                + "  void m() {\n"
                + "    MissingType value = null;\n"
                + "    int x = 1;\n"
                + "  }\n"
                + "}\n";
        String diagnostics = "/tmp/X.java:3: error: cannot find symbol";

        String sanitized = (String) sanitize.invoke(engine, source, diagnostics);
        assertNotNull(sanitized);
        assertTrue(sanitized.contains("removed uncompilable llm statement"));
        assertFalse(sanitized.contains("MissingType value = null;"));
    }

    @Test
    void sanitizeSourceAddsFallbackReturnForMissingReturnDiagnostic() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitize = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeSourceForKnownCompileIssues", String.class, String.class);
        sanitize.setAccessible(true);

        String source = "public class X {\n"
                + "  public static Object run(java.util.Map<String,Object> __vars) throws Throwable {\n"
                + "    int x = 1;\n"
                + "  }\n"
                + "}\n";
        String diagnostics = "/tmp/X.java:4: error: missing return statement";

        String sanitized = (String) sanitize.invoke(engine, source, diagnostics);
        assertNotNull(sanitized);
        assertTrue(sanitized.contains("return null;"));
    }

    @Test
    void sanitizeSourceAddsFallbackReturnForNestedMethodMissingReturn() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitize = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeSourceForKnownCompileIssues", String.class, String.class);
        sanitize.setAccessible(true);

        String source = "public class X {\n"
                + "  public static Object run(java.util.Map<String,Object> __vars) throws Throwable {\n"
                + "    Object o = new Object() {\n"
                + "      public boolean ok() {\n"
                + "        int x = 1;\n"
                + "      }\n"
                + "    };\n"
                + "    return null;\n"
                + "  }\n"
                + "}\n";
        String diagnostics = "/tmp/X.java:6: error: missing return statement";

        String sanitized = (String) sanitize.invoke(engine, source, diagnostics);
        assertNotNull(sanitized);
        assertTrue(sanitized.contains("return false;"));
        assertFalse(sanitized.contains("removed uncompilable llm statement"));
    }

    @Test
    void sanitizeSourceRemovesAbstractInstantiationLine() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitize = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeSourceForKnownCompileIssues", String.class, String.class);
        sanitize.setAccessible(true);

        String source = "class X {\n"
                + "  void m() {\n"
                + "    SessionInfoProvider p = new SessionInfoProvider();\n"
                + "    int x = 1;\n"
                + "  }\n"
                + "}\n";
        String diagnostics = "/tmp/X.java:3: error: SessionInfoProvider is abstract; cannot be instantiated";

        String sanitized = (String) sanitize.invoke(engine, source, diagnostics);
        assertNotNull(sanitized);
        assertTrue(sanitized.contains("removed uncompilable llm statement"));
        assertFalse(sanitized.contains("new SessionInfoProvider()"));
        assertTrue(sanitized.contains("int x = 1;"));
    }

    @Test
    void sanitizeSourceRemovesProtectedConstructorAccessLine() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitize = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeSourceForKnownCompileIssues", String.class, String.class);
        sanitize.setAccessible(true);

        String source = "class X {\n"
                + "  void m() {\n"
                + "    RIFManager manager = new RIFManager(\"x\");\n"
                + "    int y = 2;\n"
                + "  }\n"
                + "}\n";
        String diagnostics = "/tmp/X.java:3: error: RIFManager(String) has protected access in RIFManager";

        String sanitized = (String) sanitize.invoke(engine, source, diagnostics);
        assertNotNull(sanitized);
        assertTrue(sanitized.contains("removed uncompilable llm statement"));
        assertFalse(sanitized.contains("new RIFManager(\"x\")"));
        assertTrue(sanitized.contains("int y = 2;"));
    }

    @Test
    void sanitizeSourceRemovesIncompatibleTypesLine() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitize = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeSourceForKnownCompileIssues", String.class, String.class);
        sanitize.setAccessible(true);

        String source = "class X {\n"
                + "  void m() {\n"
                + "    String s = 1;\n"
                + "    int y = 2;\n"
                + "  }\n"
                + "}\n";
        String diagnostics = "/tmp/X.java:3: error: incompatible types: int cannot be converted to String";

        String sanitized = (String) sanitize.invoke(engine, source, diagnostics);
        assertNotNull(sanitized);
        assertTrue(sanitized.contains("removed uncompilable llm statement"));
        assertFalse(sanitized.contains("String s = 1;"));
        assertTrue(sanitized.contains("int y = 2;"));
    }

    @Test
    void sanitizeSourceRemovesStatementsOutsideMethodsLines() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitize = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeSourceForKnownCompileIssues", String.class, String.class);
        sanitize.setAccessible(true);

        String source = "public class X {\n"
                + "  public static Object run(java.util.Map<String,Object> __vars) throws Throwable {\n"
                + "    return null;\n"
                + "  }\n"
                + "  int y = 1;\n"
                + "}\n";
        String diagnostics =
                "/tmp/X.java:5: error: statements not expected outside of methods and initializers";

        String sanitized = (String) sanitize.invoke(engine, source, diagnostics);
        assertNotNull(sanitized);
        assertTrue(sanitized.contains("removed uncompilable llm statement"));
        assertFalse(sanitized.contains("int y = 1;"));
    }

    @Test
    void sanitizeForbiddenPackageUsageRemovesInternalReferences() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitize = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeForbiddenPackageUsageInSource", String.class);
        sanitize.setAccessible(true);

        String source = "class X {\n"
                + "  void m() {\n"
                + "    Object o = jdk.javadoc.internal.api.JavadocTask.class;\n"
                + "    int x = 1;\n"
                + "  }\n"
                + "}\n";
        String sanitized = (String) sanitize.invoke(engine, source);

        assertNotNull(sanitized);
        assertTrue(sanitized.contains("removed forbidden jdk internal reference"));
        assertFalse(sanitized.contains("jdk.javadoc.internal.api"));
        assertTrue(sanitized.contains("int x = 1;"));
    }

    @Test
    void sanitizeForbiddenPackageUsageRemovesSetSecurityManagerCalls() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitize = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeForbiddenPackageUsageInSource", String.class);
        sanitize.setAccessible(true);

        String source = "class X {\n"
                + "  void m() {\n"
                + "    System.setSecurityManager(null);\n"
                + "    int x = 1;\n"
                + "  }\n"
                + "}\n";
        String sanitized = (String) sanitize.invoke(engine, source);

        assertNotNull(sanitized);
        assertTrue(sanitized.contains("removed forbidden security-manager mutation"));
        assertFalse(sanitized.contains("System.setSecurityManager(null)"));
        assertTrue(sanitized.contains("int x = 1;"));
    }

    @Test
    void sanitizeForbiddenPackageUsageRemovesMultilineSetSecurityManagerAnonymousClass() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitize = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeForbiddenPackageUsageInSource", String.class);
        sanitize.setAccessible(true);

        String source = "class X {\n"
                + "  void m() {\n"
                + "    try {\n"
                + "      System.setSecurityManager(new SecurityManager() {\n"
                + "        @Override\n"
                + "        public void checkPermission(java.security.Permission perm) {\n"
                + "        }\n"
                + "        @Override\n"
                + "        public void checkExit(int status) {\n"
                + "          throw new SecurityException(\"exit:\" + status);\n"
                + "        }\n"
                + "      });\n"
                + "    } finally {\n"
                + "      int x = 1;\n"
                + "    }\n"
                + "  }\n"
                + "}\n";
        String sanitized = (String) sanitize.invoke(engine, source);

        assertNotNull(sanitized);
        assertTrue(sanitized.contains("removed forbidden security-manager mutation"));
        assertFalse(sanitized.contains("System.setSecurityManager(new SecurityManager()"));
        assertFalse(sanitized.contains("checkPermission"));
        assertFalse(sanitized.contains("checkExit"));
        assertTrue(sanitized.contains("int x = 1;"));
    }

    @Test
    void sanitizeForbiddenPackageUsageRemovesHeavyweightGuiConstructionSimpleNames() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitize = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeForbiddenPackageUsageInSource", String.class);
        sanitize.setAccessible(true);

        String source = "class X {\n"
                + "  void m() {\n"
                + "    javax.swing.JFrame keep = null;\n"
                + "    JFrame frame = new JFrame();\n"
                + "    int x = 1;\n"
                + "  }\n"
                + "}\n";
        String sanitized = (String) sanitize.invoke(engine, source);

        assertNotNull(sanitized);
        assertFalse(sanitized.contains("removed forbidden heavyweight gui construction"));
        assertFalse(sanitized.contains("new JFrame("));
        assertTrue(sanitized.contains("new org.evosuite.runtime.mock.javax.swing.MockJFrame("));
        assertTrue(sanitized.contains("int x = 1;"));
    }

    @Test
    void sanitizeForbiddenPackageUsageRemovesHeavyweightGuiConstructionFqns() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitize = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeForbiddenPackageUsageInSource", String.class);
        sanitize.setAccessible(true);

        String source = "class X {\n"
                + "  void m() {\n"
                + "    Object w = new java.awt.Frame();\n"
                + "    Object d = new javax.swing.JDialog();\n"
                + "    int x = 2;\n"
                + "  }\n"
                + "}\n";
        String sanitized = (String) sanitize.invoke(engine, source);

        assertNotNull(sanitized);
        assertFalse(sanitized.contains("removed forbidden heavyweight gui construction"));
        assertFalse(sanitized.contains("new java.awt.Frame("));
        assertFalse(sanitized.contains("new javax.swing.JDialog("));
        assertTrue(sanitized.contains("new org.evosuite.runtime.mock.java.awt.MockFrame("));
        assertTrue(sanitized.contains("new org.evosuite.runtime.mock.javax.swing.MockJDialog("));
        assertTrue(sanitized.contains("int x = 2;"));
    }

    @Test
    void sanitizeForbiddenPackageUsageRewritesJWindowConstructionToMock() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitize = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeForbiddenPackageUsageInSource", String.class);
        sanitize.setAccessible(true);

        String source = "class X {\n"
                + "  void m() {\n"
                + "    Object w = new javax.swing.JWindow();\n"
                + "    int x = 3;\n"
                + "  }\n"
                + "}\n";
        String sanitized = (String) sanitize.invoke(engine, source);

        assertNotNull(sanitized);
        assertFalse(sanitized.contains("removed forbidden heavyweight gui construction"));
        assertFalse(sanitized.contains("new javax.swing.JWindow("));
        assertTrue(sanitized.contains("new org.evosuite.runtime.mock.javax.swing.MockJWindow("));
        assertTrue(sanitized.contains("int x = 3;"));
    }

    @Test
    void sanitizeForbiddenPackageUsageRewritesFileDialogConstructionToMock() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitize = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeForbiddenPackageUsageInSource", String.class);
        sanitize.setAccessible(true);

        String source = "class X {\n"
                + "  void m() {\n"
                + "    Object d = new java.awt.FileDialog((java.awt.Frame)null, \"t\", java.awt.FileDialog.LOAD);\n"
                + "    int x = 4;\n"
                + "  }\n"
                + "}\n";
        String sanitized = (String) sanitize.invoke(engine, source);

        assertNotNull(sanitized);
        assertFalse(sanitized.contains("removed forbidden heavyweight gui construction"));
        assertFalse(sanitized.contains("new java.awt.FileDialog("));
        assertTrue(sanitized.contains("new org.evosuite.runtime.mock.java.awt.MockFileDialog("));
        assertTrue(sanitized.contains("int x = 4;"));
    }

    @Test
    void sanitizeForbiddenPackageUsageRewritesFileRandomAccessAndFileOutputStreamWhenVfsEnabled() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitize = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeForbiddenPackageUsageInSource", String.class);
        sanitize.setAccessible(true);

        boolean previousVirtualFs = Properties.VIRTUAL_FS;
        try {
            Properties.VIRTUAL_FS = true;
            String source = "class X {\n"
                    + "  void m() throws Exception {\n"
                    + "    java.io.File f = new java.io.File(\"x.bin\");\n"
                    + "    java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, \"rw\");\n"
                    + "    java.io.FileOutputStream out = new java.io.FileOutputStream(f);\n"
                    + "  }\n"
                    + "}\n";
            String sanitized = (String) sanitize.invoke(engine, source);

            assertNotNull(sanitized);
            assertFalse(sanitized.contains("new java.io.File("));
            assertFalse(sanitized.contains("new java.io.RandomAccessFile("));
            assertFalse(sanitized.contains("new java.io.FileOutputStream("));
            assertTrue(sanitized.contains("new org.evosuite.runtime.mock.java.io.MockFile("));
            assertTrue(sanitized.contains("new org.evosuite.runtime.mock.java.io.MockRandomAccessFile("));
            assertTrue(sanitized.contains("new org.evosuite.runtime.mock.java.io.MockFileOutputStream("));
        } finally {
            Properties.VIRTUAL_FS = previousVirtualFs;
        }
    }

    @Test
    void sanitizeForbiddenPackageUsageDoesNotRewriteFileWhenVfsDisabled() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method sanitize = ExecutableSnippetEngine.class.getDeclaredMethod(
                "sanitizeForbiddenPackageUsageInSource", String.class);
        sanitize.setAccessible(true);

        boolean previousVirtualFs = Properties.VIRTUAL_FS;
        try {
            Properties.VIRTUAL_FS = false;
            String source = "class X {\n"
                    + "  void m() {\n"
                    + "    java.io.File f = new java.io.File(\"x.bin\");\n"
                    + "  }\n"
                    + "}\n";
            String sanitized = (String) sanitize.invoke(engine, source);

            assertNotNull(sanitized);
            assertTrue(sanitized.contains("new java.io.File(\"x.bin\")"));
            assertFalse(sanitized.contains("MockFile("));
        } finally {
            Properties.VIRTUAL_FS = previousVirtualFs;
        }
    }

    @Test
    void classLoaderKeyUsesIdentity() throws Exception {
        ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;
        Method keyMethod = ExecutableSnippetEngine.class.getDeclaredMethod(
                "classLoaderKey", ClassLoader.class);
        keyMethod.setAccessible(true);

        ClassLoader loaderA = new ClassLoader(null) {
        };
        ClassLoader loaderB = new ClassLoader(null) {
        };

        String keyA = (String) keyMethod.invoke(engine, loaderA);
        String keyB = (String) keyMethod.invoke(engine, loaderB);
        String keyANull = (String) keyMethod.invoke(engine, new Object[]{null});

        assertNotNull(keyA);
        assertNotNull(keyB);
        assertFalse(keyA.equals(keyB), "Different classloader instances must have distinct keys");
        assertTrue("null".equals(keyANull));
    }

    private static void createJarWithManifestClassPath(File jarFile, String classPath) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (classPath != null) {
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
        }
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jarFile), manifest)) {
            // Empty jar is enough; snippet classpath sanitization only inspects metadata.
        }
        jarFile.deleteOnExit();
    }
}
