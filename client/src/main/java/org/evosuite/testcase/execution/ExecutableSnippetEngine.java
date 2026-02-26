/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testcase.execution;

import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compiles and executes Java snippets used by fallback parser artifacts.
 */
public final class ExecutableSnippetEngine {

    public static final ExecutableSnippetEngine INSTANCE = new ExecutableSnippetEngine();

    private static final class Binding {
        private final Type type;
        private final Object value;

        private Binding(Type type, Object value) {
            this.type = type;
            this.value = value;
        }
    }

    public static final class StatementResult {
        private final Object returnValue;
        private final Map<String, Object> updatedValues;

        private StatementResult(Object returnValue, Map<String, Object> updatedValues) {
            this.returnValue = returnValue;
            this.updatedValues = updatedValues;
        }

        public Object getReturnValue() {
            return returnValue;
        }

        public Map<String, Object> getUpdatedValues() {
            return updatedValues;
        }
    }

    private static final class CompiledSnippet {
        private final Method method;

        private CompiledSnippet(Method method) {
            this.method = method;
        }
    }

    private static final class SnippetCompilationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private SnippetCompilationException(String message) {
            super(message);
        }

        private SnippetCompilationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class SnippetEngineRuntimeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private SnippetEngineRuntimeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Map<String, CompiledSnippet> cache = new ConcurrentHashMap<>();
    private final Path compilationDir;
    private final URLClassLoader snippetClassLoader;
    private final AtomicInteger compileFailures = new AtomicInteger();
    private final AtomicInteger runtimeFailures = new AtomicInteger();
    private final AtomicInteger statementExecutionFailures = new AtomicInteger();
    private final AtomicInteger assertionEvaluationFailures = new AtomicInteger();

    private ExecutableSnippetEngine() {
        this.compilationDir = new File(System.getProperty("java.io.tmpdir"), "evosuite-snippets").toPath();
        try {
            this.snippetClassLoader = new URLClassLoader(
                    new URL[]{compilationDir.toUri().toURL()},
                    Thread.currentThread().getContextClassLoader());
        } catch (IOException e) {
            throw new IllegalStateException("Could not initialize snippet class loader", e);
        }
    }

    public StatementResult executeStatement(String sourceCode,
                                            Map<String, Type> variableTypes,
                                            Map<String, Object> variableValues,
                                            String returnExpression) throws Throwable {
        try {
            Map<String, Binding> bindings = toBindings(variableTypes, variableValues);
            String cacheKey = "STMT|" + sourceCode + "|" + returnExpression + "|" + signature(bindings);
            CompiledSnippet snippet = cache.computeIfAbsent(cacheKey,
                    key -> compileSnippet(key, buildStatementClassSource(key, sourceCode, bindings, returnExpression)));
            Map<String, Object> values = new LinkedHashMap<>(variableValues);
            Object returnValue = invoke(snippet, values);
            return new StatementResult(returnValue, values);
        } catch (Throwable t) {
            increment(RuntimeVariable.LLM_Fallback_Statement_Execution_Failures, statementExecutionFailures);
            if (t instanceof SnippetEngineRuntimeException) {
                increment(RuntimeVariable.LLM_Fallback_Snippet_Runtime_Failures, runtimeFailures);
            }
            throw t;
        }
    }

    public boolean evaluateAssertion(String assertionCode,
                                     Map<String, Type> variableTypes,
                                     Map<String, Object> variableValues) throws Throwable {
        try {
            Map<String, Binding> bindings = toBindings(variableTypes, variableValues);
            String cacheKey = "ASSERT|" + assertionCode + "|" + signature(bindings);
            CompiledSnippet snippet = cache.computeIfAbsent(cacheKey,
                    key -> compileSnippet(key, buildAssertionClassSource(key, assertionCode, bindings)));
            Map<String, Object> values = new LinkedHashMap<>(variableValues);
            Object result = invoke(snippet, values);
            return Boolean.TRUE.equals(result);
        } catch (AssertionError assertionError) {
            return false;
        } catch (Throwable t) {
            increment(RuntimeVariable.LLM_Fallback_Assertion_Evaluation_Failures, assertionEvaluationFailures);
            if (t instanceof SnippetEngineRuntimeException) {
                increment(RuntimeVariable.LLM_Fallback_Snippet_Runtime_Failures, runtimeFailures);
            }
            throw t;
        }
    }

    private Object invoke(CompiledSnippet snippet, Map<String, Object> values) throws Throwable {
        try {
            return snippet.method.invoke(null, values);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        } catch (IllegalAccessException | IllegalArgumentException e) {
            throw new SnippetEngineRuntimeException("Could not execute compiled snippet", e);
        }
    }

    private Map<String, Binding> toBindings(Map<String, Type> variableTypes, Map<String, Object> variableValues) {
        Map<String, Binding> bindings = new LinkedHashMap<>();
        for (Map.Entry<String, Type> entry : variableTypes.entrySet()) {
            String name = entry.getKey();
            bindings.put(name, new Binding(entry.getValue(), variableValues.get(name)));
        }
        return bindings;
    }

    private CompiledSnippet compileSnippet(String key, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            increment(RuntimeVariable.LLM_Fallback_Snippet_Compile_Failures, compileFailures);
            throw new SnippetCompilationException("No Java compiler available in current runtime");
        }

        try {
            Files.createDirectories(compilationDir);
            String className = classNameFor(key);
            Path sourceFile = compilationDir.resolve(className + ".java");
            Path classFile = compilationDir.resolve(className + ".class");
            Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));

            String classpath = buildCompilationClasspath();
            int compilationResult = compiler.run(
                    null,
                    null,
                    null,
                    "-classpath", classpath,
                    "-d", compilationDir.toString(),
                    sourceFile.toString()
            );
            if (compilationResult != 0) {
                increment(RuntimeVariable.LLM_Fallback_Snippet_Compile_Failures, compileFailures);
                throw new SnippetCompilationException("Snippet compilation failed for " + className);
            }

            Class<?> compiledClass = Class.forName(className, true, snippetClassLoader);
            Method method = compiledClass.getMethod("run", Map.class);
            safeDelete(sourceFile);
            safeDelete(classFile);
            return new CompiledSnippet(method);
        } catch (IOException | ReflectiveOperationException e) {
            increment(RuntimeVariable.LLM_Fallback_Snippet_Compile_Failures, compileFailures);
            throw new SnippetCompilationException("Could not compile snippet", e);
        }
    }

    private String buildStatementClassSource(String key,
                                             String sourceCode,
                                             Map<String, Binding> bindings,
                                             String returnExpression) {
        String className = classNameFor(key);
        StringBuilder src = new StringBuilder();
        src.append("public class ").append(className).append(" {\n");
        src.append("  @SuppressWarnings(\"unchecked\")\n");
        src.append("  public static Object run(java.util.Map<String,Object> __vars) throws Throwable {\n");
        appendVariableDeclarations(src, bindings);
        src.append(sourceCode).append("\n");
        appendVariableWriteBack(src, bindings);
        if (returnExpression != null && !returnExpression.trim().isEmpty()) {
            src.append("    return ").append(returnExpression).append(";\n");
        } else {
            src.append("    return null;\n");
        }
        src.append("  }\n");
        src.append("}\n");
        return src.toString();
    }

    private String buildAssertionClassSource(String key,
                                             String assertionCode,
                                             Map<String, Binding> bindings) {
        String className = classNameFor(key);
        StringBuilder src = new StringBuilder();
        src.append("import static org.junit.Assert.*;\n");
        src.append("public class ").append(className).append(" {\n");
        src.append("  @SuppressWarnings(\"unchecked\")\n");
        src.append("  public static Object run(java.util.Map<String,Object> __vars) throws Throwable {\n");
        appendVariableDeclarations(src, bindings);
        src.append(assertionCode).append("\n");
        src.append("    return Boolean.TRUE;\n");
        src.append("  }\n");
        src.append("}\n");
        return src.toString();
    }

    private void appendVariableDeclarations(StringBuilder src, Map<String, Binding> bindings) {
        for (Map.Entry<String, Binding> entry : bindings.entrySet()) {
            String name = entry.getKey();
            Type type = entry.getValue().type;
            Class<?> raw = rawTypeFor(type);
            src.append("    ").append(typeName(raw)).append(" ").append(name).append(" = ");
            src.append(readExpression(raw, name)).append(";\n");
        }
    }

    private void appendVariableWriteBack(StringBuilder src, Map<String, Binding> bindings) {
        for (String name : bindings.keySet()) {
            src.append("    __vars.put(\"").append(escape(name)).append("\", ").append(name).append(");\n");
        }
    }

    private String readExpression(Class<?> rawType, String varName) {
        String accessor = "__vars.get(\"" + escape(varName) + "\")";
        if (!rawType.isPrimitive()) {
            return "(" + typeName(rawType) + ")" + accessor;
        }
        if (rawType == boolean.class) {
            return "((java.lang.Boolean)" + accessor + ").booleanValue()";
        }
        if (rawType == byte.class) {
            return "((java.lang.Number)" + accessor + ").byteValue()";
        }
        if (rawType == short.class) {
            return "((java.lang.Number)" + accessor + ").shortValue()";
        }
        if (rawType == int.class) {
            return "((java.lang.Number)" + accessor + ").intValue()";
        }
        if (rawType == long.class) {
            return "((java.lang.Number)" + accessor + ").longValue()";
        }
        if (rawType == float.class) {
            return "((java.lang.Number)" + accessor + ").floatValue()";
        }
        if (rawType == double.class) {
            return "((java.lang.Number)" + accessor + ").doubleValue()";
        }
        if (rawType == char.class) {
            return "((java.lang.Character)" + accessor + ").charValue()";
        }
        return "(" + typeName(rawType) + ")" + accessor;
    }

    private String typeName(Class<?> rawType) {
        if (rawType.isArray()) {
            return rawType.getCanonicalName();
        }
        if (rawType.isPrimitive()) {
            return rawType.getName();
        }
        String canonical = rawType.getCanonicalName();
        return canonical != null ? canonical : rawType.getName().replace('$', '.');
    }

    private String classNameFor(String key) {
        return "EvosuiteSnippet_" + hash(key);
    }

    private String buildCompilationClasspath() {
        Set<String> entries = new LinkedHashSet<>();
        String javaClassPath = System.getProperty("java.class.path");
        if (javaClassPath != null && !javaClassPath.trim().isEmpty()) {
            for (String entry : javaClassPath.split(File.pathSeparator)) {
                if (!entry.trim().isEmpty()) {
                    entries.add(entry.trim());
                }
            }
        }
        appendClassLoaderEntries(Thread.currentThread().getContextClassLoader(), entries);
        return String.join(File.pathSeparator, entries);
    }

    private void appendClassLoaderEntries(ClassLoader loader, Set<String> entries) {
        ClassLoader current = loader;
        while (current != null) {
            if (current instanceof URLClassLoader) {
                for (URL url : ((URLClassLoader) current).getURLs()) {
                    if (!"file".equalsIgnoreCase(url.getProtocol())) {
                        continue;
                    }
                    try {
                        String path = new File(url.toURI()).getAbsolutePath();
                        if (!path.isEmpty()) {
                            entries.add(path);
                        }
                    } catch (Exception ignored) {
                        // Best effort only.
                    }
                }
            }
            current = current.getParent();
        }
    }

    private Class<?> rawTypeFor(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            Type rawType = ((ParameterizedType) type).getRawType();
            if (rawType instanceof Class<?>) {
                return (Class<?>) rawType;
            }
        }
        if (type instanceof GenericArrayType) {
            Class<?> component = rawTypeFor(((GenericArrayType) type).getGenericComponentType());
            return java.lang.reflect.Array.newInstance(component, 0).getClass();
        }
        return Object.class;
    }

    private String signature(Map<String, Binding> bindings) {
        StringBuilder signature = new StringBuilder();
        for (Map.Entry<String, Binding> entry : bindings.entrySet()) {
            signature.append(entry.getKey())
                    .append(":")
                    .append(entry.getValue().type == null ? "java.lang.Object" : entry.getValue().type.getTypeName())
                    .append(";");
        }
        return signature.toString();
    }

    private String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                out.append(String.format("%02x", hashed[i]));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    private String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void safeDelete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best effort temp cleanup.
        }
    }

    public Map<String, Object> emptyValues() {
        return Collections.emptyMap();
    }

    private void increment(RuntimeVariable variable, AtomicInteger counter) {
        ClientServices.track(variable, counter.incrementAndGet());
    }

    public void resetMetricsForTesting() {
        compileFailures.set(0);
        runtimeFailures.set(0);
        statementExecutionFailures.set(0);
        assertionEvaluationFailures.set(0);
    }

    public int getCompileFailures() {
        return compileFailures.get();
    }

    public int getRuntimeFailures() {
        return runtimeFailures.get();
    }

    public int getStatementExecutionFailures() {
        return statementExecutionFailures.get();
    }

    public int getAssertionEvaluationFailures() {
        return assertionEvaluationFailures.get();
    }
}
