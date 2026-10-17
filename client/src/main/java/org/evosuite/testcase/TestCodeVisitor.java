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

import com.googlecode.gentyref.CaptureType;
import com.googlecode.gentyref.GenericTypeReflector;
import dk.brics.automaton.RegExp;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.evosuite.PackageInfo;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.assertion.*;
import org.evosuite.classpath.ResourceList;
import org.evosuite.runtime.LenientMockAnswer;
import org.evosuite.runtime.MockInvocationLimit;
import org.evosuite.runtime.PrivateAccess;
import org.evosuite.runtime.TooManyResourcesException;
import org.evosuite.runtime.ViolatedAssumptionAnswer;
import org.evosuite.runtime.mock.EvoSuiteMock;
import org.evosuite.setup.TestClusterUtils;
import org.evosuite.setup.TestUsageChecker;
// Presentation metadata is a neutral test-case concern.
import org.evosuite.testcase.TestPresentationMetadata;
import org.evosuite.testcase.fm.MethodDescriptor;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.environment.EnvironmentDataStatement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ConstantValue;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.NullReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.name.VariableNameStrategy;
import org.evosuite.testcase.variable.name.VariableNameStrategyFactory;
import org.evosuite.utils.NumberFormatter;
import org.evosuite.utils.ParameterizedTypeImpl;
import org.evosuite.utils.StringUtil;
import org.evosuite.utils.generic.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.util.*;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.toCollection;
import static org.evosuite.setup.GeneratedTestAccess.findAccessibleRenderableSupertype;
import static org.evosuite.setup.GeneratedTestAccess.getGeneratedTestPackageName;
import static org.evosuite.setup.GeneratedTestAccess.isClassLiteralReferenceableFromGeneratedTest;
import static org.evosuite.setup.GeneratedTestAccess.isDeclaredTypeNameableInGeneratedTest;
import static org.evosuite.setup.GeneratedTestAccess.isEmptyPackageName;
import static org.evosuite.setup.GeneratedTestAccess.isSamePackage;
import static org.evosuite.setup.GeneratedTestAccess.isTypeAccessibleFromGeneratedTest;

/**
 * The TestCodeVisitor is a visitor that produces a String representation of a
 * test case. This is the preferred way to produce executable code from EvoSuite
 * tests.
 *
 * @author Gordon Fraser
 */
public class TestCodeVisitor extends TestVisitor {

    private static final Logger logger = LoggerFactory.getLogger(TestCodeVisitor.class);

    /** Simple name -> whether java.lang declares a type of that name. */
    private static final Map<String, Boolean> JAVA_LANG_SIMPLE_NAMES =
            new java.util.concurrent.ConcurrentHashMap<>();

    protected StringBuilder testCode = new StringBuilder();

    protected static final String NEWLINE = System.getProperty("line.separator");

    protected final Map<Integer, Throwable> exceptions = new HashMap<>();

    protected TestCase test = null;

    protected final Map<Class<?>, String> classNames = new HashMap<>();

    /**
     * Rendering support for uninterpreted snippets; it reaches visitor state only through this host.
     */
    final SnippetSourceSupport snippetSupport = new SnippetSourceSupport(new SnippetSourceSupport.Host() {
        @Override
        public Map<Class<?>, String> classNames() {
            return classNames;
        }

        @Override
        public String getClassName(Class<?> clazz) {
            return TestCodeVisitor.this.getClassName(clazz);
        }

        @Override
        public boolean isLocalNameReserved(String candidate) {
            return TestCodeVisitor.this.isLocalNameReserved(candidate);
        }

        @Override
        public String ensureUniqueLocalVariableName(String baseName) {
            return TestCodeVisitor.this.ensureUniqueLocalVariableName(baseName);
        }
    });

    protected VariableNameStrategy variableNameStrategy = VariableNameStrategyFactory.get();
    private boolean customVariableNameStrategy = false;
    private boolean emitAssertions = true;
    private Boolean assertionsEnabledOverride = null;

    /**
     * Override the variable naming strategy used for code rendering.
     * Useful for rendering test code without triggering LLM-based naming
     * (e.g., when preparing prompts for the LLM naming strategy itself).
     * Once set, {@link #visitTestCase(TestCase)} will not reset it.
     */
    public void setVariableNameStrategy(VariableNameStrategy strategy) {
        this.variableNameStrategy = strategy;
        this.customVariableNameStrategy = true;
    }

    /**
     * Returns the current variable name strategy instance.
     */
    public VariableNameStrategy getVariableNameStrategyInstance() {
        return this.variableNameStrategy;
    }

    /**
     * Enables or disables assertion emission while still allowing the visitor to
     * collect statement and naming information.
     */
    public void setEmitAssertions(boolean emitAssertions) {
        this.emitAssertions = emitAssertions;
    }

    /**
     * Overrides the global assertion mode for this rendering only. This is
     * useful for canonical structural rendering, which must not depend on the
     * currently selected assertion-generation arm.
     */
    public void setAssertionsEnabledForRendering(boolean assertionsEnabled) {
        this.assertionsEnabledOverride = assertionsEnabled;
    }

    private boolean assertionsEnabledForRendering() {
        return assertionsEnabledOverride != null
                ? assertionsEnabledOverride
                : Properties.ASSERTIONS;
    }

    /**
     * Dictionaries for naming information.
     */
    protected Map<VariableReference, String> methodNames = new HashMap<>();
    protected Map<VariableReference, String> argumentNames = new HashMap<>();
    private int nullDeclarationCounter = 0;
    private final Map<VariableReference, String> declarationVariableNames = new IdentityHashMap<>();
    private static final Pattern ALL_NULL_MOCKITO_DORETURN_ARGS = Pattern.compile(
            "\\s*(?:\\([^)]*\\)\\s*)?null\\s*(?:,\\s*(?:\\([^)]*\\)\\s*)?null\\s*)*");
    private final Set<String> reservedLocalVariableNames = new LinkedHashSet<>();
    private final Map<Integer, List<Assertion>> deferredAssertionsByPosition = new HashMap<>();

    private Map<String, Map<VariableReference, String>> information = new HashMap<>();

    /**
     * Returns the string representation of the test code.
     *
     * @return a {@link java.lang.String} object.
     */
    public String getCode() {
        return testCode.toString();
    }

    /**
     * Retrieve a list of classes that need to be imported to make this unit
     * test compile.
     *
     * @return a {@link java.util.Set} object.
     */
    public Set<Class<?>> getImports() {
        return classNames.keySet().stream()
                .filter(this::shouldImportClass)
                .collect(toCollection(HashSet::new));
    }

    private boolean shouldImportClass(Class<?> clazz) {
        if (clazz == null || clazz.isArray()) {
            return false;
        }
        String renderedName = classNames.get(clazz);
        if (renderedName == null || renderedName.isEmpty()) {
            return false;
        }
        String canonicalName = clazz.getCanonicalName();
        if (canonicalName == null || canonicalName.isEmpty()) {
            return false;
        }
        if (!isTypeAccessibleFromGeneratedTest(clazz, getGeneratedTestPackageName())) {
            return false;
        }
        if (clazz.getEnclosingClass() != null
                && renderedName.indexOf('.') >= 0
                && testCode.indexOf(renderedName) >= 0) {
            return false;
        }
        return !canonicalName.equals(renderedName);
    }

    /**
     * Clears the recorded exceptions.
     */
    public void clearExceptions() {
        this.exceptions.clear();
    }

    /**
     * Sets the map of exceptions.
     *
     * @param exceptions a {@link java.util.Map} object.
     */
    public void setExceptions(Map<Integer, Throwable> exceptions) {
        this.exceptions.putAll(exceptions);
    }

    /**
     * Records an exception for a specific statement.
     *
     * @param statement a {@link org.evosuite.testcase.statements.Statement} object.
     * @param exception a {@link java.lang.Throwable} object.
     */
    public void setException(Statement statement, Throwable exception) {
        exceptions.put(statement.getPosition(), exception);
    }

    /**
     * Returns the exception recorded for a specific statement.
     *
     * @param statement a {@link org.evosuite.testcase.statements.Statement} object.
     * @return a {@link java.lang.Throwable} object.
     */
    protected Throwable getException(Statement statement) {
        return exceptions.getOrDefault(statement.getPosition(), null);
    }

    /**
     * Returns the class name of a variable.
     *
     * @param var a {@link org.evosuite.testcase.variable.VariableReference} object.
     * @return a {@link java.lang.String} object.
     */
    public String getClassName(VariableReference var) {
        return getDeclarationTypeName(var.getType());
    }

    /**
     * The type to write in a declaration or cast of a value of {@code type}. When a type
     * argument cannot be named, a wildcard in its place makes the variable (or a cast to it) a
     * capture type that is not assignable to the real G&lt;Hidden&gt; a field, parameter or
     * put() expects. The raw type converts to every parameterization (unchecked), so declare
     * and cast raw.
     */
    private String getDeclarationTypeName(Type type) {
        if (type instanceof ParameterizedType
                && ((ParameterizedType) type).getRawType() instanceof Class<?>
                && !containsUnnameableType(((ParameterizedType) type).getRawType())) {
            for (Type argument : ((ParameterizedType) type).getActualTypeArguments()) {
                if (containsUnnameableType(argument)) {
                    return getClassName((Class<?>) ((ParameterizedType) type).getRawType());
                }
            }
        }
        return getTypeName(type);
    }

    private String getTypeName(ParameterizedType type) {
        Class<?> rawClass = (Class<?>) type.getRawType();
        String ownerQualifier = getParameterizedOwnerQualifier(type);
        String name = ownerQualifier.isEmpty()
                ? getClassName(rawClass) : ownerQualifier + rawClass.getSimpleName();
        Type[] types = type.getActualTypeArguments();
        boolean isDefined = false;
        for (Type parameterType : types) {
            if (parameterType instanceof Class<?>
                    || parameterType instanceof ParameterizedType
                    || parameterType instanceof WildcardType
                    || parameterType instanceof GenericArrayType) {
                isDefined = true;
                break;
            }
        }
        if (isDefined) {
            if (types.length > 0) {
                name += "<";
                for (int i = 0; i < types.length; i++) {
                    if (i != 0) {
                        name += ", ";
                    }

                    if (objectViolatesTypeParameterBound(rawClass, i, types[i])
                            || violatesRecursiveTypeParameterBound(type, i)) {
                        // EvoSuite substitutes Object for an unresolved type variable,
                        // but for an F-bounded parameter (e.g. VisibilityChecker<T
                        // extends VisibilityChecker<T>>) Object is not within the bound
                        // and Foo<Object> never compiles. An unbounded wildcard is
                        // always legal, so emit Foo<?> instead.
                        name += "?";
                    } else if (containsUnnameableType(types[i])) {
                        name += getAccessibleTypeArgumentName(types[i]);
                    } else {
                        name += getTypeParameterName(types[i]);
                    }
                }
                name += ">";
            }
        }
        return name;
    }

    /**
     * Whether writing {@code type} in generated source would name a class that javac rejects
     * ("has private access", "has protected access"): a private or foreign non-public nested
     * class, or one in a package its module does not export. Arguments of a parameterized
     * type are visited too, because they are written out as well.
     */
    private static boolean containsUnnameableType(Type type) {
        if (type instanceof Class<?>) {
            Class<?> clazz = (Class<?>) type;
            while (clazz.isArray()) {
                clazz = clazz.getComponentType();
            }
            return !clazz.isPrimitive() && !isDeclaredTypeNameableInGeneratedTest(clazz);
        } else if (type instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) type;
            if (containsUnnameableType(parameterized.getRawType())) {
                return true;
            }
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (containsUnnameableType(argument)) {
                    return true;
                }
            }
            return false;
        } else if (type instanceof WildcardType) {
            WildcardType wildcard = (WildcardType) type;
            for (Type bound : wildcard.getUpperBounds()) {
                if (bound != null && containsUnnameableType(bound)) {
                    return true;
                }
            }
            for (Type bound : wildcard.getLowerBounds()) {
                if (bound != null && containsUnnameableType(bound)) {
                    return true;
                }
            }
            return false;
        } else if (type instanceof GenericArrayType) {
            return containsUnnameableType(((GenericArrayType) type).getGenericComponentType());
        }
        return false;
    }

    /**
     * A type argument that cannot be named, rendered as a wildcard at the argument's own
     * position: {@code LinkedHashMap<String, Hidden>} becomes
     * {@code LinkedHashMap<String, ?>}, a legal target for the real value. For
     * {@code List<Map<String, Hidden>>} the wildcard cannot sit one level down (generics are
     * invariant), so it becomes {@code List<? extends Map<?, ?>>}. The wildcard is bounded by
     * the nearest nameable supertype when there is a useful one.
     */
    private String getAccessibleTypeArgumentName(Type argument) {
        Class<?> erased = argument instanceof Class<?> || argument instanceof ParameterizedType
                ? safeErasure(argument) : null;
        if (erased == null || erased.isArray()) {
            return "?";
        }
        if (containsUnnameableType(erased)) {
            Class<?> bound = findAccessibleRenderableSupertype(erased);
            if (bound == Object.class || containsUnnameableType(bound)) {
                return "?";
            }
            return "? extends " + getTypeParameterName(bound);
        }
        // A nameable raw type with a hidden type argument somewhere inside.
        return erased == Object.class ? "?" : "? extends " + getTypeParameterName(erased);
    }

    /**
     * For a non-static inner class of a generic outer class, the qualifier that carries the
     * owner's type arguments (eg {@code LazyList<Integer>.}); empty when the owner is not
     * parameterised. Without it the declaration is the raw {@code LazyList.LazyListIterator},
     * whose members are all erased.
     */
    private String getParameterizedOwnerQualifier(ParameterizedType type) {
        if (!(type.getOwnerType() instanceof ParameterizedType)
                || !(type.getRawType() instanceof Class<?>)) {
            return "";
        }
        Class<?> rawClass = (Class<?>) type.getRawType();
        if (Modifier.isStatic(rawClass.getModifiers())) {
            return "";
        }
        String owner = getTypeName((ParameterizedType) type.getOwnerType());
        return owner.indexOf('<') < 0 ? "" : owner + ".";
    }

    /**
     * Returns true when the {@code index}-th type argument is a concrete type that is not within
     * a recursive bound of its type parameter, eg {@code TypeResolverBuilder<DefaultTypeResolverBuilder>}
     * where {@code T extends TypeResolverBuilder<T>} and {@code DefaultTypeResolverBuilder}
     * implements {@code TypeResolverBuilder<StdTypeResolverBuilder>}. javac rejects such a type
     * ("type argument ... is not within bounds of type-variable T"); the generator can produce
     * one because it instantiates the parameter without substituting it into its own bound.
     */
    private boolean violatesRecursiveTypeParameterBound(ParameterizedType type, int index) {
        if (!(type.getRawType() instanceof Class<?>)) {
            return false;
        }
        TypeVariable<?>[] typeParams = ((Class<?>) type.getRawType()).getTypeParameters();
        Type[] arguments = type.getActualTypeArguments();
        if (index >= typeParams.length || arguments.length != typeParams.length) {
            return false;
        }
        Type argument = arguments[index];
        if (!(argument instanceof Class<?> || argument instanceof ParameterizedType)) {
            return false;
        }
        Map<TypeVariable<?>, Type> assignments = new HashMap<>();
        for (int i = 0; i < typeParams.length; i++) {
            assignments.put(typeParams[i], arguments[i]);
        }
        for (Type bound : typeParams[index].getBounds()) {
            if (!(bound instanceof ParameterizedType)) {
                continue;
            }
            ParameterizedType parameterizedBound = (ParameterizedType) bound;
            Type[] boundArguments = parameterizedBound.getActualTypeArguments().clone();
            boolean substitutable = true;
            for (int i = 0; i < boundArguments.length; i++) {
                if (boundArguments[i] instanceof TypeVariable<?>
                        && assignments.containsKey(boundArguments[i])) {
                    boundArguments[i] = assignments.get(boundArguments[i]);
                } else if (boundArguments[i] instanceof TypeVariable<?>
                        || boundArguments[i] instanceof WildcardType) {
                    substitutable = false;
                }
            }
            if (!substitutable) {
                continue;
            }
            Type substituted = new ParameterizedTypeImpl((Class<?>) parameterizedBound.getRawType(),
                    boundArguments, parameterizedBound.getOwnerType());
            if (!TypeUtils.isAssignable(argument, substituted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true when {@code argument} is {@code java.lang.Object} but the
     * {@code index}-th type parameter of {@code rawClass} has an upper bound that
     * {@code Object} does not satisfy (a recursive / F-bound). Rendering such a
     * parameterization as {@code Foo<Object>} is a guaranteed compile error
     * ("type argument Object is not within bounds of type-variable T").
     */
    private boolean objectViolatesTypeParameterBound(Class<?> rawClass, int index, Type argument) {
        if (argument != Object.class) {
            return false;
        }
        TypeVariable<?>[] typeParams = rawClass.getTypeParameters();
        if (index >= typeParams.length) {
            return false;
        }
        for (Type bound : typeParams[index].getBounds()) {
            Class<?> boundErasure = safeErasure(bound);
            // A bound other than Object is not satisfiable by Object.
            if (boundErasure != null && boundErasure != Object.class) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the type name of the given type.
     *
     * @param type the type to get the name for
     * @return the type name
     */
    public String getTypeName(Type type) {
        if (type instanceof Class<?>) {
            return getClassName((Class<?>) type);
        } else if (type instanceof ParameterizedType) {
            return getTypeName((ParameterizedType) type);
        } else if (type instanceof WildcardType) {
            String ret = "Object";
            return ret;
        } else if (type instanceof TypeVariable) {
            return "Object";
        } else if (type instanceof CaptureType) {
            CaptureType captureType = (CaptureType) type;
            if (captureType.getLowerBounds().length == 0) {
                return "Object";
            } else {
                return getTypeName(captureType.getLowerBounds()[0]);
            }
        } else if (type instanceof GenericArrayType) {
            return getTypeName(((GenericArrayType) type).getGenericComponentType())
                    + "[]";
        } else {
            throw new RuntimeException("Unsupported type:" + type + ", class"
                    + type.getClass());
        }
    }

    /**
     * Returns the type parameter name of the given type.
     *
     * @param type the type to get the name for
     * @return the type parameter name
     */
    public String getTypeParameterName(Type type) {
        if (type instanceof Class<?>) {
            Class<?> clazz = (Class<?>) type;
            String name = getClassName(clazz);
            if (clazz == Class.class) {
                return name;
            }
            // A raw generic class used as a type argument needs wildcards to avoid
            // compile errors (e.g. TypeVariable<Class> must be TypeVariable<Class<?>>).
            TypeVariable<?>[] typeParams = clazz.getTypeParameters();
            if (typeParams.length > 0) {
                name += "<";
                for (int i = 0; i < typeParams.length; i++) {
                    if (i > 0) {
                        name += ", ";
                    }
                    name += "?";
                }
                name += ">";
            }
            return name;
        } else if (type instanceof ParameterizedType) {
            return getTypeName((ParameterizedType) type);
        } else if (type instanceof WildcardType) {
            String ret = "?";
            boolean first = true;
            for (Type bound : ((WildcardType) type).getLowerBounds()) {
                // If there are lower bounds we need to state them, even if Object
                if (bound == null) { // || GenericTypeReflector.erase(bound).equals(Object.class))
                    continue;
                }
                String boundName = getTypeParameterName(bound);
                // Avoid emitting invalid wildcard syntax like "? super ?".
                // This can happen when the lower bound is itself an unresolved
                // type variable/capture from reflective generic signatures.
                if ("?".equals(boundName)) {
                    continue;
                }

                if (!first) {
                    ret += ", ";
                }
                ret += " super " + boundName;
                first = false;
            }
            for (Type bound : ((WildcardType) type).getUpperBounds()) {
                if (bound == null
                        || (!(bound instanceof CaptureType)
                        && safeErasure(bound).equals(Object.class))) {
                    continue;
                }
                // A nested wildcard ("? extends ?") comes from substituting a
                // wildcard for a type variable inside a bound; WildcardTypeImpl
                // flattens those, but other WildcardType implementations may not.
                String boundName = getTypeParameterName(bound);
                if (boundName.startsWith("?")) {
                    boundName = boundName.startsWith("? extends ")
                            ? boundName.substring("? extends ".length()) : null;
                }
                if (boundName == null) {
                    continue;
                }

                if (!first) {
                    ret += ", ";
                }
                ret += " extends " + boundName;
                first = false;
            }
            return ret;
        } else if (type instanceof TypeVariable) {
            return "?";
        } else if (type instanceof CaptureType) {
            CaptureType captureType = (CaptureType) type;
            if (captureType.getLowerBounds().length == 0) {
                return "?";
            } else {
                return getTypeName(captureType.getLowerBounds()[0]);
            }
        } else if (type instanceof GenericArrayType) {
            return getTypeName(((GenericArrayType) type).getGenericComponentType())
                    + "[]";
        } else {
            throw new RuntimeException("Unsupported type:" + type + ", class"
                    + type.getClass());
        }
    }

    /**
     * Returns the type name of the given variable reference.
     *
     * @param var the variable reference
     * @return the type name
     */
    public String getTypeName(VariableReference var) {

        GenericClass<?> clazz = var.getGenericClass();
        return getTypeName(clazz.getType());
    }

    /**
     * Returns the class name of the given class.
     *
     * @param clazz a {@link java.lang.Class} object.
     * @return a {@link java.lang.String} object.
     */
    public String getClassName(Class<?> clazz) {
        if (classNames.containsKey(clazz)) {
            return classNames.get(clazz);
        }

        if (clazz.isArray()) {
            return getClassName(clazz.getComponentType()) + "[]";
        }

        String canonicalName = clazz.getCanonicalName();
        if (canonicalName != null
                && !canonicalName.isEmpty()
                && !isTypeAccessibleFromGeneratedTest(clazz, getGeneratedTestPackageName())) {
            classNames.put(clazz, canonicalName);
            return canonicalName;
        }

        GenericClass<?> c = GenericClassFactory.get(clazz);
        String name = c.getSimpleName();
        boolean useCanonicalName = false;
        if (hasSimpleNameConflict(clazz, name) || shadowsJavaLangType(clazz)) {
            name = clazz.getCanonicalName();
            useCanonicalName = true;
        } else {
            /*
             * If e.g. there is a foo.bar.IllegalStateException with
             * foo.bar being the SUT package, then we need to use the
             * full package name for java.lang.IllegalStateException
             */
            String fullName = Properties.CLASS_PREFIX + "." + name;
            if (!fullName.equals(clazz.getCanonicalName())) {
                try {
                    if (ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                            .hasClass(fullName)) {
                        name = clazz.getCanonicalName();
                        useCanonicalName = true;
                    }
                } catch (IllegalArgumentException e) {
                    // If the classpath is not correct, then we just don't check
                    // because that cannot happen in regular EvoSuite use, only
                    // from test cases
                }
            }
        }
        // Ensure outer classes are imported as well
        Class<?> outerClass = clazz.getEnclosingClass();
        if (outerClass != null && !useCanonicalName) {
            String enclosingName = getClassName(outerClass);
            String simpleOuterName = outerClass.getSimpleName() + ".";
            if (simpleOuterName.equals(enclosingName)) {
                name = enclosingName + name.substring(simpleOuterName.length());
            } else {
                name = enclosingName + name.substring(name.lastIndexOf(simpleOuterName) + simpleOuterName.length() - 1);
            }
        }

        Class<?> declaringClass = clazz.getDeclaringClass();
        if (declaringClass != null) {
            getClassName(declaringClass);
        }

        // We can't use "Test" because of JUnit
        if (name.equals("Test")) {
            name = clazz.getCanonicalName();
            useCanonicalName = true;
        }
        classNames.put(clazz, name);

        return name;
    }

    /**
     * Whether importing {@code clazz} would shadow a java.lang type of the same simple name
     * (org.apache.xpath.operations.String). The test suite refers to such types by their simple
     * name without registering them here (the {@code String[] EVO_INIT_ORDER} of the scaffolding,
     * {@code Object} casts, {@code Class} literals), so the import breaks them all.
     */
    private static boolean shadowsJavaLangType(Class<?> clazz) {
        if (clazz.isPrimitive() || clazz.getEnclosingClass() != null
                || "java.lang".equals(clazz.getPackage() == null ? null : clazz.getPackage().getName())) {
            return false;
        }
        return JAVA_LANG_SIMPLE_NAMES.computeIfAbsent(clazz.getSimpleName(), simpleName -> {
            try {
                Class.forName("java.lang." + simpleName, false, null);
                return true;
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        });
    }

    private boolean hasSimpleNameConflict(Class<?> clazz, String simpleName) {
        String canonicalName = clazz.getCanonicalName();
        for (Map.Entry<Class<?>, String> entry : classNames.entrySet()) {
            if (!simpleName.equals(entry.getKey().getSimpleName())) {
                continue;
            }
            Class<?> existingClass = entry.getKey();
            String existingCanonical = existingClass.getCanonicalName();
            if (existingCanonical != null && existingCanonical.indexOf('.') >= 0
                    && existingCanonical.equals(entry.getValue())) {
                // Rendered fully qualified, so never imported: its simple name is still free.
                continue;
            }
            if (!Objects.equals(canonicalName, existingCanonical)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the variable name of a variable.
     *
     * @param var a {@link org.evosuite.testcase.variable.VariableReference} object.
     * @return a {@link java.lang.String} object.
     */
    public String getVariableName(VariableReference var) {
        if (var instanceof NullReference
                || var.getVariableClass().equals(Void.class)
                || var.getVariableClass().equals(Void.TYPE)) {
            return "null";
        }
        if (var instanceof ConstantValue) {
            ConstantValue cval = (ConstantValue) var;
            if (cval.getValue() != null && cval.getVariableClass().equals(Class.class)) {
                Class<?> classLiteral = (Class<?>) cval.getValue();
                return getClassLiteralExpression(classLiteral);
            }
            return var.getName();
        } else if (var instanceof FieldReference) {
            String access = getFieldReferenceAccess((FieldReference) var);
            if (isFieldReferenceErasedByRawReceiver(var, var.getVariableClass())) {
                return "((" + getClassName(var.getVariableClass()) + ") " + access + ")";
            }
            return access;
        } else if (var instanceof ArrayIndex) {
            VariableReference array = ((ArrayIndex) var).getArray();
            List<Integer> indices = ((ArrayIndex) var).getArrayIndices();
            String result = getVariableName(array);
            for (Integer index : indices) {
                result += "[" + index + "]";
            }
            return result;
        } else {
            VariableReference normalized = normalizeVariableReference(var);
            String declaredName = declarationVariableNames.get(normalized);
            if (declaredName != null && !declaredName.isEmpty()) {
                return declaredName;
            }
            String postProcessedName = getPostProcessedVariableName(normalized);
            if (postProcessedName != null) {
                return postProcessedName;
            }
            if (VariableNameStrategyFactory.gatherInformation()) {
                information.put("MethodNames", methodNames);
                information.put("ArgumentNames", argumentNames);
                variableNameStrategy.addVariableInformation(information);
            }
            return variableNameStrategy.getNameForVariable(normalized);
        }
    }

    /**
     * The expression naming a field reference, without the cast {@link #getVariableName} adds
     * to a read through a raw receiver: an assignment target must stay assignable.
     */
    private String getFieldReferenceAccess(FieldReference reference) {
        VariableReference source = reference.getSource();
        GenericField field = reference.getField();
        if (!canUseDirectFieldAccess(field.getField(), source)) {
            return getReflectiveFieldAccess(field.getField(), source);
        }
        if (source != null) {
            String ret = "";
            // If the method is not public and this is a subclass in a different package we need to cast
            if (!field.isPublic() && !field.getDeclaringClass().equals(source.getVariableClass())
                    && source.isAssignableTo(field.getDeclaringClass())) {
                String packageName1 = ClassUtils.getPackageName(field.getDeclaringClass());
                String packageName2 = ClassUtils.getPackageName(source.getVariableClass());
                if (!packageName1.equals(packageName2)) {
                    ret += "((" + getClassName(field.getDeclaringClass())
                            + ")" + getVariableName(source) + ")";
                } else {
                    ret += getVariableName(source);
                }
            } else if (!source.isAssignableTo(field.getField().getDeclaringClass())) {
                try {
                    // If the concrete source class has that field then it's ok
                    source.getVariableClass().getDeclaredField(field.getName());
                    ret = getVariableName(source);
                } catch (NoSuchFieldException e) {
                    // If not we need to cast to the subtype
                    ret = "((" + getTypeName(field.getField().getDeclaringClass()) + ") "
                            + getVariableName(source) + ")";
                }
            } else {
                ret += getVariableName(source);
            }

            return ret + "." + field.getName();
        } else {
            return getClassName(field.getField().getDeclaringClass()) + "."
                    + field.getName();
        }
    }

    private String getPostProcessedVariableName(VariableReference variable) {
        if (test == null || variable == null) {
            return null;
        }
        TestPresentationMetadata metadata = TestPresentationMetadata.get(test);
        if (metadata == null) {
            return null;
        }
        int position = safePosition(variable);
        if (position < 0) {
            return null;
        }
        return metadata.getVariableName(position);
    }

    private String getDeclarationVariableName(VariableReference var) {
        VariableReference normalized = normalizeVariableReference(var);
        String existingName = declarationVariableNames.get(normalized);
        if (existingName != null) {
            return existingName;
        }
        String name = getVariableName(normalized);
        if ("null".equals(name)) {
            name = "nullRef" + (nullDeclarationCounter++);
        }
        name = ensureUniqueLocalVariableName(name);
        declarationVariableNames.put(normalized, name);
        reservedLocalVariableNames.add(name);
        return name;
    }

    private String ensureUniqueLocalVariableName(String baseName) {
        String seed = (baseName == null || baseName.trim().isEmpty()) ? "var" : baseName.trim();
        if (!isLocalNameReserved(seed)) {
            return seed;
        }
        int suffix = 1;
        String candidate = seed + "_" + suffix;
        while (isLocalNameReserved(candidate)) {
            suffix++;
            candidate = seed + "_" + suffix;
        }
        return candidate;
    }

    private boolean isLocalNameReserved(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return true;
        }
        if (reservedLocalVariableNames.contains(candidate)) {
            return true;
        }
        if (declarationVariableNames.containsValue(candidate)) {
            return true;
        }
        return containsIdentifierOutsideLiteralsAndComments(testCode.toString(), candidate);
    }

    private boolean canUseDirectFieldAccess(Field field, VariableReference source) {
        if (field == null) {
            return false;
        }
        String testPackageName = getGeneratedTestPackageName();
        if (!isTypeAccessibleFromGeneratedTest(field.getDeclaringClass(), testPackageName)) {
            return false;
        }
        int modifiers = TestUsageChecker.getOriginalFieldModifiers(field);
        if (Modifier.isPublic(modifiers)) {
            return true;
        }
        if (Modifier.isPrivate(modifiers)) {
            return false;
        }
        // Without a concrete output package we cannot safely assume package-private
        // or protected access is legal; be conservative to avoid uncompilable tests.
        if (isEmptyPackageName(testPackageName)) {
            return false;
        }
        if (!isSamePackage(field.getDeclaringClass(), testPackageName)) {
            return false;
        }
        // A package-private field is not inherited by a subclass in another
        // package. FieldStatement emits receiver.field without FieldReference's
        // declaring-type cast, so use the shared reflective path for that receiver.
        return Modifier.isProtected(modifiers) || source == null
                || isSamePackage(getRenderedDeclarationClass(source), testPackageName);
    }

    private String getReflectiveFieldAccess(Field field, VariableReference source) {
        String receiver = source == null ? "null" : getVariableName(source);
        // Outer parens wrap the cast so callers can use the expression as a method
        // receiver: `((T) PrivateAccess.getVariable(...)).foo()` rather than the
        // mis-parsed `(T) PrivateAccess.getVariable(...).foo()`.
        return "((" + getClassName(field.getType()) + ") "
                + getClassName(PrivateAccess.class)
                + ".getVariable("
                + getClassLiteralExpression(field.getDeclaringClass())
                + ", "
                + receiver
                + ", \""
                + field.getName()
                + "\"))";
    }

    private String getReflectiveFieldWrite(Field field, VariableReference source, String valueExpression) {
        String receiver = source == null ? "null" : getVariableName(source);
        return getClassName(PrivateAccess.class)
                + ".setVariable("
                + getClassLiteralExpression(field.getDeclaringClass())
                + ", "
                + receiver
                + ", \""
                + field.getName()
                + "\", "
                + valueExpression
                + ")";
    }

    private String getFieldAccess(Field field, VariableReference source, String targetTypeName, boolean needsCast) {
        if (!canUseDirectFieldAccess(field, source)) {
            return getReflectiveFieldAccess(field, source);
        }

        String access;
        if (Modifier.isStatic(field.getModifiers())) {
            access = getClassName(field.getDeclaringClass()) + "." + field.getName();
        } else {
            access = getVariableName(source) + "." + field.getName();
        }

        if (!needsCast) {
            return access;
        }
        return "(" + targetTypeName + ")" + access;
    }

    private String getSnippetBindingName(VariableReference var) {
        String name = getVariableName(var);
        if ("null".equals(name)) {
            return getDeclarationVariableName(var);
        }
        return name;
    }

    private VariableReference normalizeVariableReference(VariableReference var) {
        if (var == null || test == null) {
            return var;
        }
        int position = safePosition(var);
        if (position >= 0 && position < test.size()) {
            // For code emission we canonicalize by defining statement position.
            // This avoids orphan variable names when assertions carry a cloned/stale
            // VariableReference instance that points to the same logical statement.
            return test.getStatement(position).getReturnValue();
        }
        VariableReference recovered = recoverByCompatibleType(var);
        return recovered != null ? recovered : var;
    }

    private VariableReference recoverByCompatibleType(VariableReference var) {
        List<VariableReference> matches = new ArrayList<>();
        for (int i = 0; i < test.size(); i++) {
            VariableReference candidate = test.getStatement(i).getReturnValue();
            if (isRecoverableMatch(var, candidate)) {
                matches.add(candidate);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        return var;
    }

    private boolean sameVariableDefinition(VariableReference left, VariableReference right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        int leftPosition = safePosition(left);
        int rightPosition = safePosition(right);
        if (leftPosition < 0 || rightPosition < 0 || leftPosition != rightPosition) {
            return false;
        }
        return isTypeCompatible(left, right);
    }

    private boolean isTypeCompatible(VariableReference left, VariableReference right) {
        Class<?> leftClass = left.getVariableClass();
        Class<?> rightClass = right.getVariableClass();
        return leftClass.equals(rightClass)
                || leftClass.isAssignableFrom(rightClass)
                || rightClass.isAssignableFrom(leftClass);
    }

    private boolean isRecoverableMatch(VariableReference expected, VariableReference candidate) {
        Class<?> expectedClass = expected.getVariableClass();
        Class<?> candidateClass = candidate.getVariableClass();
        return expectedClass.equals(candidateClass) || expectedClass.isAssignableFrom(candidateClass);
    }

    private int safePosition(VariableReference variableReference) {
        if (variableReference == null || test == null) {
            return -1;
        }
        // Avoid VariableReference#getStPosition() here: it throws on stale/orphan references.
        int position = test.positionOfReturnValue(variableReference);
        if (position >= 0) {
            return position;
        }
        for (int i = 0; i < test.size(); i++) {
            VariableReference candidate = test.getStatement(i).getReturnValue();
            if (candidate != null && candidate.equals(variableReference)) {
                return i;
            }
        }
        return -1;
    }


    /**
     * Retrieve the names of all known variables.
     *
     * @return .
     */
    public Collection<String> getVariableNames() {
        return variableNameStrategy.getVariableNames();
    }

    /**
     * Retrieve the names of all known classes.
     *
     * @return .
     */
    public Collection<String> getClassNames() {
        return classNames.values();
    }

    /*
     * (non-Javadoc)
     *
     * @see org.evosuite.testcase.TestVisitor#visitTestCase(org.evosuite.testcase.TestCase)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitTestCase(TestCase test) {
        this.test = test;
        this.testCode = new StringBuilder();
        this.nullDeclarationCounter = 0;
        this.declarationVariableNames.clear();
        this.reservedLocalVariableNames.clear();
        this.deferredAssertionsByPosition.clear();
        if (!customVariableNameStrategy) {
            this.variableNameStrategy = VariableNameStrategyFactory.get();
        }
    }

    /**
     * Visits a primitive assertion.
     *
     * @param assertion a {@link org.evosuite.assertion.PrimitiveAssertion} object.
     */
    protected void visitPrimitiveAssertion(PrimitiveAssertion assertion) {
        VariableReference source = assertion.getSource();
        Object value = assertion.getValue();

        String stmt = "";

        if (value == null) {
            stmt += "assertNull(" + getVariableName(source) + ");";
        } else if (source.getVariableClass().equals(float.class)) {
            stmt += "assertEquals(" + NumberFormatter.getNumberString(value, this) + ", "
                    + getVariableName(source) + ", "
                    + NumberFormatter.getNumberString(Properties.FLOAT_PRECISION, this) + ");";
        } else if (source.getVariableClass().equals(double.class)) {
            stmt += "assertEquals(" + NumberFormatter.getNumberString(value, this) + ", "
                    + getVariableName(source) + ", "
                    + NumberFormatter.getNumberString(Properties.DOUBLE_PRECISION, this) + ");";
        } else if (NumberFormatter.isEnum(value)) {
            stmt += EnumAssertionCodeRenderer.getEnumAssertionCode(value, getVariableName(source), this);
        } else if (source.getVariableClass().equals(boolean.class) || source.getVariableClass().equals(Boolean.class)) {
            Boolean flag;
            if (value instanceof Boolean) {
                flag = (Boolean) value;
            } else if (value instanceof Number) {
                int n = ((Number) value).intValue();
                if (n == 0) {
                    flag = Boolean.FALSE;
                } else if (n == 1) {
                    flag = Boolean.TRUE;
                } else {
                    stmt += "assertEquals(" + NumberFormatter.getNumberString(value, this) + ", "
                            + getVariableName(source) + ");";
                    testCode.append(stmt);
                    return;
                }
            } else {
                stmt += "assertEquals(" + NumberFormatter.getNumberString(value, this) + ", "
                        + getVariableName(source) + ");";
                testCode.append(stmt);
                return;
            }
            if (flag) {
                stmt += "assertTrue(";
            } else {
                stmt += "assertFalse(";
            }
            stmt += "" + getVariableName(source) + ");";
        } else if (source.isWrapperType()) {
            if (source.getVariableClass().equals(Float.class)) {
                stmt += "assertEquals(" + NumberFormatter.getNumberString(value, this)
                        + ", (float)" + getVariableName(source) + ", "
                        + NumberFormatter.getNumberString(Properties.FLOAT_PRECISION, this) + ");";
            } else if (source.getVariableClass().equals(Double.class)) {
                stmt += "assertEquals(" + NumberFormatter.getNumberString(value, this)
                        + ", (double)" + getVariableName(source) + ", "
                        + NumberFormatter.getNumberString(Properties.DOUBLE_PRECISION, this) + ");";
            } else if (NumberFormatter.isEnum(value)) {
                stmt += EnumAssertionCodeRenderer.getEnumAssertionCode(value, getVariableName(source), this);
            } else {
                stmt += "assertEquals(" + NumberFormatter.getNumberString(value, this)
                        + ", (" + NumberFormatter.getBoxedClassName(value) + ")"
                        + getVariableName(source) + ");";
            }
        } else if (PrimitiveAssertion.needsUnboxingCast(source, value)) {
            // The variable class and the value disagree (eg a variable of class long whose
            // declaration is the constructor owner Integer): without a cast
            // assertEquals(int, Integer) is ambiguous between (long, long) and (Object, Object).
            stmt += "assertEquals(" + NumberFormatter.getNumberString(value, this)
                    + ", (" + NumberFormatter.getBoxedClassName(value) + ")"
                    + getVariableName(source) + ");";
        } else {
            stmt += "assertEquals(" + NumberFormatter.getNumberString(value, this) + ", "
                    + getVariableName(source) + ");";
        }

        testCode.append(stmt);
    }


    protected void visitArrayEqualsAssertion(ArrayEqualsAssertion assertion) {
        VariableReference source = assertion.getSource();
        Object[] value = (Object[]) assertion.getValue();

        String stmt = "";

        if (source.getComponentClass().equals(Boolean.class) || source.getComponentClass().equals(boolean.class)) {
            stmt += "assertTrue(Arrays.equals(";
            // Make sure that the Arrays class is imported
            getClassName(Arrays.class);
        } else {
            stmt += "assertArrayEquals(";
        }
        stmt += "new " + getTypeName(source.getComponentType()) + "[] {";
        boolean first = true;
        for (Object o : value) {
            if (!first) {
                stmt += ", ";
            } else {
                first = false;
            }

            stmt += NumberFormatter.getNumberString(o, this);

        }
        stmt += "}" + ", " + getVariableName(source);
        if (source.getComponentClass().equals(Float.class) || source.getComponentClass().equals(float.class)) {
            stmt += ", " + NumberFormatter.getNumberString(Properties.FLOAT_PRECISION, this) + ");";
        } else {
            if (source.getComponentClass().equals(Double.class) || source.getComponentClass().equals(double.class)) {
                stmt += ", " + NumberFormatter.getNumberString(Properties.DOUBLE_PRECISION, this) + ");";
            } else {
                if (source.getComponentClass().equals(Boolean.class)
                        || source.getComponentClass().equals(boolean.class)) {
                    stmt += "));";
                } else {
                    stmt += ");";
                }
            }
        }

        testCode.append(stmt);
    }

    protected void visitArrayLengthAssertion(ArrayLengthAssertion assertion) {
        VariableReference source = assertion.getSource();
        int length = assertion.length;

        String stmt = "assertEquals(";
        String sourceExpr = getVariableName(source);
        if (source != null && source.getVariableClass() != null && source.getVariableClass().isArray()) {
            stmt += length + ", " + sourceExpr + ".length);";
        } else {
            stmt += length + ", java.lang.reflect.Array.getLength(" + sourceExpr + "));";
        }

        testCode.append(stmt);
    }

    /**
     * The raw class of the type a declaration of {@code var} is rendered with: mirrors
     * {@link #getTypeName(Type)}, which prints {@code Object} for type variables, wildcards and
     * captures, so {@link VariableReference#getVariableClass()} (which may report a bound or a
     * narrowed runtime class) is not always what the source says.
     */
    private static Class<?> getRenderedDeclarationClass(VariableReference var) {
        Type type = var.getType();
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType && ((ParameterizedType) type).getRawType() instanceof Class<?>) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        }
        if (type instanceof GenericArrayType) {
            return Object[].class;
        }
        return Object.class;
    }

    protected void visitContainsAssertion(ContainsAssertion assertion) {
        VariableReference containerObject = assertion.getSource();
        VariableReference containedObject = assertion.getContainedVariable();

        // The observer saw the variable after Scope#setObject narrowed it to its runtime class,
        // but the declaration is rendered from the variable's type in *this* test (a method
        // returning Object, a type variable, ...), and contains() only compiles if that rendered
        // type is a Collection. Render nothing rather than "object0.contains(0)" on an Object.
        if (!Collection.class.isAssignableFrom(getRenderedDeclarationClass(containerObject))) {
            return;
        }

        Boolean contains = (Boolean) assertion.getValue();

        String stmt = "";
        if (contains) {
            stmt += "assertTrue(";
        } else {
            stmt += "assertFalse(";
        }
        stmt += getVariableName(containerObject) + ".contains(" + getVariableName(containedObject) + "));";

        testCode.append(stmt);
    }

    /**
     * Visits a primitive field assertion.
     *
     * @param assertion a {@link org.evosuite.assertion.PrimitiveFieldAssertion}
     *                  object.
     */
    protected void visitPrimitiveFieldAssertion(PrimitiveFieldAssertion assertion) {
        VariableReference source = assertion.getSource();
        Object value = assertion.getValue();
        Field field = assertion.getField();

        String target = getFieldAccess(field, Modifier.isStatic(field.getModifiers()) ? null : source,
                getTypeName(field.getGenericType()), false);

        if (value == null) {
            testCode.append("assertNull(" + target
                    + ");");
        } else if (value.getClass().equals(Long.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", "
                    + target + ");");
        } else if (value.getClass().equals(Float.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", "
                    + target + ", " + NumberFormatter.getNumberString(Properties.FLOAT_PRECISION, this) + ");");
        } else if (value.getClass().equals(Double.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", "
                    + target + ", " + NumberFormatter.getNumberString(Properties.DOUBLE_PRECISION, this) + ");");
        } else if (value.getClass().equals(Character.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", "
                    + target + ");");
        } else if (value.getClass().equals(String.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", "
                    + target + ");");
        } else if (value.getClass().equals(Boolean.class)) {
            Boolean flag = (Boolean) value;
            if (flag) {
                testCode.append("assertTrue(");
            } else {
                testCode.append("assertFalse(");
            }
            testCode.append("" + target + ");");
        } else if (value.getClass().isEnum()) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", "
                    + target + ");");
            // Make sure the enum is imported in the JUnit test
            getClassName(value.getClass());

        } else {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", "
                    + target + ");");
        }
    }

    /**
     * Visits an inspector assertion.
     *
     * @param assertion a {@link org.evosuite.assertion.InspectorAssertion} object.
     */
    protected void visitInspectorAssertion(InspectorAssertion assertion) {
        VariableReference source = assertion.getSource();
        Object value = assertion.getValue();
        Inspector inspector = assertion.getInspector();
        Class<?> generatedType = inspector.getReturnType();
        String inspectorTarget = getInspectorTarget(source, inspector);

        if (value == null) {
            testCode.append("assertNull(" + inspectorTarget + "."
                    + inspector.getMethodCall() + "());");
        } else if (value.getClass().equals(Long.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", ");
            if (ClassUtils.isPrimitiveWrapper(generatedType)) {
                testCode.append("(long)");
            }
            testCode.append(inspectorTarget + "." + inspector.getMethodCall() + "());");
        } else if (value.getClass().equals(Short.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", ");
            if (ClassUtils.isPrimitiveWrapper(generatedType)) {
                testCode.append("(short)");
            }
            testCode.append(inspectorTarget + "." + inspector.getMethodCall() + "());");
        } else if (value.getClass().equals(Integer.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", ");
            if (ClassUtils.isPrimitiveWrapper(generatedType)) {
                testCode.append("(int)");
            }
            testCode.append(inspectorTarget + "." + inspector.getMethodCall() + "());");
        } else if (value.getClass().equals(Byte.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", ");
            if (ClassUtils.isPrimitiveWrapper(generatedType)) {
                testCode.append("(byte)");
            }
            testCode.append(inspectorTarget + "." + inspector.getMethodCall() + "());");
        } else if (value.getClass().equals(Float.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", ");
            testCode.append(inspectorTarget + "." + inspector.getMethodCall()
                    + "(), " + NumberFormatter.getNumberString(Properties.FLOAT_PRECISION, this) + ");");
        } else if (value.getClass().equals(Double.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", ");
            testCode.append(inspectorTarget + "." + inspector.getMethodCall()
                    + "(), " + NumberFormatter.getNumberString(Properties.DOUBLE_PRECISION, this) + ");");
        } else if (value.getClass().equals(Character.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", ");
            if (ClassUtils.isPrimitiveWrapper(generatedType)) {
                testCode.append("(char)");
            }
            testCode.append(inspectorTarget + "." + inspector.getMethodCall() + "());");
        } else if (value.getClass().equals(String.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", ");
            testCode.append(inspectorTarget + "." + inspector.getMethodCall() + "());");
        } else if (NumberFormatter.isEnum(value)) {
            testCode.append(EnumAssertionCodeRenderer.getEnumAssertionCode(value,
                    inspectorTarget + "." + inspector.getMethodCall() + "()", this));

        } else if (value.getClass().equals(boolean.class) || value.getClass().equals(Boolean.class)) {
            if ((Boolean) value) {
                testCode.append("assertTrue(" + inspectorTarget + "."
                        + inspector.getMethodCall() + "());");
            } else {
                testCode.append("assertFalse(" + inspectorTarget + "."
                        + inspector.getMethodCall() + "());");
            }

        } else {
            testCode.append("assertEquals(" + value + ", " + inspectorTarget + "."
                    + inspector.getMethodCall() + "());");
        }
    }

    private String getInspectorTarget(VariableReference source, Inspector inspector) {
        String sourceName = getVariableName(source);
        if (source == null || inspector == null || inspector.getMethod() == null) {
            return sourceName;
        }

        Method targetMethod = inspector.getMethod();
        if (inspector instanceof ChainedInspector) {
            Method outerMethod = ((ChainedInspector) inspector).getOuterMethod();
            if (outerMethod != null) {
                targetMethod = outerMethod;
            }
        }

        Class<?> declaringClass = targetMethod.getDeclaringClass();
        if (declaringClass == null || declaringClass.isAssignableFrom(source.getVariableClass())) {
            return sourceName;
        }

        return "((" + getClassName(declaringClass) + ") " + sourceName + ")";
    }

    /**
     * Visits a null assertion.
     *
     * @param assertion a {@link org.evosuite.assertion.NullAssertion} object.
     */
    protected void visitNullAssertion(NullAssertion assertion) {
        VariableReference source = assertion.getSource();
        Boolean value = (Boolean) assertion.getValue();
        if (value) {
            testCode.append("assertNull(" + getVariableName(source) + ");");
        } else {
            testCode.append("assertNotNull(" + getVariableName(source) + ");");
        }
    }

    /**
     * Visits a compare assertion.
     *
     * @param assertion a {@link org.evosuite.assertion.CompareAssertion} object.
     */
    protected void visitCompareAssertion(CompareAssertion assertion) {
        VariableReference source = assertion.getSource();
        VariableReference dest = assertion.getDest();
        Object value = assertion.getValue();

        if (source.getType().equals(Integer.class)) {
            if ((Integer) value == 0) {
                testCode.append("assertTrue(" + getVariableName(source) + " == "
                        + getVariableName(dest) + ");");
            } else if ((Integer) value < 0) {
                testCode.append("assertTrue(" + getVariableName(source) + " < "
                        + getVariableName(dest) + ");");
            } else {
                testCode.append("assertTrue(" + getVariableName(source) + " > "
                        + getVariableName(dest) + ");");
            }
        } else {
            testCode.append("assertEquals(" + getVariableName(source) + ".compareTo("
                    + getVariableName(dest) + "), " + value + ");");
        }
    }

    /**
     * Visits an equals assertion.
     *
     * @param assertion a {@link org.evosuite.assertion.EqualsAssertion} object.
     */
    protected void visitEqualsAssertion(EqualsAssertion assertion) {
        VariableReference source = assertion.getSource();
        VariableReference dest = assertion.getDest();
        Object value = assertion.getValue();

        if (source.isPrimitive() || source.isWrapperType()) {
            if (source.getVariableClass().equals(float.class)) {
                if ((Boolean) value) {
                    testCode.append("assertEquals(" + getVariableName(source) + ", "
                            + getVariableName(dest) + ", "
                            + NumberFormatter.getNumberString(Properties.FLOAT_PRECISION, this) + ");");
                } else {
                    testCode.append("assertNotEquals(" + getVariableName(source) + ", "
                            + getVariableName(dest) + ", "
                            + NumberFormatter.getNumberString(Properties.FLOAT_PRECISION, this) + ");");
                }
            } else if (source.getVariableClass().equals(Float.class)) {
                if ((Boolean) value) {
                    testCode.append("assertEquals((float)" + getVariableName(source) + ", (float)"
                            + getVariableName(dest) + ", "
                            + NumberFormatter.getNumberString(Properties.FLOAT_PRECISION, this) + ");");
                } else {
                    testCode.append("assertNotEquals((float)" + getVariableName(source) + ", (float)"
                            + getVariableName(dest) + ", "
                            + NumberFormatter.getNumberString(Properties.FLOAT_PRECISION, this) + ");");
                }
            } else if (source.getVariableClass().equals(double.class)) {
                if ((Boolean) value) {
                    testCode.append("assertEquals(" + getVariableName(source) + ", "
                            + getVariableName(dest) + ", "
                            + NumberFormatter.getNumberString(Properties.DOUBLE_PRECISION, this) + ");");
                } else {
                    testCode.append("assertNotEquals(" + getVariableName(source) + ", "
                            + getVariableName(dest) + ", "
                            + NumberFormatter.getNumberString(Properties.DOUBLE_PRECISION, this) + ");");
                }
            } else if (source.getVariableClass().equals(Double.class)) {
                if ((Boolean) value) {
                    testCode.append("assertEquals((double)" + getVariableName(source)
                            + ", (double)" + getVariableName(dest) + ", "
                            + NumberFormatter.getNumberString(Properties.DOUBLE_PRECISION, this) + ");");
                } else {
                    testCode.append("assertNotEquals((double)" + getVariableName(source)
                            + ", (double)" + getVariableName(dest) + ", "
                            + NumberFormatter.getNumberString(Properties.DOUBLE_PRECISION, this) + ");");
                }
            } else if (source.isWrapperType()) {
                if ((Boolean) value) {
                    testCode.append("assertTrue(" + getVariableName(source) + ".equals(("
                            + this.getClassName(Object.class) + ")" + getVariableName(dest) + "));");
                } else {
                    testCode.append("assertFalse(" + getVariableName(source) + ".equals(("
                            + this.getClassName(Object.class) + ")" + getVariableName(dest) + "));");
                }
            } else if (dest.isWrapperType()) {
                if ((Boolean) value) {
                    testCode.append("assertTrue(" + getVariableName(dest) + ".equals(("
                            + this.getClassName(Object.class) + ")" + getVariableName(source) + "));");
                } else {
                    testCode.append("assertFalse(" + getVariableName(dest) + ".equals(("
                            + this.getClassName(Object.class) + ")" + getVariableName(source) + "));");
                }
            } else {
                if ((Boolean) value) {
                    testCode.append("assertTrue(" + getVariableName(source) + " == "
                            + getVariableName(dest) + ");");
                } else {
                    testCode.append("assertFalse(" + getVariableName(source) + " == "
                            + getVariableName(dest) + ");");
                }
            }
        } else {
            if ((Boolean) value) {
                testCode.append("assertTrue(" + getVariableName(source) + ".equals(("
                        + this.getClassName(Object.class) + ")" + getVariableName(dest) + "));");
            } else {
                testCode.append("assertFalse(" + getVariableName(source) + ".equals(("
                        + this.getClassName(Object.class) + ")" + getVariableName(dest) + "));");
            }
        }
    }

    /**
     * Visits a same assertion.
     *
     * @param assertion a {@link org.evosuite.assertion.SameAssertion} object.
     */
    protected void visitSameAssertion(SameAssertion assertion) {
        VariableReference source = assertion.getSource();
        VariableReference dest = assertion.getDest();
        Object value = assertion.getValue();

        if ((Boolean) value) {
            testCode.append("assertSame(" + getVariableName(source) + ", "
                    + getVariableName(dest) + ");");
        } else {
            testCode.append("assertNotSame(" + getVariableName(source) + ", "
                    + getVariableName(dest) + ");");
        }
    }

    private String getUnstableTestComment() {
        return " // Unstable assertion";
    }

    private boolean isTestUnstable() {
        return test != null && test.isUnstable();
    }


    /**
     * Visits an assertion and handles unstable tests and comments.
     *
     * @param assertion the assertion to visit.
     */
    protected void visitAssertion(Assertion assertion) {

        if (isTestUnstable()) {
            /*
             * if the current test is unstable, then comment out all of its assertions.
             */
            testCode.append("// " + getUnstableTestComment() + ": ");
        }

        if (assertion instanceof PrimitiveAssertion) {
            visitPrimitiveAssertion((PrimitiveAssertion) assertion);
        } else if (assertion instanceof PrimitiveFieldAssertion) {
            visitPrimitiveFieldAssertion((PrimitiveFieldAssertion) assertion);
        } else if (assertion instanceof InspectorAssertion) {
            visitInspectorAssertion((InspectorAssertion) assertion);
        } else if (assertion instanceof NullAssertion) {
            visitNullAssertion((NullAssertion) assertion);
        } else if (assertion instanceof CompareAssertion) {
            visitCompareAssertion((CompareAssertion) assertion);
        } else if (assertion instanceof EqualsAssertion) {
            visitEqualsAssertion((EqualsAssertion) assertion);
        } else if (assertion instanceof SameAssertion) {
            visitSameAssertion((SameAssertion) assertion);
        } else if (assertion instanceof ArrayEqualsAssertion) {
            visitArrayEqualsAssertion((ArrayEqualsAssertion) assertion);
        } else if (assertion instanceof ArrayLengthAssertion) {
            visitArrayLengthAssertion((ArrayLengthAssertion) assertion);
        } else if (assertion instanceof ContainsAssertion) {
            visitContainsAssertion((ContainsAssertion) assertion);
        } else if (assertion instanceof TemplateCodeAssertion) {
            testCode.append(((TemplateCodeAssertion) assertion).render(this));
        } else {
            throw new RuntimeException("Unknown assertion type: " + assertion);
        }
        if (assertion.hasComment()) {
            testCode.append(assertion.getComment());
        }
    }

    private void addAssertions(Statement statement) {
        if (!emitAssertions) {
            return;
        }
        boolean assertionAdded = emitDeferredAssertionsForPosition(statement.getPosition());
        if (getException(statement) != null) {
            // Assumption: The statement that throws an exception is the last statement of a test.
            VariableReference returnValue = statement.getReturnValue();
            for (Assertion assertion : statement.getAssertions()) {
                canonicalizeAssertionSource(statement, assertion);
                if (assertion != null
                        && !isInCatchTemplateAssertion(assertion)
                        && !assertion.getReferencedVariables().contains(returnValue)) {
                    assertionAdded |= emitOrDeferAssertion(assertion, statement.getPosition());
                }
            }
        } else {
            for (Assertion assertion : statement.getAssertions()) {
                if (assertion != null) {
                    canonicalizeAssertionSource(statement, assertion);
                    assertionAdded |= emitOrDeferAssertion(assertion, statement.getPosition());
                }
            }
        }
        if (assertionAdded) {
            testCode.append(NEWLINE);
        }
    }

    private static boolean isInCatchTemplateAssertion(Assertion assertion) {
        return assertion instanceof TemplateCodeAssertion
                && ((TemplateCodeAssertion) assertion).getPlacement()
                == TemplateCodeAssertion.Placement.IN_CATCH;
    }

    private boolean emitOrDeferAssertion(Assertion assertion, int currentPosition) {
        int latestReferencedPosition = getLatestReferencedPosition(assertion);
        if (latestReferencedPosition > currentPosition) {
            deferredAssertionsByPosition
                    .computeIfAbsent(latestReferencedPosition, ignored -> new ArrayList<>())
                    .add(assertion);
            return false;
        }
        visitAssertion(assertion);
        testCode.append(NEWLINE);
        return true;
    }

    private boolean emitDeferredAssertionsForPosition(int position) {
        List<Assertion> deferred = deferredAssertionsByPosition.remove(position);
        if (deferred == null || deferred.isEmpty()) {
            return false;
        }
        for (Assertion assertion : deferred) {
            visitAssertion(assertion);
            testCode.append(NEWLINE);
        }
        return true;
    }

    private int getLatestReferencedPosition(Assertion assertion) {
        int maxPosition = -1;
        for (VariableReference variableReference : assertion.getReferencedVariables()) {
            if (variableReference == null) {
                continue;
            }
            int position = safePosition(normalizeVariableReference(variableReference));
            if (position > maxPosition) {
                maxPosition = position;
            }
        }
        return maxPosition;
    }

    private void canonicalizeAssertionSource(Statement statement, Assertion assertion) {
        if (assertion == null || assertion.getSource() == null || test == null) {
            return;
        }

        VariableReference source = assertion.getSource();
        VariableReference normalized = normalizeVariableReference(source);
        if (normalized != null && normalized != source) {
            assertion.setSource(normalized);
            source = normalized;
        }

        if (isDefinedInCurrentTest(source)) {
            return;
        }

        VariableReference returnValue = statement.getReturnValue();
        if (returnValue != null && isTypeCompatible(source, returnValue)) {
            assertion.setSource(returnValue);
        }
    }

    private boolean isDefinedInCurrentTest(VariableReference variableReference) {
        int position = safePosition(variableReference);
        if (position < 0 || position >= test.size()) {
            return false;
        }
        VariableReference canonical = test.getStatement(position).getReturnValue();
        return sameVariableDefinition(variableReference, canonical);
    }

    protected String getEnumValue(EnumPrimitiveStatement<?> statement) {
        Object value = statement.getValue();
        Class<?> clazz = statement.getEnumClass();
        String className = getClassName(clazz);

        try {
            if (value.getClass().getField(value.toString()) != null) {
                return className + "." + value;
            }

        } catch (NoSuchFieldException e) {
            // Ignore
        }

        for (Field field : value.getClass().getDeclaredFields()) {
            if (field.isEnumConstant()) {
                try {
                    if (field.get(value).equals(value)) {
                        return className + "." + field.getName();
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        }
        return className + ".valueOf(\"" + value + "\")";

    }



    /*
     * (non-Javadoc)
     *
     * @see org.evosuite.testcase.TestVisitor#visitPrimitiveStatement(org.evosuite.testcase.PrimitiveStatement)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitPrimitiveStatement(PrimitiveStatement<?> statement) {
        VariableReference retval = statement.getReturnValue();
        Object value = statement.getValue();

        if (statement instanceof StringPrimitiveStatement) {
            if (value == null) {
                testCode.append(getClassName(retval) + " "
                        + getDeclarationVariableName(retval) + " = null;" + NEWLINE);

            } else {
                String escapedString = StringUtil.getEscapedString((String) value);
                testCode.append(((Class<?>) retval.getType()).getSimpleName() + " "
                        + getDeclarationVariableName(retval) + " = \"" + escapedString + "\";" + NEWLINE);
            }
            // testCode.append(((Class<?>) retval.getType()).getSimpleName() + " "
            // + getVariableName(retval) + " = \""
            // + StringEscapeUtils.escapeJava((String) value) + "\";\n");
        } else if (statement instanceof EnvironmentDataStatement) {
            testCode.append(((EnvironmentDataStatement<?>) statement).getTestCode(getDeclarationVariableName(retval)));
        } else if (statement instanceof ClassPrimitiveStatement) {
            StringBuilder builder = new StringBuilder();
            Class<?> literalClass = (Class<?>) value;
            builder.append(getClassLiteralDeclarationTypeName(literalClass));
            builder.append(" ");
            builder.append(getDeclarationVariableName(retval));
            builder.append(" = ");
            builder.append(getClassName(literalClass));
            builder.append(".class;");
            builder.append(NEWLINE);
            testCode.append(builder.toString());
        } else {
            testCode.append(getClassName(retval) + " " + getDeclarationVariableName(retval) + " = "
                    + NumberFormatter.getNumberString(value, this) + ";" + NEWLINE);
        }
        addAssertions(statement);
    }

    private String getClassLiteralDeclarationTypeName(Class<?> literalClass) {
        // Primitive/void class literals cannot appear as generic type arguments
        // (e.g., Class<boolean> is illegal), so use wildcard in those cases.
        if (literalClass == null || literalClass.isPrimitive() || literalClass == Void.TYPE) {
            return "Class<?>";
        }
        if (!isTypeAccessibleFromGeneratedTest(literalClass, getGeneratedTestPackageName())) {
            return "Class<?>";
        }
        // For class literals of generic raw classes, using wildcarded type arguments
        // in the declaration (e.g., Class<List<?>> x = List.class) is not assignable.
        // Emit the raw literal target type directly to preserve compilability.
        return "Class<" + getClassName(literalClass) + ">";
    }



    /*
     * (non-Javadoc)
     *
     * @see org.evosuite.testcase.TestVisitor#visitFieldStatement(org.evosuite.testcase.FieldStatement)
     */

    /**
     * Visits a field statement.
     *
     * @param statement a {@link org.evosuite.testcase.statements.FieldStatement} object.
     */
    @Override
    public void visitFieldStatement(FieldStatement statement) {
        Throwable exception = getException(statement);

        StringBuilder builder = new StringBuilder();

        VariableReference retval = statement.getReturnValue();
        GenericField field = statement.getField();
        boolean needsCast = !retval.isAssignableFrom(field.getFieldType())
                || isFieldReadErasedByRawReceiver(field.getField(), statement.getSource(),
                        retval.getVariableClass());
        String castTypeName = getClassName(retval);
        // A Class<?> field read into a Class<Object> variable: the model treats the wildcard
        // as assignable, but javac sees Class<capture> and rejects the assignment. A raw cast
        // is an unchecked but legal conversion.
        Type declaredFieldType = field.getField().getGenericType();
        if (declaredFieldType instanceof ParameterizedType
                && retval.getType() instanceof ParameterizedType
                && java.util.Arrays.stream(((ParameterizedType) declaredFieldType).getActualTypeArguments())
                .anyMatch(a -> a instanceof WildcardType)
                && java.util.Arrays.stream(((ParameterizedType) retval.getType()).getActualTypeArguments())
                .noneMatch(a -> a instanceof WildcardType)) {
            needsCast = true;
            castTypeName = getClassName(
                    (Class<?>) ((ParameterizedType) retval.getType()).getRawType());
        }

        if (exception != null) {
            builder.append(getClassName(retval));
            builder.append(" ");
            builder.append(getVariableName(retval));
            // A primitive field (boolean flag = PropsValues.FLAG) cannot be pre-declared as null.
            builder.append(" = ").append(retval.isPrimitive() ? retval.getDefaultValueString() : "null")
                    .append(";");
            builder.append(NEWLINE);
            builder.append("try {");
            builder.append(NEWLINE);
            builder.append("    ");
        } else {
            builder.append(getClassName(retval));
            builder.append(" ");
        }
        if (!field.isStatic()) {
            VariableReference source = statement.getSource();
            builder.append(getVariableName(retval));
            builder.append(" = ");
            builder.append(getFieldAccess(field.getField(), source, castTypeName, needsCast));
        } else {
            builder.append(getVariableName(retval));
            builder.append(" = ");
            builder.append(getFieldAccess(field.getField(), null, castTypeName, needsCast));
        }
        builder.append(";");
        if (exception != null) {
            Class<?> ex = exception.getClass();
            while (!Modifier.isPublic(ex.getModifiers())) {
                ex = ex.getSuperclass();
            }
            builder.append(NEWLINE);
            builder.append("} catch(");
            builder.append(getClassName(ex));
            builder.append(" e) {}");
        }
        builder.append(NEWLINE);

        testCode.append(builder.toString());
        addAssertions(statement);
    }

    private String getPrimitiveNullCast(Class<?> declaredParamType) {
        String castString = "";
        castString += "(" + getTypeName(declaredParamType) + ") ";
        castString += "(" + getTypeName(ClassUtils.primitiveToWrapper(declaredParamType))
                + ") ";

        return castString;
    }

    private String getParameterString(Type[] parameterTypes,
                                      List<VariableReference> parameters, boolean isGenericMethod,
                                      boolean isOverloaded, int startPos) {
        return getParameterString(parameterTypes, parameters, isGenericMethod, isOverloaded, startPos, null);
    }

    private String getParameterString(Type[] parameterTypes,
                                      List<VariableReference> parameters, boolean isGenericMethod,
                                      boolean isOverloaded, int startPos, Type ownerType) {
        return getParameterString(parameterTypes, parameters, isGenericMethod, isOverloaded,
                startPos, ownerType, null);
    }

    private String getParameterString(Type[] parameterTypes,
                                      List<VariableReference> parameters, boolean isGenericMethod,
                                      boolean isOverloaded, int startPos, Type ownerType,
                                      Type[] genericParameterTypes) {
        String parameterString = "";

        for (int i = startPos; i < parameters.size(); i++) {
            if (i > startPos) {
                parameterString += ", ";
            }
            Type declaredParamType = parameterTypes[i];
            Type actualParamType = parameters.get(i).getType();
            String name = getVariableName(parameters.get(i));
            boolean argumentIsNull = "null".equals(name)
                    || isNullInitializedVariable(parameters.get(i));
            if (argumentIsNull) {
                name = "null";
            }
            boolean requiresRawClassCast = requiresRawClassCast(declaredParamType, actualParamType);
            boolean rawClassLiteralArgument = !requiresRawClassCast
                    && isParameterizedClassTargetForClassLiteral(declaredParamType, parameters.get(i));
            Class<?> rawParamClass = declaredParamType instanceof WildcardType ? Object.class
                    : safeErasure(declaredParamType);
            if (!argumentIsNull && !requiresRawClassCast
                    && parameterizedCastIsInconvertible(declaredParamType, parameters.get(i))) {
                // The argument's concrete parameterization differs from the
                // parameter's (e.g. passing a DocumentDeserializer --
                // JsonDeserializer<Document> -- where a JsonDeserializer<Object> is
                // expected). Whether or not erasure-based isAssignable accepts it,
                // javac rejects both the plain invocation and a "(G<X>)" cast as
                // inconvertible. A raw cast makes the (unchecked) conversion legal.
                parameterString += "(" + getClassName(
                        (Class<?>) ((ParameterizedType) declaredParamType).getRawType()) + ") " + name;
                continue;
            }
            if (rawParamClass.isPrimitive() && argumentIsNull) {
                parameterString += getPrimitiveNullCast(rawParamClass);
            } else if (!argumentIsNull && isInconvertibleArrayArgument(declaredParamType, actualParamType)) {
                // "(Foo[]) stringArray" is rejected by javac; the argument is passed as it is
                // and the call result is cast raw (see requiresRawReturnCastForArrayArgument).
            } else if (rawClassLiteralArgument) {
                parameterString += "(" + getTypeName(Class.class) + ") ";
            } else if (isGenericMethod && !(declaredParamType instanceof WildcardType)) {
                if (!declaredParamType.equals(actualParamType) || argumentIsNull
                        || (genericParameterTypes != null && isHiddenConcreteClass(declaredParamType))) {
                    Type genericParameterType = genericParameterTypes == null
                            ? null : genericParameterTypes[i];
                    parameterString += "(" + getAccessibleGenericCastTypeName(declaredParamType, actualParamType,
                            argumentIsNull, genericParameterType) + ") ";
                    // A reference cast only boxes a short/byte constant that keeps its own
                    // cast: "(Short) (-1)" is an int, which javac does not box into Short.
                    Class<?> castClass = safeErasure(declaredParamType);
                    boolean boxesConstant = castClass != null && !castClass.isPrimitive();
                    if (name.contains("(short") && !(boxesConstant && castClass.isAssignableFrom(Short.class))) {
                        name = name.replace("(short)", "");
                    }
                    if (name.contains("(byte") && !(boxesConstant && castClass.isAssignableFrom(Byte.class))) {
                        name = name.replace("(byte)", "");
                    }

                }
            } else if (requiresRawClassCast) {
                parameterString += "(" + getTypeName(Class.class) + ") ";
            } else if (isOverloaded && !argumentIsNull && parameters.get(i).isPrimitive()
                    && isBoxedByReferenceParameter(rawParamClass, parameters.get(i).getVariableClass())) {
                // A primitive handed to a reference parameter is only boxed in javac's second
                // resolution phase, so a sibling overload taking the primitive wins: with
                // remove(Object) and remove(int), "list.remove(intValue)" calls remove(int).
                // The wrapper cast boxes in the first phase, binds the reference overload, and
                // stays valid for a type variable bound to that wrapper.
                parameterString += "(" + getClassName(ClassUtils.primitiveToWrapper(
                        parameters.get(i).getVariableClass())) + ") ";
            } else if (argumentIsNull) {
                // Casting null to a reference type is only needed to disambiguate
                // overloaded signatures. Otherwise it can introduce fragile type
                // references that are unnecessary for compilation.
                if (isOverloaded) {
                    String nullCastTypeName = getNullCastTypeNameForOverloadedCall(
                            declaredParamType, ownerType,
                            genericParameterTypes == null ? null : genericParameterTypes[i]);
                    if (nullCastTypeName != null) {
                        parameterString += "(" + nullCastTypeName + ") ";
                    }
                }
            } else if (!GenericClassUtils.isAssignable(declaredParamType, actualParamType)) {

                if (TypeUtils.isArrayType(declaredParamType)
                        && TypeUtils.isArrayType(actualParamType)) {
                    Class<?> componentClass = safeErasure(declaredParamType).getComponentType();
                    if (componentClass.equals(Object.class)) {
                        GenericClass<?> genericComponentClass = GenericClassFactory.get(componentClass);
                        if (genericComponentClass.hasWildcardOrTypeVariables()) {
                            // If we are assigning a generic array, then we don't need to cast

                        } else {
                            // If we are assigning a non-generic array, then we do need to cast
                            parameterString += "(" + getTypeName(declaredParamType)
                                    + ") ";
                        }
                    } else {
                        // if (!GenericClass.isAssignable(GenericTypeReflector.getArrayComponentType(declaredParamType),
                        // GenericTypeReflector.getArrayComponentType(actualParamType))) {
                        parameterString += "(" + getTypeName(declaredParamType) + ") ";
                    }
                } else if (isUnresolvedTypeVariableOrWildcard(declaredParamType)
                        && rawParamClass != null
                        && rawParamClass.isAssignableFrom(
                        ClassUtils.primitiveToWrapper(safeErasure(actualParamType)))) {
                    // Declared type is a TypeVariable/Wildcard whose erasure (eg, Object
                    // for `E` in HashSet.add(E)) already accepts the actual argument.
                    // An `(Object)` cast here would defeat generic method resolution on
                    // a parameterized receiver (eg, HashSet<String>.add resolves to
                    // add(String), which then rejects an Object argument). Emit no cast.
                } else if (shouldSkipErasedObjectCast(declaredParamType, actualParamType, ownerType)) {
                    // For raw method owners on parameterized receivers (eg, ArrayList<Integer>
                    // with add(Object)), an erased (Object) cast defeats the receiver's generic
                    // method resolution and makes otherwise-valid code uncompilable.
                } else {
                    parameterString += "(" + getCastTypeName(declaredParamType, actualParamType) + ") ";
                }
                if (name.contains("(short")) {
                    name = name.replace("(short)", "");
                }
                if (name.contains("(byte")) {
                    name = name.replace("(byte)", "");
                }
                //}
            } else {
                // We have to cast between wrappers and primitives in case there
                // are overloaded signatures. This could be optimized by checking
                // if there actually is a problem of overloaded signatures
                GenericClass<?> parameterClass = GenericClassFactory.get(declaredParamType);
                if (parameterClass.isWrapperType() && parameters.get(i).isPrimitive()) {
                    parameterString += "(" + getTypeName(declaredParamType) + ") ";
                } else if (parameterClass.isPrimitive()
                        && parameters.get(i).isWrapperType()) {
                    parameterString += "(" + getTypeName(declaredParamType) + ") ";
                } else if (isOverloaded) {
                    // If there is an overloaded method, we need to cast to make sure we use the right version
                    if (!declaredParamType.equals(actualParamType)
                            && !shouldSkipErasedObjectCast(declaredParamType, actualParamType, ownerType)) {
                        parameterString += "(" + getCastTypeName(declaredParamType, actualParamType) + ") ";
                    }
                }
            }

            parameterString += name;
        }

        return parameterString;
    }

    private static boolean isBoxedByReferenceParameter(Class<?> parameterClass, Class<?> primitiveArgument) {
        return parameterClass != null && primitiveArgument != null && primitiveArgument.isPrimitive()
                && !parameterClass.isPrimitive()
                && parameterClass.isAssignableFrom(ClassUtils.primitiveToWrapper(primitiveArgument));
    }

    private String getNullCastTypeNameForOverloadedCall(Type declaredParamType, Type ownerType,
                                                        Type genericParameterType) {
        if (declaredParamType == null) {
            return null;
        }
        // For raw method owners on parameterized receivers, an erased (Object)
        // cast can defeat generic method resolution and make valid code fail.
        // A parameter the method declares as Object is not an erasure, though:
        // without the cast a null argument is ambiguous between the sibling
        // overloads (print(char[]) and print(String) next to print(Object)). An
        // inner class of a non-generic outer is a parameterized owner as well.
        if (!(genericParameterType instanceof Class<?>)
                && shouldSkipErasedObjectCast(declaredParamType, null, ownerType)) {
            return null;
        }
        if (isUnresolvedTypeVariableOrWildcard(declaredParamType)) {
            Class<?> erasedType = safeErasure(declaredParamType);
            if (erasedType == null || erasedType == Object.class || erasedType.isPrimitive()) {
                return null;
            }
            return getTypeName(erasedType);
        }
        if (declaredParamType instanceof Class<?>) {
            Class<?> declaredClass = (Class<?>) declaredParamType;
            if (!declaredClass.isPrimitive() && !declaredClass.isArray()
                    && !isDeclaredTypeNameableInGeneratedTest(declaredClass)) {
                // The parameter was bound to a type the test cannot name (the private Count of
                // a Map<String, Count> field). A cast to a nameable supertype would not convert
                // back to the parameter, so no cast can be written and the bare null is the
                // best that compiles.
                return null;
            }
        }
        if (declaredParamType instanceof ParameterizedType
                && hasRawGenericTypeArgument((ParameterizedType) declaredParamType)) {
            // A raw generic class as a type argument (List<Functor>) is rendered with
            // wildcards (List<Functor<?, ?, ?>>), which is a different type and no longer
            // matches the declared parameter. The raw cast is enough to pick the overload.
            return getClassName((Class<?>) ((ParameterizedType) declaredParamType).getRawType());
        }
        return getTypeName(declaredParamType);
    }

    private boolean hasRawGenericTypeArgument(ParameterizedType type) {
        for (Type argument : type.getActualTypeArguments()) {
            if (argument instanceof Class<?> && argument != Class.class
                    && ((Class<?>) argument).getTypeParameters().length > 0) {
                return true;
            }
            if (argument instanceof ParameterizedType
                    && hasRawGenericTypeArgument((ParameterizedType) argument)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRenderedNullArgument(VariableReference parameter) {
        if (parameter == null) {
            return false;
        }
        String rendered = getVariableName(parameter);
        return "null".equals(rendered) || isNullInitializedVariable(parameter);
    }

    private boolean isApplicableAtInvocation(VariableReference argument, Class<?> parameterType) {
        if (argument == null) {
            return false;
        }
        if (isRenderedNullArgument(argument)) {
            return parameterType == null || !parameterType.isPrimitive();
        }
        return argument.isAssignableTo(parameterType);
    }

    private boolean hasNullOverloadAmbiguity(Class<?>[] thisParameterTypes,
                                             List<Class<?>[]> otherParameterTypes,
                                             List<VariableReference> arguments,
                                             int startPos) {
        if (thisParameterTypes == null || arguments == null || otherParameterTypes == null) {
            return false;
        }
        if (!hasRenderedNullArgument(thisParameterTypes, arguments, startPos)) {
            return false;
        }

        for (Class<?>[] candidateTypes : otherParameterTypes) {
            if (candidateTypes == null || candidateTypes.length != thisParameterTypes.length) {
                continue;
            }
            boolean allArgsApplicableToBoth = true;
            boolean differsAtNullSlot = false;
            for (int i = startPos; i < arguments.size() && i < thisParameterTypes.length; i++) {
                VariableReference argument = arguments.get(i);
                boolean thisApplicable = isApplicableAtInvocation(argument, thisParameterTypes[i]);
                boolean candidateApplicable = isApplicableAtInvocation(argument, candidateTypes[i]);
                if (!(thisApplicable && candidateApplicable)) {
                    allArgsApplicableToBoth = false;
                    break;
                }
                if (isRenderedNullArgument(argument) && !Objects.equals(thisParameterTypes[i], candidateTypes[i])) {
                    differsAtNullSlot = true;
                }
            }
            if (allArgsApplicableToBoth && differsAtNullSlot) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a parameter slot from {@code startPos} on receives an argument rendered as a bare null.
     */
    private boolean hasRenderedNullArgument(Class<?>[] parameterTypes,
                                            List<VariableReference> arguments,
                                            int startPos) {
        for (int i = startPos; i < arguments.size() && i < parameterTypes.length; i++) {
            if (isRenderedNullArgument(arguments.get(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean requiresNullArgumentDisambiguation(Constructor<?> constructor,
                                                       List<VariableReference> arguments,
                                                       int startPos) {
        if (constructor == null || arguments == null) {
            return false;
        }
        Class<?>[] thisParameterTypes = constructor.getParameterTypes();
        if (!hasRenderedNullArgument(thisParameterTypes, arguments, startPos)) {
            return false;
        }
        List<Class<?>[]> alternatives = new ArrayList<>();
        // EvoSuite emits the *_ESTest into the target class's own package, so
        // javac resolves overloads against every non-private constructor, not
        // only the public ones. getConstructors() would miss a protected or
        // package-private overload (for example Jackson's protected copy
        // constructor ObjectMapper(ObjectMapper)), leaving an ambiguous bare
        // null uncast and uncompilable. Enumerate declared constructors and drop
        // only the private ones, which same-package code cannot reference.
        for (Constructor<?> other : constructor.getDeclaringClass().getDeclaredConstructors()) {
            if (other.equals(constructor) || Modifier.isPrivate(other.getModifiers())) {
                continue;
            }
            if (other.getParameterCount() == thisParameterTypes.length) {
                alternatives.add(other.getParameterTypes());
            }
        }
        return hasNullOverloadAmbiguity(thisParameterTypes, alternatives, arguments, startPos);
    }

    private boolean requiresNullArgumentDisambiguation(Method method,
                                                       List<VariableReference> arguments,
                                                       int startPos,
                                                       Class<?> receiverType) {
        if (method == null || arguments == null) {
            return false;
        }
        Class<?>[] thisParameterTypes = method.getParameterTypes();
        if (!hasRenderedNullArgument(thisParameterTypes, arguments, startPos)) {
            return false;
        }
        List<Class<?>[]> alternatives = new ArrayList<>();
        // javac resolves an overloaded call against the *receiver's* static type,
        // not the type that declares the resolved method. When EvoSuite invokes a
        // method inherited from a supertype (e.g. FastDateFormat.format(null) bound
        // to the final java.text.Format.format(Object)), method.getDeclaringClass()
        // is the supertype and would miss the subtype's competing overloads
        // (format(Date)/format(Calendar)), leaving an ambiguous bare null uncast.
        // Enumerate from the receiver type instead, falling back to the declaring
        // class for static calls with no receiver.
        Class<?> overloadOwner = receiverType != null ? receiverType : method.getDeclaringClass();
        // Same-package *_ESTest code resolves overloads against non-private
        // methods. getMethods() gives inherited public overloads, but a
        // protected/package-private overload declared in a *superclass* of the
        // receiver (e.g. Token.Tag.appendAttributeValue(String)/(char[]) invoked on
        // a Token.EndTag receiver) is invisible to both getMethods() (public only)
        // and the receiver's own getDeclaredMethods(). Walk the superclass chain
        // collecting declared methods so inherited non-public overloads are seen.
        // Over-inclusion is harmless: a spurious extra cast still compiles.
        Set<Method> candidates = new LinkedHashSet<>();
        try {
            candidates.addAll(Arrays.asList(overloadOwner.getMethods()));
            for (Class<?> c = overloadOwner; c != null && c != Object.class; c = c.getSuperclass()) {
                candidates.addAll(Arrays.asList(c.getDeclaredMethods()));
            }
        } catch (LinkageError e) {
            // Reflecting over the receiver's methods resolves every type in their
            // signatures, and projects routinely ship classes whose
            // signatures mention optional dependencies that are not on the
            // classpath (lwjgl, JSP EL, Axis, ...). This runs on the search path
            // via getCode(), so it must not abort the run: without the overload
            // list we simply do not cast the null, which is the pre-existing
            // behaviour for these calls.
            return false;
        }
        for (Method other : candidates) {
            if (other.equals(method) || Modifier.isPrivate(other.getModifiers())) {
                continue;
            }
            if (!other.getName().equals(method.getName())) {
                continue;
            }
            if (other.getParameterCount() == thisParameterTypes.length) {
                alternatives.add(other.getParameterTypes());
            }
        }
        return hasNullOverloadAmbiguity(thisParameterTypes, alternatives, arguments, startPos);
    }

    private boolean shouldSkipErasedObjectCast(Type declaredParamType, Type actualParamType, Type ownerType) {
        if (!(ownerType instanceof ParameterizedType) || declaredParamType == null) {
            return false;
        }
        if (isUnresolvedTypeVariableOrWildcard(declaredParamType)) {
            return true;
        }
        if (safeErasure(declaredParamType) != Object.class) {
            return false;
        }
        if (actualParamType == null) {
            return true;
        }
        Class<?> rawActual = safeErasure(actualParamType);
        if (rawActual == null) {
            return false;
        }
        return Object.class.isAssignableFrom(ClassUtils.primitiveToWrapper(rawActual));
    }

    private boolean isNullInitializedVariable(VariableReference reference) {
        if (reference == null || test == null) {
            return false;
        }
        int pos = safePosition(reference);
        if (pos < 0 || pos >= test.size()) {
            return false;
        }
        Statement statement = test.getStatement(pos);
        if (statement instanceof NullStatement) {
            return true;
        }
        if (statement instanceof PrimitiveStatement<?>) {
            return ((PrimitiveStatement<?>) statement).getValue() == null;
        }
        if (statement instanceof UninterpretedStatement) {
            String code = ((UninterpretedStatement) statement).getSourceCode();
            if (code == null) {
                return false;
            }
            String normalized = code.replace('\n', ' ').replace('\r', ' ').trim();
            return normalized.matches(".*=\\s*null\\s*;\\s*$");
        }
        return false;
    }

    /*
     * For Class literals with nested generic arguments, casting to the fully parameterized
     * type may be a compile-time error (e.g. Class<LinkedList> to Class<LinkedList<Integer>>).
     * In those cases we fall back to a raw Class cast.
     */
    /**
     * Generic method instantiation can select a hidden class even though its argument
     * is rendered with an accessible supertype. Use that supertype for concrete class
     * casts, just as for null arguments, instead of naming an inaccessible inferred type.
     * Keep non-null parameterized types on the existing generic conversion path.
     */
    private String getAccessibleGenericCastTypeName(Type declaredParamType, Type actualParamType,
                                                    boolean argumentIsNull, Type genericParameterType) {
        if (argumentIsNull || declaredParamType instanceof Class<?>) {
            Class<?> erased = safeErasure(declaredParamType);
            if (erased != null && !erased.isPrimitive() && !erased.isArray()
                    && !isDeclaredTypeNameableInGeneratedTest(erased)) {
                return getTypeName(findAccessibleGenericSupertype(erased, genericParameterType));
            }
        }
        return getCastTypeName(declaredParamType, actualParamType);
    }

    private boolean isHiddenConcreteClass(Type type) {
        if (!(type instanceof Class<?>)) {
            return false;
        }
        Class<?> clazz = (Class<?>) type;
        return !clazz.isPrimitive() && !clazz.isArray()
                && !isDeclaredTypeNameableInGeneratedTest(clazz);
    }

    /** Preserve a simple public method bound when another accessible interface would violate it. */
    private Class<?> findAccessibleGenericSupertype(Class<?> inferredType, Type genericType) {
        Class<?> accessibleType = findAccessibleRenderableSupertype(inferredType);
        if (genericType instanceof TypeVariable<?>) {
            Type[] bounds = ((TypeVariable<?>) genericType).getBounds();
            if (bounds.length == 1 && bounds[0] instanceof Class<?>) {
                Class<?> bound = (Class<?>) bounds[0];
                if (!bound.isAssignableFrom(accessibleType) && bound.isAssignableFrom(inferredType)
                        && isDeclaredTypeNameableInGeneratedTest(bound)) {
                    return bound;
                }
            }
        }
        return accessibleType;
    }

    private String getCastTypeName(Type declaredParamType, Type actualParamType) {
        if (requiresRawClassCast(declaredParamType, actualParamType)) {
            return getTypeName(Class.class);
        }
        if (requiresRawCastForInconvertibleParameterizedTypes(declaredParamType, actualParamType)) {
            return getTypeName(safeErasure(declaredParamType));
        }
        if (requiresRawCastTypeName(declaredParamType)) {
            return getTypeName(safeErasure(declaredParamType));
        }
        return getTypeName(declaredParamType);
    }

    private boolean requiresRawCastForInconvertibleParameterizedTypes(Type declaredParamType, Type actualParamType) {
        if (!(declaredParamType instanceof ParameterizedType)
                || !(actualParamType instanceof ParameterizedType)) {
            return false;
        }
        ParameterizedType declared = (ParameterizedType) declaredParamType;
        ParameterizedType actual = (ParameterizedType) actualParamType;
        if (declared.equals(actual)) {
            return false;
        }
        // A parameterized subtype with compatible arguments is directly convertible to the
        // declared type (eg LinkedHashSet<Single> -> Iterable<Single>). Erasing that cast is not
        // merely unnecessary: for static generic factories whose return variable is inferred from
        // the assignment target, a raw argument invocation erases the return to its upper bound.
        // That produces uncompilable code such as `Compound c = coalesce((Iterable) values)`.
        if (TypeUtils.isAssignable(actual, declared)) {
            return false;
        }
        Class<?> declaredRaw = safeErasure(declared);
        Class<?> actualRaw = safeErasure(actual);
        if (declaredRaw == null || actualRaw == null) {
            return false;
        }
        // Casting between the same/related raw types but incompatible type arguments
        // can be rejected at compile-time (e.g. ArrayList<A> -> List<B>).
        return declaredRaw.isAssignableFrom(actualRaw) || actualRaw.isAssignableFrom(declaredRaw);
    }

    private boolean requiresRawCastTypeName(Type type) {
        if (type == null) {
            return false;
        }
        if (type instanceof ParameterizedType) {
            for (Type argumentType : ((ParameterizedType) type).getActualTypeArguments()) {
                if (requiresRawCastTypeName(argumentType)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof WildcardType) {
            WildcardType wildcardType = (WildcardType) type;
            for (Type lowerBound : wildcardType.getLowerBounds()) {
                if (requiresRawCastWildcardBound(lowerBound)) {
                    return true;
                }
            }
            for (Type upperBound : wildcardType.getUpperBounds()) {
                if (upperBound == null || Object.class.equals(safeErasure(upperBound))) {
                    continue;
                }
                if (requiresRawCastWildcardBound(upperBound)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof GenericArrayType) {
            return requiresRawCastTypeName(((GenericArrayType) type).getGenericComponentType());
        }
        return false;
    }

    private boolean requiresRawCastWildcardBound(Type bound) {
        if (bound == null) {
            return false;
        }
        if (bound instanceof WildcardType || bound instanceof TypeVariable || bound instanceof CaptureType) {
            return true;
        }
        return requiresRawCastTypeName(bound);
    }

    private boolean isClassType(Type type) {
        if (type instanceof Class<?>) {
            return Class.class.equals(type);
        }
        if (type instanceof ParameterizedType) {
            Type rawType = ((ParameterizedType) type).getRawType();
            return rawType instanceof Class<?> && Class.class.equals(rawType);
        }
        return false;
    }

    /**
     * True when {@code declaredType} and {@code actualType} are both class-literal types
     * (raw {@code Class}, or a parameterised {@code Class<...>}) but are not the same type
     * -- eg a {@code Class<LinkedList>} class-literal variable (the static type of
     * {@code LinkedList.class} per JLS 15.8.2) reaching a {@code Class<LinkedList<String>>}
     * target. {@code GenericClassUtils.isClassLiteralAssignable} deliberately treats that
     * pair as assignable, but javac rejects the direct
     * assignment/invocation/cast-to-named-type in both directions. Every rendering path
     * that can carry such a value across a declared/actual type boundary -- a method-call
     * argument, a direct or field assignment -- must emit a raw {@code (Class)} cast
     * instead when this returns true; that erases the mismatched parameterisation and the
     * conversion from raw {@code Class} to any {@code Class<...>} is always legal
     * (unchecked). This is the single predicate for that one narrow case: it is distinct
     * from the broader, recursive "parameterized cast is inconvertible" check used
     * elsewhere for generic containers in general (eg {@code Set<Class<Object>>} vs raw
     * {@code Set<Class>}), which both erase through a raw cast but are not the same
     * decision and must not be collapsed into this one.
     */
    private boolean requiresRawClassCast(Type declaredType, Type actualType) {
        return isClassType(declaredType) && isClassType(actualType)
                && !declaredType.equals(actualType);
    }

    /**
     * A class-literal variable is always declared at its own raw parameterisation
     * ({@code Class<Comparable> c = Comparable.class}), whatever type the test model gives it.
     * When the target is {@code Class<G<...>>} the model may call the types equal, but the
     * rendered variable is {@code Class<G>}, which javac does not accept there (and which makes
     * the inference of a generic callee fail), so the raw {@code (Class)} cast is needed as well.
     */
    private boolean isParameterizedClassTargetForClassLiteral(Type targetType, VariableReference source) {
        if (!(targetType instanceof ParameterizedType) || !isClassType(targetType)
                || test == null || source == null) {
            return false;
        }
        int position = source.getStPosition();
        if (position < 0 || position >= test.size()
                || !(test.getStatement(position) instanceof ClassPrimitiveStatement)
                || test.getStatement(position).getReturnValue() != source) {
            return false;
        }
        Type[] arguments = ((ParameterizedType) targetType).getActualTypeArguments();
        return arguments.length == 1 && arguments[0] instanceof ParameterizedType;
    }

    private boolean isUnresolvedTypeVariableOrWildcard(Type type) {
        if (type instanceof TypeVariable || type instanceof WildcardType) {
            return true;
        }
        if (type instanceof CaptureType) {
            return ((CaptureType) type).getLowerBounds().length == 0;
        }
        return false;
    }

    static Class<?> safeErasure(Type type) {
        if (type instanceof CaptureType) {
            CaptureType captureType = (CaptureType) type;
            Type[] lowerBounds = captureType.getLowerBounds();
            if (lowerBounds != null && lowerBounds.length > 0 && lowerBounds[0] != null) {
                return GenericTypeReflector.erase(lowerBounds[0]);
            }
            Type[] upperBounds = captureType.getUpperBounds();
            if (upperBounds != null && upperBounds.length > 0 && upperBounds[0] != null) {
                return GenericTypeReflector.erase(upperBounds[0]);
            }
            return Object.class;
        }
        if (type instanceof WildcardType) {
            WildcardType wildcardType = (WildcardType) type;
            Type[] lowerBounds = wildcardType.getLowerBounds();
            if (lowerBounds != null && lowerBounds.length > 0 && lowerBounds[0] != null) {
                return safeErasure(lowerBounds[0]);
            }
            Type[] upperBounds = wildcardType.getUpperBounds();
            if (upperBounds != null && upperBounds.length > 0 && upperBounds[0] != null) {
                return safeErasure(upperBounds[0]);
            }
            return Object.class;
        }
        try {
            return GenericTypeReflector.erase(type);
        } catch (RuntimeException e) {
            // Some custom Type implementations are not supported by gentyref erasure.
            return Object.class;
        }
    }


    @Override
    public void visitUninterpretedStatement(UninterpretedStatement statement) {
        String code = SnippetSourceSupport.normalizeBinaryInnerClassLiterals(statement.getSourceCode());
        String returnExpression =
                SnippetSourceSupport.normalizeBinaryInnerClassLiterals(statement.getReturnExpression());
        if (statement.isParsedFromLlm()) {
            code = SnippetSourceSupport.promoteUndeclaredNewAssignmentsToDeclarations(code, statement.getBindings());
            SnippetSourceSupport.SnippetLocalNameNormalization normalization =
                    snippetSupport.normalizeLlmSnippetLocalNames(code, returnExpression);
            code = normalization.code;
            returnExpression = normalization.returnExpression;
            reservedLocalVariableNames.addAll(normalization.declaredNames);
        }
        // Seed the import resolver with the statement return type before scanning
        // raw snippet text. This lets generic return signatures inside anonymous
        // bodies resolve helper types via same-package / related-type discovery.
        if (statement.getReturnValue() != null && statement.getReturnValue().getType() != null) {
            getClassName(statement.getReturnValue());
        }
        snippetSupport.ensureSnippetImports(code, returnExpression, statement.getBindings());

        // Substitute original LLM variable names with EvoSuite-generated names
        Map<String, VariableReference> bindings = statement.getBindings();
        if (!bindings.isEmpty()) {
            for (Map.Entry<String, VariableReference> entry : bindings.entrySet()) {
                String originalName = entry.getKey();
                String evoName = getSnippetBindingName(entry.getValue());
                if (evoName != null && !originalName.equals(evoName)) {
                    // Replace identifier usages in code only, not inside literals/comments.
                    code = SnippetSourceSupport.replaceIdentifierOutsideLiteralsAndComments(
                            code, originalName, evoName);
                }
            }
        }

        code = CodeEmissionUtils.renderCompilableSource(code);

        testCode.append(code);
        if (!code.endsWith("\n")) {
            testCode.append(NEWLINE);
        }
        if (returnExpression != null && !returnExpression.trim().isEmpty()
                && !statement.getReturnValue().isVoid()) {
            String generatedName = getDeclarationVariableName(statement.getReturnValue());
            if (generatedName != null && !generatedName.equals(returnExpression.trim())) {
                testCode.append(getClassName(statement.getReturnValue()))
                        .append(" ")
                        .append(generatedName)
                        .append(" = ")
                        .append(returnExpression)
                        .append(";")
                        .append(NEWLINE);
            }
        }
        addAssertions(statement);
    }

    @Override
    public void visitFunctionalMockStatement(FunctionalMockStatement st) {

        VariableReference retval = st.getReturnValue();

        // If it is not used, then minimizer will delete the statement anyway
        // boolean unused = test!=null && !test.hasReferences(retval);
        // if(unused){
        //     //no point whatsoever in creating a mock that is never used
        //     return;
        // }

        StringBuffer result = new StringBuffer();

        // By construction, we should avoid cases like:
        //   Object obj = mock(Foo.class);
        // as it leads to problems when setting up "when(...)", and anyway it would make little sense.
        // However, parser/seeding paths can produce compatible-but-not-equal raw/target pairs,
        // e.g. Impl var with Interface target, so we resolve to a concrete compatible raw class.
        Class<?> rawClass = GenericClassFactory.get(retval.getType()).getRawClass();
        Class<?> targetClass = st.getTargetClass();
        rawClass = resolveFunctionalMockRawClass(rawClass, targetClass);
        String rawClassName = getClassName(rawClass);


        //Foo foo = mock(Foo.class);
        String variableType = getClassName(retval);
        // Register the declaration: a later assignment to this local must see it as already
        // declared, or it is rendered as a second declaration under a "_1" name.
        result.append(variableType).append(" ").append(getDeclarationVariableName(retval)).append(" = ");
        if (!variableType.equals(rawClassName)) {
            //this can happen in case of generics, eg
            //Foo<String> foo = (Foo<String>) mock(Foo.class);
            result.append("(").append(variableType).append(") ");
        }

        /*
         * Tricky situation. Ideally, we would want to throw assumption error if a non-mocked method
         * is called, as to avoid false-positives when SUTs evolve.
         * However, it might well be that a test case is not updated, leaving mocks using the default
         * "null" return values. This would crash the JUnit check. Activating the  ViolatedAssumptionAnswer
         * during the search would just make things worse, as negatively effecting the search.
         * So we could just skip it, but this would effect false-positive preventions
         */
        st.completePendingStubsWithNulls();

        // Every rendered mock is stubOnly(), as it is during the search: without it Mockito keeps
        // each invocation in a per-mock list, and a SUT loop that keeps calling a stubbed mock (for
        // instance Scanner over a Readable whose read() always returns 0) retains millions of
        // invocations in the JUnit check until the client heap is exhausted.
        if (st instanceof FunctionalMockForAbstractClassStatement) {
            appendStubOnlyMock(result, rawClassName, "CALLS_REAL_METHODS");
        } else if (st.isUseLenientDefaultAnswer()) {
            // DMoN-promoted mocks use LenientMockAnswer to match the lenient default
            // answer used during search (returns sensible non-null values for all
            // return types).  No explicit doReturn stubs are needed.
            appendStubOnlyMock(result, rawClassName,
                    "new " + getClassName(LenientMockAnswer.class) + "()");
        } else {
            appendStubOnlyMock(result, rawClassName,
                    "new " + getClassName(ViolatedAssumptionAnswer.class) + "()");
        }

        // DMoN lenient mocks: skip doReturn stubs — LenientMockAnswer handles all calls.
        if (!st.isUseLenientDefaultAnswer()) {

            //when(...).thenReturn(...)
            for (MethodDescriptor md : st.getMockedMethods()) {
                if (!md.shouldBeMocked()) {
                    continue;
                }

                List<VariableReference> params = st.getParameters(md.getID());
                if (params == null) {
                    continue;
                }

                GenericClass<?> returnType = md.getReturnClass();

                String parameterString;

                if (!returnType.isPrimitive()) {
                    Type[] types = new Type[params.size()];
                    boolean isOverloaded = false;
                    for (int i = 0; i < types.length; i++) {
                        if (types.length > 1 && returnType.isArray()) {
                            types[i] = Object.class;
                            isOverloaded = true;
                        } else {
                            // Use the method's return type, not the variable's type.
                            // For null references the variable type defaults to Object,
                            // but the cast must match the mocked method's return type
                            // (e.g. String, not Object) to produce compilable code.
                            types[i] = returnType.getType();
                            if (isDefinitelyInconvertibleReferenceCast(types[i], params.get(i).getType())) {
                                // The search stubbed an erased type variable with a value of
                                // another type ((String) typeEnum does not compile); doReturn
                                // takes Object, so pass the value uncast, as it was returned.
                                types[i] = Object.class;
                            }
                        }
                    }

                    parameterString = getParameterString(types, params, false, isOverloaded, 0);
                    parameterString = inlineForwardReferencedMockReturnValues(
                            parameterString, params, st.getPosition());
                    parameterString = sanitizeMockitoDoReturnArguments(parameterString, params);
                    // TODO unsure of these parameters
                } else {

                    //if return type is a primitive, then things can get complicated due to autoboxing :(

                    parameterString = getParameterStringForFMthatReturnPrimitive(returnType.getRawClass(), params);
                    parameterString = inlineForwardReferencedMockReturnValues(
                            parameterString, params, st.getPosition());
                }

                // this does not work when throwing exception as default answer
                // result += "when("+getVariableName(retval)+"."+md.getMethodName()
                //         +"("+md.getInputParameterMatchers()+"))";
                // result += ".thenReturn( ";
                // result += parameterString + " );"+NEWLINE;

                // Mockito doReturn() only takes single arguments. So we need to make sure
                // that in the generated tests we import MockitoExtension class
                //parameterString = "doReturn(" + parameterString.replaceAll(", ", ").doReturn(") + ")";
                //result += parameterString+".when("+getVariableName(retval)+")";
                
                java.lang.reflect.Method originalMethod = md.getMethod();
                try {
                    // Mockito sometimes intercepts methods on the generated proxy class.
                    // To resolve generic type parameters correctly, we need the method
                    // from the original target interface/class.
                    originalMethod = st.getTargetClass().getMethod(
                            md.getMethodName(), md.getMethod().getParameterTypes());
                    // The erased signature of a generic interface method (Comparable.compareTo(T))
                    // finds the bridge compareTo(Object) of a class implementing Comparable<Self>.
                    // Its Object parameter yields nullable(Object.class), which javac cannot
                    // infer against the real compareTo(Self).
                    originalMethod = org.evosuite.testcase.fm.MethodDescriptor
                            .resolveBridgedMethod(originalMethod);
                } catch (Exception e) {
                    // Fallback
                }
                org.evosuite.testcase.fm.MethodDescriptor updatedMd =
                        new org.evosuite.testcase.fm.MethodDescriptor(originalMethod, retval.getGenericClass());
                
                // The variable may be declared wider than the mocked class (Object for an
                // erased type variable), and the stubbed method must resolve on the receiver.
                String stubReceiver = getVariableName(retval);
                // An unresolved type variable is declared Object although its erasure is the bound.
                Type declaredVariableType = retval.getType();
                Class<?> declaredVariableClass =
                        declaredVariableType instanceof Class<?> || declaredVariableType instanceof ParameterizedType
                                ? retval.getVariableClass() : Object.class;
                if (!originalMethod.getDeclaringClass().isAssignableFrom(declaredVariableClass)
                        && originalMethod.getDeclaringClass().isAssignableFrom(rawClass)) {
                    stubReceiver = "(" + rawClassName + ") " + stubReceiver;
                } else if (hasWildcardTypeArgument(declaredVariableType)
                        && originalMethod.getDeclaringClass().isAssignableFrom(rawClass)) {
                    // Calling on a Comparable<?> captures the wildcard, which then clashes
                    // with the Class<T> argument of matchers such as nullable(Object.class).
                    stubReceiver = "(" + rawClassName + ") " + stubReceiver;
                }
                result.append("doReturn(").append(parameterString)
                        .append(").when(").append(stubReceiver).append(")");
                result.append(".").append(md.getMethodName()).append("(")
                        .append(updatedMd.getInputParameterMatchers()).append(");").append(NEWLINE);

            }

        } // end if (!st.isUseLenientDefaultAnswer())

        testCode.append(result);
    }

    private static boolean hasWildcardTypeArgument(Type type) {
        if (!(type instanceof ParameterizedType)) {
            return false;
        }
        for (Type argument : ((ParameterizedType) type).getActualTypeArguments()) {
            if (argument instanceof WildcardType) {
                return true;
            }
        }
        return false;
    }

    private Class<?> resolveFunctionalMockRawClass(Class<?> variableRawClass, Class<?> mockedTargetClass) {
        if (variableRawClass.equals(mockedTargetClass)) {
            return variableRawClass;
        }

        if (variableRawClass.isAssignableFrom(mockedTargetClass)) {
            return mockedTargetClass;
        }

        if (mockedTargetClass.isAssignableFrom(variableRawClass)) {
            return variableRawClass;
        }

        throw new IllegalStateException("Mismatch between variable raw type " + variableRawClass
                + " and mocked " + mockedTargetClass);
    }

    private String getParameterStringForFMthatReturnPrimitive(Class<?> returnType, List<VariableReference> parameters) {

        assert returnType.isPrimitive();
        String parameterString = "";

        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                parameterString += ", ";
            }
            String name = getVariableName(parameters.get(i));
            Class<?> parameterType = parameters.get(i).getVariableClass();

            if (returnType.equals(parameterType)) {
                parameterString += name;
                continue;
            }

            GenericClass<?> parameterClass = GenericClassFactory.get(parameterType);
            if (parameterClass.isWrapperType()) {

                boolean isRightWrapper = false;

                if (Integer.class.equals(parameterClass.getRawClass())) {
                    isRightWrapper = returnType.equals(Integer.TYPE);
                } else if (Character.class.equals(parameterClass.getRawClass())) {
                    isRightWrapper = returnType.equals(Character.TYPE);
                } else if (Boolean.class.equals(parameterClass.getRawClass())) {
                    isRightWrapper = returnType.equals(Boolean.TYPE);
                } else if (Float.class.equals(parameterClass.getRawClass())) {
                    isRightWrapper = returnType.equals(Float.TYPE);
                } else if (Double.class.equals(parameterClass.getRawClass())) {
                    isRightWrapper = returnType.equals(Double.TYPE);
                } else if (Long.class.equals(parameterClass.getRawClass())) {
                    isRightWrapper = returnType.equals(Long.TYPE);
                } else if (Short.class.equals(parameterClass.getRawClass())) {
                    isRightWrapper = returnType.equals(Short.TYPE);
                } else if (Byte.class.equals(parameterClass.getRawClass())) {
                    isRightWrapper = returnType.equals(Byte.TYPE);
                }

                if (isRightWrapper) {
                    parameterString += name;
                    continue;
                }
            }

            //if we arrive here, it means types are different and not a right wrapper (eg Integer for int)
            parameterString += "(" + returnType.getName() + ")" + name;

            if (parameterClass.isWrapperType()) {
                if (Integer.class.equals(parameterClass.getRawClass())) {
                    parameterString += ".intValue()";
                } else if (Character.class.equals(parameterClass.getRawClass())) {
                    parameterString += ".charValue()";
                } else if (Boolean.class.equals(parameterClass.getRawClass())) {
                    parameterString += ".booleanValue()";
                } else if (Float.class.equals(parameterClass.getRawClass())) {
                    parameterString += ".floatValue()";
                } else if (Double.class.equals(parameterClass.getRawClass())) {
                    parameterString += ".doubleValue()";
                } else if (Long.class.equals(parameterClass.getRawClass())) {
                    parameterString += ".longValue()";
                } else if (Short.class.equals(parameterClass.getRawClass())) {
                    parameterString += ".shortValue()";
                } else if (Byte.class.equals(parameterClass.getRawClass())) {
                    parameterString += ".byteValue()";
                }
            }
        }


        return parameterString;
    }

    private String sanitizeMockitoDoReturnArguments(String parameterString, List<VariableReference> params) {
        if (parameterString == null || parameterString.isEmpty() || params == null || params.isEmpty()) {
            return parameterString;
        }
        boolean allNullByReference = true;
        for (VariableReference param : params) {
            if (!(param instanceof NullReference)) {
                allNullByReference = false;
                break;
            }
        }
        boolean allNullByLiteral = ALL_NULL_MOCKITO_DORETURN_ARGS.matcher(parameterString).matches();
        if (!allNullByReference && !allNullByLiteral) {
            return parameterString;
        }

        int argumentCount = allNullByReference ? params.size() : parameterString.split(",").length;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < argumentCount; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("(Object) null");
        }
        return sb.toString();
    }

    /**
     * Functional-mock stubbing occasionally references values that are declared
     * later in the statement list (e.g., doReturn(int0)... with int0 declared
     * after the mock statement). Such forward references are not legal Java.
     * For primitive/null/enum/class literal statements we can safely inline the
     * literal expression to keep emitted code compilable.
     */
    private String inlineForwardReferencedMockReturnValues(String parameterString,
                                                           List<VariableReference> params,
                                                           int mockStatementPosition) {
        if (parameterString == null || parameterString.isEmpty() || params == null || params.isEmpty()
                || test == null) {
            return parameterString;
        }
        String rewritten = parameterString;
        for (VariableReference param : params) {
            if (param == null) {
                continue;
            }
            int position = safePosition(normalizeVariableReference(param));
            if (position <= mockStatementPosition || position >= test.size()) {
                continue;
            }
            String variableName = getVariableName(param);
            if (variableName == null || variableName.isEmpty()) {
                continue;
            }
            String inlineValue = getInlineValueExpressionForForwardReference(position);
            if (inlineValue == null || inlineValue.isEmpty()) {
                continue;
            }
            rewritten = rewritten.replaceAll(
                    "(?<![A-Za-z0-9_$])" + Pattern.quote(variableName) + "(?![A-Za-z0-9_$])",
                    java.util.regex.Matcher.quoteReplacement(inlineValue));
        }
        return rewritten;
    }

    private String getInlineValueExpressionForForwardReference(int statementPosition) {
        Statement sourceStatement = test.getStatement(statementPosition);
        if (sourceStatement instanceof NullStatement) {
            return "null";
        }
        if (sourceStatement instanceof EnumPrimitiveStatement<?>) {
            return getEnumValue((EnumPrimitiveStatement<?>) sourceStatement);
        }
        if (sourceStatement instanceof ClassPrimitiveStatement) {
            Object value = ((ClassPrimitiveStatement) sourceStatement).getValue();
            if (!(value instanceof Class<?>)) {
                return null;
            }
            return getClassName((Class<?>) value) + ".class";
        }
        if (sourceStatement instanceof StringPrimitiveStatement) {
            Object value = ((StringPrimitiveStatement) sourceStatement).getValue();
            if (value == null) {
                return "null";
            }
            return "\"" + StringUtil.getEscapedString((String) value) + "\"";
        }
        if (sourceStatement instanceof PrimitiveStatement<?>) {
            Object value = ((PrimitiveStatement<?>) sourceStatement).getValue();
            return NumberFormatter.getNumberString(value, this);
        }
        return null;
    }



    /*
     * (non-Javadoc)
     *
     * @see org.evosuite.testcase.TestVisitor#visitMethodStatement(org.evosuite.testcase.MethodStatement)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitMethodStatement(MethodStatement statement) {
        String result = "";
        VariableReference retval = statement.getReturnValue();
        GenericMethod method = statement.getMethod();
        Throwable exception = getException(statement);
        List<VariableReference> parameters = statement.getParameterReferences();
        if (!this.methodNames.containsKey(retval) && VariableNameStrategyFactory.gatherInformation()) {
            this.methodNames.put(retval, method.getName());
        }
        if (!this.argumentNames.containsKey(retval) && VariableNameStrategyFactory.gatherInformation()) {
            final List<String> parameterNames = (List<String>)statement.obtainParameterNameListInOrder();
            int idx = 0;
            for (final VariableReference param : parameters) {
                this.argumentNames.put(param, parameterNames.get(idx));
                ++idx;
            }
        }
        if (retval.isVoid() && VariableNameStrategyFactory.gatherInformation()) {
            final List<String> parameterNames = (List<String>)statement.obtainParameterNameListInOrder();
            int idx = 0;
            for (final VariableReference param : parameters) {
                this.argumentNames.put(param, parameterNames.get(idx));
                ++idx;
            }
        }
        String declarationVarName = getDeclarationVariableName(retval);
        // An instantiated GenericMethod may have no unresolved type parameters left, while the
        // underlying Java invocation is still generic and subject to raw-invocation erasure.
        boolean isGenericMethod = method.hasTypeParameters()
                || method.getMethod().getTypeParameters().length > 0;
        String declarationTypeName = getMethodReturnDeclarationType(method, retval);
        if (exception != null && !statement.isDeclaredException(exception)) {
            result += "// Undeclared exception!" + NEWLINE;
        }
        boolean lastStatement = statement.getPosition() == statement.getTestCase().size() - 1;
        boolean referenced = test != null && (test.hasReferences(retval)
                || assertionReferencesReturnValue(statement, retval)
                || assertionReferencesReturnValueInTest(retval));
        boolean unused = !assertionsEnabledForRendering() ? exception != null : !referenced;

        if (!retval.isVoid() && retval.getAdditionalVariableReference() == null
                && !unused) {
            if (exception != null) {
                if (!lastStatement || statement.hasAssertions()) {
                    result += declarationTypeName + " " + declarationVarName
                            + " = " + retval.getDefaultValueString() + ";" + NEWLINE;
                }
            } else {
                result += declarationTypeName + " ";
            }
        }
        if (shouldUseTryCatch(exception, statement.isDeclaredException(exception))) {
            result += "try {" + NEWLINE + "    ";
        }
        Type ownerTypeForParameterRendering = statement.getCallee() != null
                ? statement.getCallee().getType()
                : method.getOwnerType();
        boolean forceRawReceiverInvocation = shouldForceRawReceiverInvocation(
                statement, method, parameters);
        Type[] renderedParameterTypes = forceRawReceiverInvocation
                ? method.getMethod().getParameterTypes()
                : method.getParameterTypes();
        renderedParameterTypes = preserveConcreteGenericParameterDeclarations(
                method, renderedParameterTypes);
        Type parameterRenderingOwnerType = forceRawReceiverInvocation
                ? method.getMethod().getDeclaringClass()
                : ownerTypeForParameterRendering;
        Class<?> overloadReceiverType = statement.getCallee() != null
                ? statement.getCallee().getVariableClass() : null;
        boolean overloadedMethodCall = method.isOverloaded(parameters, overloadReceiverType)
                || requiresNullArgumentDisambiguation(
                        method.getMethod(), parameters, 0, overloadReceiverType);
        String parameterString = getParameterString(renderedParameterTypes,
                parameters, isGenericMethod,
                overloadedMethodCall, 0, parameterRenderingOwnerType,
                method.getMethod().getGenericParameterTypes());

        String calleeStr = "";
        // The class the receiver is cast to; written raw, it erases a generic return type.
        Class<?> receiverCastClass = null;
        if (method.isStatic()) {
            calleeStr += getClassName(method.getMethod().getDeclaringClass());
        } else {
            VariableReference callee = statement.getCallee();
            Class<?> bridgeSource;
            if (forceRawReceiverInvocation) {
                Class<?> declaringClass = method.getMethod().getDeclaringClass();
                Class<?> calleeClass = callee.getVariableClass();
                String calleeName = getVariableName(callee);
                receiverCastClass = declaringClass;
                if (calleeClass != null && !declaringClass.isAssignableFrom(calleeClass)) {
                    calleeStr += "((" + getTypeName(declaringClass) + ")(Object)" + calleeName + ")";
                } else {
                    calleeStr += "((" + getTypeName(declaringClass) + ")" + calleeName + ")";
                }
            } else if (!(callee instanceof ConstantValue)
                    && (bridgeSource = bridgeSourceDeclaringClass(method.getMethod())) != null) {
                // The search called a synthetic bridge, typically to feed an argument of the
                // wrong type into a generic override. Source code naming the method binds to the
                // override with the specific parameter type; viewing the receiver as the generic
                // declaring class binds to the erased signature the bridge implements.
                receiverCastClass = bridgeSource;
                calleeStr += "((" + getClassName(bridgeSource) + ") " + getVariableName(callee) + ")";
            } else if (!(callee instanceof ConstantValue)
                    && (bridgeSource = genericDeclaringClassOverriddenByBridge(method.getMethod(),
                    callee.getVariableClass())) != null) {
                // The generic method itself was called on a subclass receiver whose override
                // narrows the parameter type (ResourceTrackerImpl<T>.calculateBytes(T) on a
                // StringTracker, argument not a String). Source code naming the method on the
                // subclass binds to the override; the raw generic class binds to this method.
                receiverCastClass = bridgeSource;
                calleeStr += "((" + getClassName(bridgeSource) + ") " + getVariableName(callee) + ")";
            } else if (callee instanceof ConstantValue) {
                Class<?> declaringClass = method.getMethod().getDeclaringClass();
                Class<?> calleeClass = callee.getVariableClass();
                String calleeName = getVariableName(callee);
                // If the constant is not assignable to the declaring class, add an (Object) cast first
                // to ensure the generated code compiles (e.g., (Target)(Object)"str").
                receiverCastClass = declaringClass;
                if (calleeClass != null && !declaringClass.isAssignableFrom(calleeClass)) {
                    calleeStr += "((" + getClassName(declaringClass) + ")(Object)" + calleeName + ")";
                } else {
                    calleeStr += "((" + getClassName(declaringClass) + ")" + calleeName + ")";
                }
            } else if (isShadowedByInaccessibleOverride(method.getMethod(), callee.getVariableClass())) {
                // The receiver's static type overrides this protected/package-private method in
                // another package, where javac resolves the call and finds it inaccessible.
                // Viewing the receiver as the declaring class (which is in the test's package)
                // resolves it there; the call still dispatches to the override at run time.
                receiverCastClass = method.getMethod().getDeclaringClass();
                calleeStr += "((" + getTypeName(method.getMethod().getDeclaringClass()) + ") "
                        + getVariableName(callee) + ")";
            } else {
                boolean calleeDeclaresMethod =
                        hasMethodBySignatureName(callee.getVariableClass(), method.getMethod());
                // Only a package-private method can become accessible through this cast. The
                // declaring package must also be the package of the generated test.
                if (method.isDefault() && !method.getDeclaringClass().equals(callee.getVariableClass())
                        && callee.isAssignableTo(method.getMethod().getDeclaringClass())) {
                    String packageName1 = ClassUtils.getPackageName(method.getDeclaringClass());
                    String packageName2 = ClassUtils.getPackageName(callee.getVariableClass());
                    if (packageName1.equals(Properties.CLASS_PREFIX)
                            && !packageName1.equals(packageName2)) {
                        receiverCastClass = method.getMethod().getDeclaringClass();
                        calleeStr += "((" + getClassName(method.getMethod().getDeclaringClass())
                                + ")" + getVariableName(callee) + ")";
                    } else {
                        calleeStr += getVariableName(callee);
                    }
                } else if (!calleeDeclaresMethod
                        || !callee.isAssignableTo(method.getMethod().getDeclaringClass())) {
                    try {
                        // If the concrete callee class has that method then it's ok
                        callee.getVariableClass().getDeclaredMethod(method.getName(), method.getRawParameterTypes());
                        calleeStr += getVariableName(callee);
                    } catch (NoSuchMethodException | NoClassDefFoundError e) {
                        // If not we need to cast to the subtype. If callee is unrelated,
                        // cast via Object to keep code compilable.
                        Class<?> declaringClass = method.getMethod().getDeclaringClass();
                        Class<?> calleeClass = callee.getVariableClass();
                        receiverCastClass = declaringClass;
                        if (calleeClass != null && !declaringClass.isAssignableFrom(calleeClass)) {
                            calleeStr += "((" + getTypeName(declaringClass) + ")(Object)"
                                    + getVariableName(callee) + ")";
                        } else {
                            calleeStr += "((" + getTypeName(declaringClass) + ") "
                                    + getVariableName(callee) + ")";
                        }
                        // TODO: Here we could check if this is actually possible
                        // ...but what would we do?
                        // if(!ClassUtils.getAllSuperclasses(method.getMethod().getDeclaringClass())
                        // .contains(callee.getVariableClass())) {
                        //}
                    }
                } else {
                    calleeStr += getVariableName(callee);
                }
            }
        }

        String returnCastStr = "";
        boolean requiresReturnCast = !unused
                && ((!retval.isAssignableFrom(method.getReturnType())
                || requiresReturnValueCastForRawReceiver(statement, method, retval)
                || requiresReturnCastDueToRawParameterCast(
                        isGenericMethod, renderedParameterTypes, parameters, method, retval))
                || (forceRawReceiverInvocation
                && dependsOnGenericTypeInformation(method.getMethod().getGenericReturnType()))
                || requiresReturnCastForRawReceiverCast(receiverCastClass, method, retval)
                || requiresStaticGenericReturnCast(method, retval))
                && !retval.getVariableClass().isAnonymousClass()
                // Static generic methods are a special case where we shouldn't add a cast
                && !(isGenericMethod && method.getParameterTypes().length == 0 && method.isStatic());
        boolean rawReturnCastForRedeclaredReceiver = !unused
                && (method.isStatic()
                ? isGenericMethod && method.getParameterTypes().length == 0
                && uninferableStaticGenericReturn(method, retval)
                : returnDependsOnRedeclaredReceiver(statement, method, retval));
        boolean rawReturnCastForArrayArgument = !unused && !retval.isVoid()
                && retval.getType() instanceof ParameterizedType
                && requiresRawReturnCastForArrayArgument(renderedParameterTypes, parameters);
        if (requiresReturnCast || rawReturnCastForRedeclaredReceiver || rawReturnCastForArrayArgument) {
            String name = getAccessibleGenericReturnTypeName(method, retval);
            if (!name.matches(".*\\.\\d+$")) {
                if (retval.getType() instanceof ParameterizedType
                        && (rawReturnCastForRedeclaredReceiver
                        || rawReturnCastForArrayArgument
                        || returnCastIsInconvertible(retval.getType(),
                                method.getMethod().getGenericReturnType()))) {
                    // The declared parameterization is generic-inconvertible with the
                    // method's return type -- typically a variable typed
                    // Set<Class<Object>> receiving a raw Set<Class> return (old
                    // Mockito-internal APIs). "(Set<Class<Object>>) x" does not
                    // compile; a raw cast is an unchecked but legal conversion that
                    // still assigns to the parameterized variable.
                    returnCastStr = "(" + getClassName(
                            (Class<?>) ((ParameterizedType) retval.getType()).getRawType()) + ")";
                } else {
                    returnCastStr = "(" + name + ")";
                }
            }
        }
        calleeStr = returnCastStr + calleeStr;
        if (retval.isVoid()) {
            result += calleeStr + "." + method.getName() + "(" + parameterString + ");";
        } else {
            // if (exception == null || !lastStatement)
            if (!unused) {
                result += declarationVarName + " = ";
            }
            // If unused, then we don't want to print anything:
            //else
            //    result += getClassName(retval) + " " + getVariableName(retval) + " = ";

            result += calleeStr + "." + method.getName() + "(" + parameterString + ");";
        }

        if (shouldUseTryCatch(exception, statement.isDeclaredException(exception))) {
            if (assertionsEnabledForRendering()) {
                result += generateFailAssertion(statement, exception);
            }

            result += NEWLINE + "}";// end try block

            result += generateCatchBlock(statement, exception);
        }

        testCode.append(result + NEWLINE);
        addAssertions(statement);
    }

    /**
     * The declaring class of the generic, non-bridge {@code method} when a strict subclass
     * {@code calleeClass} of it declares a bridge with the same erased signature (the subclass
     * overrides the method with a narrower parameter type); null otherwise or when the
     * declaring class cannot be named from the test.
     */
    private static Class<?> genericDeclaringClassOverriddenByBridge(Method method, Class<?> calleeClass) {
        if (method == null || calleeClass == null || method.isBridge()
                || Modifier.isStatic(method.getModifiers())) {
            return null;
        }
        Class<?> declaring = method.getDeclaringClass();
        if (declaring == calleeClass || !declaring.isAssignableFrom(calleeClass)
                || !Modifier.isPublic(declaring.getModifiers())
                || Arrays.equals(method.getGenericParameterTypes(), method.getParameterTypes())) {
            return null;
        }
        try {
            for (Class<?> current = calleeClass; current != null && current != declaring;
                 current = current.getSuperclass()) {
                Method[] declared = current.getDeclaredMethods();
                for (Method candidate : declared) {
                    if (candidate.isBridge() && candidate.getName().equals(method.getName())
                            && Arrays.equals(candidate.getParameterTypes(),
                            method.getParameterTypes())
                            && declaresNarrowingOverride(declared, method)) {
                        return declaring;
                    }
                }
            }
        } catch (LinkageError e) {
            // unresolvable hierarchy: leave the call as it is
        }
        return null;
    }

    /**
     * Whether {@code declared} contains a real (non-bridge) override of {@code method} with
     * narrower parameter types. A bridge alone does not show one: javac also adds a bridge to a
     * public class for every public method it inherits from a package-private superclass
     * (PatriciaTrie.put for AbstractPatriciaTrie.put), and that bridge overrides nothing.
     */
    private static boolean declaresNarrowingOverride(Method[] declared, Method method) {
        Class<?>[] erased = method.getParameterTypes();
        for (Method candidate : declared) {
            if (candidate.isBridge() || Modifier.isStatic(candidate.getModifiers())
                    || !candidate.getName().equals(method.getName())
                    || candidate.getParameterCount() != erased.length
                    || Arrays.equals(candidate.getParameterTypes(), erased)) {
                continue;
            }
            Class<?>[] narrowed = candidate.getParameterTypes();
            boolean narrower = true;
            for (int i = 0; i < erased.length && narrower; i++) {
                narrower = erased[i].isAssignableFrom(narrowed[i]);
            }
            if (narrower) {
                return true;
            }
        }
        return false;
    }

    /**
     * For a bridge method that exists because a subclass overrides a generic method with a more
     * specific parameter type, the supertype declaring the generic method, whose erased signature
     * equals the bridge's; null for any other method (including covariant-return bridges, which
     * source code resolves correctly) or when that supertype cannot be named from the test.
     */
    private static Class<?> bridgeSourceDeclaringClass(Method method) {
        if (method == null || !method.isBridge() || Modifier.isStatic(method.getModifiers())) {
            return null;
        }
        Class<?> bridgeOwner = method.getDeclaringClass();
        Deque<Class<?>> pending = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        pending.add(bridgeOwner);
        while (!pending.isEmpty()) {
            Class<?> current = pending.poll();
            if (!seen.add(current)) {
                continue;
            }
            if (current != bridgeOwner) {
                try {
                    Method candidate = current.getDeclaredMethod(method.getName(),
                            method.getParameterTypes());
                    if (!candidate.isBridge() && !Modifier.isStatic(candidate.getModifiers())
                            && !Modifier.isPrivate(candidate.getModifiers())
                            && candidate.getGenericParameterTypes().length > 0
                            && !Arrays.equals(candidate.getGenericParameterTypes(),
                            candidate.getParameterTypes())
                            && Modifier.isPublic(current.getModifiers())
                            && declaresNarrowingOverride(bridgeOwner.getDeclaredMethods(), candidate)) {
                        return current;
                    }
                } catch (NoSuchMethodException | LinkageError e) {
                    // keep looking
                }
            }
            try {
                if (current.getSuperclass() != null) {
                    pending.add(current.getSuperclass());
                }
                pending.addAll(Arrays.asList(current.getInterfaces()));
            } catch (LinkageError e) {
                // unresolvable hierarchy, stop here
            }
        }
        return null;
    }

    /**
     * Whether javac, resolving {@code method} on a receiver of static type {@code calleeClass},
     * would find a non-public override declared in a package other than the generated test's
     * (JLS 6.6.2: the test is neither in that package nor a subclass), although {@code method}
     * itself is declared in the test's package. The instrumentation widens the members of
     * classes in the test's package to public, so reflection cannot reveal this on
     * {@code method}; the override's class lives elsewhere and keeps its real modifiers.
     * Only then is a cast to the declaring class both necessary and legal.
     */
    private static boolean isShadowedByInaccessibleOverride(Method method, Class<?> calleeClass) {
        if (method == null || calleeClass == null || Modifier.isStatic(method.getModifiers())) {
            return false;
        }
        Class<?> declaringClass = method.getDeclaringClass();
        String testPackageName = getGeneratedTestPackageName();
        if (declaringClass == calleeClass || isEmptyPackageName(testPackageName)
                || !declaringClass.isAssignableFrom(calleeClass)
                || !isSamePackage(declaringClass, testPackageName)
                || !isTypeAccessibleFromGeneratedTest(declaringClass, testPackageName)) {
            return false;
        }
        for (Class<?> current = calleeClass; current != null && current != declaringClass;
                current = current.getSuperclass()) {
            Method override;
            try {
                override = current.getDeclaredMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException | LinkageError e) {
                continue;
            }
            int modifiers = override.getModifiers();
            if (Modifier.isPrivate(modifiers) || Modifier.isStatic(modifiers)
                    || override.isBridge()) {
                continue;
            }
            // The most derived declaration is the one javac picks.
            return !Modifier.isPublic(modifiers) && !isSamePackage(current, testPackageName);
        }
        return false;
    }

    /**
     * A receiver cast to a generic class is written raw ({@code ((AbstractPatriciaTrie) trie)}),
     * and every member of a raw type is erased: a method returning {@code V} returns
     * {@code Object} to javac, which cannot be assigned to the variable's type without a cast.
     */
    private boolean requiresReturnCastForRawReceiverCast(Class<?> receiverCastClass,
                                                         GenericMethod method,
                                                         VariableReference retval) {
        if (receiverCastClass == null || retval == null || retval.isVoid() || method.isStatic()
                || receiverCastClass.getTypeParameters().length == 0
                || !dependsOnGenericTypeInformation(method.getMethod().getGenericReturnType())) {
            return false;
        }
        Class<?> retvalClass = retval.getVariableClass();
        Class<?> erasedReturnClass = method.getMethod().getReturnType();
        return retvalClass != null && erasedReturnClass != null
                && !ClassUtils.isAssignable(erasedReturnClass, retvalClass, true);
    }

    private boolean requiresStaticGenericReturnCast(GenericMethod method, VariableReference retval) {
        if (method == null || retval == null || !method.isStatic()) {
            return false;
        }
        Type genericReturn = method.getMethod().getGenericReturnType();
        if (!dependsOnGenericTypeInformation(genericReturn)) {
            return false;
        }
        Class<?> erasedReturn = method.getMethod().getReturnType();
        Class<?> renderedReturn = retval.getVariableClass();
        return erasedReturn != null && renderedReturn != null
                && !erasedReturn.equals(renderedReturn)
                && erasedReturn.isAssignableFrom(renderedReturn);
    }

    /**
     * Generic return-type instantiation can occasionally over-specialize an unrelated parameter
     * to the concrete argument class. In particular, a class implementing a raw generic interface
     * can replace a declaration such as {@code Iterable<Single>} with the raw implementation type.
     * Rendering that invocation as raw erases a method-level generic return to its upper bound.
     * Restore reflective parameterized declarations that are already fully concrete; declarations
     * containing method/owner variables still need the normal instantiated types.
     */
    private Type[] preserveConcreteGenericParameterDeclarations(GenericMethod method,
                                                                  Type[] renderedTypes) {
        if (method == null || renderedTypes == null
                || (method.getMethod().getTypeParameters().length == 0
                && !method.hasTypeParameters())) {
            return renderedTypes;
        }
        Type[] declaredTypes = method.getMethod().getGenericParameterTypes();
        if (declaredTypes.length != renderedTypes.length) {
            return renderedTypes;
        }
        Type[] result = renderedTypes.clone();
        for (int i = 0; i < declaredTypes.length; i++) {
            Type declared = declaredTypes[i];
            if (declared instanceof ParameterizedType
                    && !containsUnresolvedTypeComponent(declared)
                    && !(renderedTypes[i] instanceof ParameterizedType)) {
                result[i] = declared;
            }
        }
        return result;
    }

    private boolean containsUnresolvedTypeComponent(Type type) {
        if (type instanceof TypeVariable || type instanceof WildcardType || type instanceof CaptureType) {
            return true;
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) type;
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (containsUnresolvedTypeComponent(argument)) {
                    return true;
                }
            }
            return parameterized.getOwnerType() != null
                    && containsUnresolvedTypeComponent(parameterized.getOwnerType());
        }
        if (type instanceof GenericArrayType) {
            return containsUnresolvedTypeComponent(
                    ((GenericArrayType) type).getGenericComponentType());
        }
        return false;
    }

    private boolean shouldForceRawReceiverInvocation(MethodStatement statement,
                                                     GenericMethod method,
                                                     List<VariableReference> parameters) {
        if (statement == null || method == null || method.isStatic()
                || parameters == null || statement.getCallee() == null) {
            return false;
        }
        Type calleeType = declaredReceiverType(statement);
        if (!(calleeType instanceof ParameterizedType)) {
            return false;
        }
        Type[] declaredParameterTypes = resolveMethodParameterTypesForReceiver(method, calleeType);
        if (declaredParameterTypes.length != parameters.size()) {
            return false;
        }
        Type[] modelParameterTypes = method.getParameterTypes();

        for (int i = 0; i < declaredParameterTypes.length; i++) {
            VariableReference argument = parameters.get(i);
            if (isRenderedNullArgument(argument)) {
                continue;
            }

            Type declaredParameterType = declaredParameterTypes[i];
            Type actualParameterType = argument.getType();
            if (GenericClassUtils.isAssignable(declaredParameterType, actualParameterType)) {
                continue;
            }
            if (isUnresolvedTypeVariableOrWildcard(declaredParameterType)) {
                return true;
            }
            if (isDefinitelyInconvertibleReferenceCast(declaredParameterType, actualParameterType)) {
                return true;
            }
            // A constant is rendered as its literal, whatever type the variable declares: an
            // Object-typed "value" is a String to javac, and (Foo) "value" does not compile.
            if (argument instanceof ConstantValue && ((ConstantValue) argument).getValue() != null
                    && isDefinitelyInconvertibleReferenceCast(declaredParameterType,
                    ((ConstantValue) argument).getValue().getClass())) {
                return true;
            }
            // javac checks the argument against the receiver's declared parameterization
            // (eg ArrayList<IOException>.add(IOException)), but the argument is rendered
            // against the method's own model, which can still accept it (eg add(E) with E
            // unresolved), so neither the call nor an argument cast would be emitted.
            if (modelParameterTypes.length == declaredParameterTypes.length
                    && (GenericClassUtils.isAssignable(modelParameterTypes[i], actualParameterType)
                    || (isUnresolvedTypeVariableOrWildcard(modelParameterTypes[i])
                    && safeErasure(modelParameterTypes[i]).isAssignableFrom(
                    ClassUtils.primitiveToWrapper(safeErasure(actualParameterType)))))) {
                return true;
            }
        }

        return false;
    }

    /**
     * The type javac sees for the receiver. A constructor's variable is declared with the
     * constructor's owner type, which can be more specific than the variable's own type
     * (eg ArrayList&lt;Foo&gt; declared while the variable is a raw or differently
     * parameterized list).
     */
    private Type declaredReceiverType(MethodStatement statement) {
        return declaredVariableType(statement.getTestCase(), statement.getCallee());
    }

    /** See {@link #declaredReceiverType}: the type javac sees for a variable. */
    private Type declaredVariableType(TestCase testCase, VariableReference callee) {
        if (testCase != null && callee.getStPosition() >= 0
                && callee.getStPosition() < testCase.size()) {
            Statement definition = testCase.getStatement(callee.getStPosition());
            if (definition instanceof ConstructorStatement
                    && definition.getReturnValue() == callee) {
                GenericConstructor constructor = ((ConstructorStatement) definition).getConstructor();
                Class<?> declaringClass = constructor.getConstructor().getDeclaringClass();
                if (constructor.getOwnerType() instanceof ParameterizedType
                        && !declaringClass.isMemberClass()
                        && isDeclaredTypeNameableInGeneratedTest(declaringClass)) {
                    return rawIfOutsideOfBounds(constructor.getOwnerType());
                }
            }
        }
        return callee.getType();
    }

    private Type[] resolveMethodParameterTypesForReceiver(GenericMethod method, Type receiverType) {
        if (method == null || receiverType == null) {
            return method != null ? method.getParameterTypes() : new Type[0];
        }
        try {
            GenericMethod resolvedMethod = new GenericMethod(method.getMethod(), receiverType);
            return resolvedMethod.getParameterTypes();
        } catch (RuntimeException ignored) {
            return method.getParameterTypes();
        }
    }

    private boolean isDefinitelyInconvertibleReferenceCast(Type declaredType, Type actualType) {
        Class<?> declaredClass = ClassUtils.primitiveToWrapper(safeErasure(declaredType));
        Class<?> actualClass = ClassUtils.primitiveToWrapper(safeErasure(actualType));
        if (declaredClass == null || actualClass == null) {
            return false;
        }
        if (declaredClass.isAssignableFrom(actualClass) || actualClass.isAssignableFrom(declaredClass)) {
            return false;
        }
        if (declaredClass.isInterface()) {
            return !actualClass.isInterface()
                    && Modifier.isFinal(actualClass.getModifiers())
                    && !declaredClass.isAssignableFrom(actualClass);
        }
        if (actualClass.isInterface()) {
            return !declaredClass.isInterface()
                    && Modifier.isFinal(declaredClass.getModifiers())
                    && !actualClass.isAssignableFrom(declaredClass);
        }
        return true;
    }

    /**
     * The type to declare (or cast) a method's return value as when that value's class cannot be
     * named: a hidden class inferred for a type variable (the method's own or the receiver
     * class's, as in Map&lt;K,V&gt;.get), or a private class the method returns directly. The
     * latter includes an inner class of a generic class, whose type is the parameterized
     * {@code Outer<T>.Inner} and would otherwise be written out through its owner.
     */
    private String getAccessibleGenericReturnTypeName(GenericMethod method, VariableReference retval) {
        if (method != null && retval != null) {
            Type returnType = retval.getType();
            Class<?> returnedClass = returnType instanceof Class<?> ? (Class<?>) returnType
                    : returnType instanceof ParameterizedType
                    && ((ParameterizedType) returnType).getRawType() instanceof Class<?>
                    ? (Class<?>) ((ParameterizedType) returnType).getRawType() : null;
            if (returnedClass != null && !returnedClass.isPrimitive() && !returnedClass.isArray()
                    && !isDeclaredTypeNameableInGeneratedTest(returnedClass)) {
                return getTypeName(findAccessibleGenericSupertype(returnedClass,
                        method.getMethod().getGenericReturnType()));
            }
        }
        return getClassName(retval);
    }

    private String getMethodReturnDeclarationType(GenericMethod method, VariableReference retval) {
        String renderedType = getAccessibleGenericReturnTypeName(method, retval);
        if (method == null || retval == null || retval.isVoid()) {
            return renderedType;
        }

        Type resolvedReturnType = retval.getType();
        Type declaredGenericReturn = method.getMethod().getGenericReturnType();
        if (!(resolvedReturnType instanceof ParameterizedType)
                || !method.hasTypeParameters()
                || !dependsOnGenericTypeInformation(resolvedReturnType)
                || !dependsOnGenericTypeInformation(declaredGenericReturn)) {
            return renderedType;
        }

        return getTypeName(method.getMethod().getReturnType());
    }

    /**
     * Whether a reference array argument can never be cast to the (type-variable bound) array
     * parameter type, eg a String[] passed where Arrays.asList was bound to T = Foo.
     */
    private boolean isInconvertibleArrayArgument(Type declaredParamType, Type actualParamType) {
        if (!TypeUtils.isArrayType(declaredParamType) || !TypeUtils.isArrayType(actualParamType)) {
            return false;
        }
        Class<?> declaredComponent = safeErasure(declaredParamType).getComponentType();
        Class<?> actualComponent = safeErasure(actualParamType).getComponentType();
        return declaredComponent != null && actualComponent != null
                && !declaredComponent.isPrimitive() && !actualComponent.isPrimitive()
                && isDefinitelyInconvertibleReferenceCast(declaredComponent, actualComponent)
                && !declaredComponent.isAssignableFrom(actualComponent);
    }

    private boolean requiresRawReturnCastForArrayArgument(Type[] renderedParameterTypes,
                                                          List<VariableReference> parameters) {
        if (renderedParameterTypes == null || parameters == null
                || renderedParameterTypes.length != parameters.size()) {
            return false;
        }
        for (int i = 0; i < parameters.size(); i++) {
            VariableReference argument = parameters.get(i);
            if (!isRenderedNullArgument(argument)
                    && isInconvertibleArrayArgument(renderedParameterTypes[i], argument.getType())) {
                return true;
            }
        }
        return false;
    }

    private boolean requiresReturnCastDueToRawParameterCast(boolean isGenericMethod,
                                                              Type[] renderedParameterTypes,
                                                              List<VariableReference> parameters,
                                                              GenericMethod method,
                                                              VariableReference retval) {
        if (!isGenericMethod || method == null || retval == null) {
            return false;
        }
        if (!dependsOnGenericTypeInformation(method.getMethod().getGenericReturnType())) {
            return false;
        }
        Class<?> retvalClass = retval.getVariableClass();
        if (retvalClass == null || Object.class.equals(retvalClass)) {
            return false;
        }
        if (renderedParameterTypes == null || parameters == null
                || renderedParameterTypes.length != parameters.size()) {
            return false;
        }
        for (int i = 0; i < renderedParameterTypes.length; i++) {
            Type declaredParamType = renderedParameterTypes[i];
            if (declaredParamType instanceof WildcardType) {
                continue;
            }
            VariableReference argRef = parameters.get(i);
            String argName = getVariableName(argRef);
            if ("null".equals(argName) || isNullInitializedVariable(argRef)) {
                continue;
            }
            Type actualParamType = argRef.getType();
            if (!declaredParamType.equals(actualParamType)
                    && requiresRawCastForInconvertibleParameterizedTypes(declaredParamType, actualParamType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A zero-argument static generic call is left uncast because a cast gives javac no target
     * type to infer from. When the variable's type arguments cannot be matched by inference at
     * all (Comparator&lt;Object&gt; for Comparator&lt;Entry&lt;K,V&gt;&gt;), only a raw cast, which
     * is unchecked but legal, makes the assignment compile.
     */
    private boolean uninferableStaticGenericReturn(GenericMethod method, VariableReference retval) {
        Type declaredReturn = method.getMethod().getGenericReturnType();
        Type variableType = retval.getType();
        if (!(declaredReturn instanceof ParameterizedType)
                || !(variableType instanceof ParameterizedType)) {
            return false;
        }
        Type[] returnArguments = ((ParameterizedType) declaredReturn).getActualTypeArguments();
        Type[] variableArguments = ((ParameterizedType) variableType).getActualTypeArguments();
        if (!((ParameterizedType) declaredReturn).getRawType()
                .equals(((ParameterizedType) variableType).getRawType())
                || returnArguments.length != variableArguments.length) {
            return false;
        }
        for (int i = 0; i < returnArguments.length; i++) {
            if (returnArguments[i] instanceof ParameterizedType
                    && !(variableArguments[i] instanceof ParameterizedType
                    && ((ParameterizedType) returnArguments[i]).getRawType().equals(
                            ((ParameterizedType) variableArguments[i]).getRawType()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The receiver is declared with the constructor's owner type (ArrayList&lt;Object&gt;) while
     * the call is modelled on the variable's own parameterization (ArrayList&lt;Foo&gt;), so a
     * generic return value has the declared parameterization for javac, not the modelled one.
     * Only a raw cast, which is unchecked but legal, bridges the two.
     */
    private boolean returnDependsOnRedeclaredReceiver(MethodStatement statement,
                                                      GenericMethod method,
                                                      VariableReference retval) {
        if (statement.getCallee() == null || !(retval.getType() instanceof ParameterizedType)
                || !dependsOnGenericTypeInformation(method.getMethod().getGenericReturnType())) {
            return false;
        }
        Type declared = declaredReceiverType(statement);
        return declared instanceof ParameterizedType
                && !declared.equals(statement.getCallee().getType());
    }

    /**
     * A type argument that implements only the raw form of a parameterized bound (UURI, which
     * is a raw Comparable, for E extends Comparable&lt;E&gt;) is not within the bound for javac,
     * although the search treats the raw interface as satisfying it. Such a type is declared
     * and constructed raw, as no parameterization of it compiles.
     */
    private Type rawIfOutsideOfBounds(Type type) {
        if (!(type instanceof ParameterizedType)
                || !(((ParameterizedType) type).getRawType() instanceof Class<?>)) {
            return type;
        }
        ParameterizedType parameterized = (ParameterizedType) type;
        Class<?> rawClass = (Class<?>) parameterized.getRawType();
        TypeVariable<?>[] parameters = rawClass.getTypeParameters();
        Type[] arguments = parameterized.getActualTypeArguments();
        for (int i = 0; i < parameters.length && i < arguments.length; i++) {
            if (!(arguments[i] instanceof Class<?> || arguments[i] instanceof ParameterizedType)) {
                continue;
            }
            Class<?> argumentClass = GenericTypeReflector.erase(arguments[i]);
            for (Type bound : parameters[i].getBounds()) {
                if (!(bound instanceof ParameterizedType)) {
                    continue;
                }
                Class<?> boundClass = GenericTypeReflector.erase(bound);
                if (boundClass.isAssignableFrom(argumentClass)
                        && GenericTypeReflector.getExactSuperType(arguments[i], boundClass)
                        instanceof Class<?>) {
                    return rawClass;
                }
            }
        }
        return type;
    }

    private boolean requiresReturnValueCastForRawReceiver(MethodStatement statement,
                                                          GenericMethod method,
                                                          VariableReference retval) {
        if (statement == null || method == null || retval == null || method.isStatic()) {
            return false;
        }
        VariableReference callee = statement.getCallee();
        if (callee == null) {
            return false;
        }
        if (!receiverErasesMembersOf(callee, method.getMethod().getDeclaringClass())) {
            return false;
        }
        Type declaredGenericReturn = method.getMethod().getGenericReturnType();
        if (!dependsOnGenericTypeInformation(declaredGenericReturn)) {
            return false;
        }
        Type resolvedReturnType = method.getReturnType();
        Type erasedCallsiteReturnType = method.getMethod().getReturnType();
        Class<?> retvalClass = retval.getVariableClass();
        Class<?> erasedReturnClass = method.getMethod().getReturnType();
        if (retvalClass == null || erasedReturnClass == null) {
            return false;
        }
        return retval.isAssignableFrom(resolvedReturnType)
                && !ClassUtils.isAssignable(erasedReturnClass, retvalClass, true);
    }

    /**
     * Whether javac sees the members {@code declaringClass} contributes to the receiver erased.
     * That is the case for a receiver rendered as a raw generic type, and for a receiver class
     * without type parameters that reaches a generic {@code declaringClass} through a raw
     * supertype ({@code class ProviderList extends Hashtable}): the type of every member it
     * inherits from there is erased, so {@code put} returns {@code Object}.
     */
    private boolean receiverErasesMembersOf(VariableReference receiver, Class<?> declaringClass) {
        Class<?> receiverClass = receiver.getVariableClass();
        if (receiverClass == null || declaringClass == null) {
            return false;
        }
        if (receiverClass.getTypeParameters().length > 0) {
            return getClassName(receiver).equals(getClassName(receiverClass))
                    || isReadThroughRawReflectiveCast(receiver)
                    // A constructor's variable is declared with the constructor's owner type,
                    // which is raw when an argument is outside its bounds.
                    || (!(receiver instanceof FieldReference) && !(receiver instanceof ArrayIndex)
                    && declaredVariableType(test != null ? test : receiver.getTestCase(),
                            normalizeVariableReference(receiver)) instanceof Class<?>);
        }
        if (declaringClass.getTypeParameters().length == 0
                || !declaringClass.isAssignableFrom(receiverClass)) {
            return false;
        }
        return !(GenericTypeReflector.getExactSuperType(receiverClass, declaringClass)
                instanceof ParameterizedType);
    }

    /**
     * A field whose declared type uses the declaring class's type variables reads as its erasure
     * through a receiver that erases it ({@code pair.key} is a {@code Comparable} for a raw
     * {@code FlexiblePair}); a variable or parameter of the resolved type then needs a cast back.
     */
    private boolean isFieldReadErasedByRawReceiver(Field field, VariableReference source,
                                                   Class<?> targetClass) {
        if (field == null || source == null || targetClass == null
                || Modifier.isStatic(field.getModifiers())
                || !canUseDirectFieldAccess(field, source)
                || !dependsOnGenericTypeInformation(field.getGenericType())
                || !receiverErasesMembersOf(source, field.getDeclaringClass())) {
            return false;
        }
        return !targetClass.isAnonymousClass()
                && !ClassUtils.isAssignable(field.getType(), targetClass, true);
    }

    /** {@link #isFieldReadErasedByRawReceiver} for a field reference used as a value. */
    private boolean isFieldReferenceErasedByRawReceiver(VariableReference var, Class<?> targetClass) {
        return var instanceof FieldReference
                && isFieldReadErasedByRawReceiver(((FieldReference) var).getField().getField(),
                        ((FieldReference) var).getSource(), targetClass);
    }

    /**
     * A field that is not directly accessible is read as {@code ((Raw) PrivateAccess.getVariable(..))},
     * so javac sees the raw field class whatever parameterization the variable carries.
     */
    private boolean isReadThroughRawReflectiveCast(VariableReference callee) {
        return callee instanceof FieldReference
                && !canUseDirectFieldAccess(((FieldReference) callee).getField().getField(),
                        ((FieldReference) callee).getSource());
    }

    private boolean dependsOnGenericTypeInformation(Type type) {
        if (type == null) {
            return false;
        }
        if (type instanceof TypeVariable || type instanceof WildcardType || type instanceof CaptureType) {
            return true;
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            // The raw type of a parameterized type always declares type parameters, so
            // checking it would flag every parameterized type, Box<String> included; only
            // the owner (Outer<T>.Inner<E>) and the arguments can still be unresolved.
            if (dependsOnGenericTypeInformation(parameterizedType.getOwnerType())) {
                return true;
            }
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                if (dependsOnGenericTypeInformation(argument)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof GenericArrayType) {
            return dependsOnGenericTypeInformation(((GenericArrayType) type).getGenericComponentType());
        }
        if (type instanceof Class<?>) {
            return ((Class<?>) type).getTypeParameters().length > 0;
        }
        return false;
    }

    private boolean hasMethodBySignatureName(Class<?> clazz, Method target) {
        if (clazz == null || target == null) {
            return false;
        }
        try {
            for (Method candidate : clazz.getMethods()) {
                if (!candidate.getName().equals(target.getName())) {
                    continue;
                }
                if (sameParameterTypeNames(candidate.getParameterTypes(), target.getParameterTypes())) {
                    return true;
                }
            }
            Class<?> current = clazz;
            while (current != null) {
                for (Method candidate : current.getDeclaredMethods()) {
                    if (!candidate.getName().equals(target.getName())) {
                        continue;
                    }
                    if (sameParameterTypeNames(candidate.getParameterTypes(), target.getParameterTypes())) {
                        return true;
                    }
                }
                current = current.getSuperclass();
            }
        } catch (NoClassDefFoundError e) {
            // Missing transitive dependency — cannot verify signature match
            return false;
        }
        return false;
    }

    private boolean sameParameterTypeNames(Class<?>[] left, Class<?>[] right) {
        if (left.length != right.length) {
            return false;
        }
        for (int i = 0; i < left.length; i++) {
            if (!Objects.equals(left[i].getName(), right[i].getName())) {
                return false;
            }
        }
        return true;
    }

    private boolean assertionReferencesReturnValue(Statement statement, VariableReference returnValue) {
        for (Assertion assertion : statement.getAssertions()) {
            if (assertion == null) {
                continue;
            }
            for (VariableReference referenced : assertion.getReferencedVariables()) {
                if (sameVariableDefinition(referenced, returnValue)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean assertionReferencesReturnValueInTest(VariableReference returnValue) {
        if (test == null || returnValue == null) {
            return false;
        }
        for (int i = 0; i < test.size(); i++) {
            Statement stmt = test.getStatement(i);
            if (stmt == null) {
                continue;
            }
            for (Assertion assertion : stmt.getAssertions()) {
                if (assertion == null) {
                    continue;
                }
                for (VariableReference referenced : assertion.getReferencedVariables()) {
                    if (sameVariableDefinition(referenced, returnValue)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns a catch block for an exception that can be thrown by this
     * statement. The caught exception type is the actual class of the exception
     * object passed as parameter (or one of its superclass if the type is not
     * public). This method can be overridden to inject code in the catch block.
     **/
    public String generateCatchBlock(AbstractStatement statement, Throwable exception) {
        String result = "";
        String catchVarName = chooseFreshCatchVariableName();

        Class<?> ex = getExceptionClassToUse(exception);

        // preparing the catch block
        if (!(exception instanceof RuntimeException) && !(exception instanceof Error)) {
            // This is a checked exception.
            if (statement.isDeclaredException(exception)) {
                result += " catch(" + getClassName(ex) + " " + catchVarName + ") {" + NEWLINE;
            } else {
                // A checked exception that is not declared cannot be thrown according to the JVM spec.
                // And yet, it is possible, which is probably a bug in Java.
                // See class org.apache.commons.lang3.time.FastDatePrinter:
                //     @Override
                //     public <B extends Appendable> B format(final Date date, final B buf) {
                //         final Calendar c = newCalendar();  // hard code GregorianCalendar
                //         c.setTime(date);
                //         return applyRules(c, buf);
                //     }
                // Passing in a PipeWriter will lead to an IOException.
                // As a workaround, we'll just check for Throwable
                //
                result += " catch(" + getClassName(Throwable.class) + " " + catchVarName + ") {" + NEWLINE;
            }
        } else {
            String className = getClassName(ex);
            if (className.contains("MockitoMock")) {
                className = getClassName(Throwable.class);
            }
            result += " catch(" + className + " " + catchVarName + ") {" + NEWLINE;
        }

        // adding the message of the exception
        String exceptionMessage;
        try {
            if (exception.getMessage() != null) {
                exceptionMessage = exception.getMessage().replace("*/", "*_/");
            } else {
                exceptionMessage = "no message in exception (getMessage() returned null)";
            }
        } catch (Exception exceptionThownExecutionGetMessage) {
            exceptionMessage = "no message (getMessage() has thrown an exception)";
        }

        String sourceClass = getSourceClassName(exception);

        if (sourceClass == null || isValidSource(sourceClass)) {
            /*
                do not print comments if it was a non-valid source.
                however, if source is undefined, then it should be OK
             */
            result += "    //" + NEWLINE;
            for (String msg : exceptionMessage.split("\n")) {
                result += "    // " + StringEscapeUtils.escapeJava(msg) + NEWLINE;
            }
            result += "    //" + NEWLINE;
        }

        if (sourceClass != null && isValidSource(sourceClass)
                && isExceptionToAssertThrownBy(ex)
                && shouldAssertThrowSite(sourceClass)
                && !Properties.NO_RUNTIME_DEPENDENCY) {
            /*
                do not check source if it comes from a non-runtime evosuite
                class. this could happen if source is an instrumentation done
                during search which is not applied to runtime
             */

            //from class EvoAssertions
            result += "    verifyException(\"" + sourceClass + "\", " + catchVarName + ");" + NEWLINE;
        }

        for (Assertion assertion : statement.getAssertions()) {
            if (!isInCatchTemplateAssertion(assertion)) {
                continue;
            }
            TemplateCodeAssertion template = (TemplateCodeAssertion) assertion;
            String rendered = template.render(this, catchVarName);
            for (String line : rendered.split("\\r?\\n")) {
                if (!line.isEmpty()) {
                    result += "    " + line + NEWLINE;
                }
            }
        }

        result += "}" + NEWLINE;// closing the catch block
        return result;
    }

    private String chooseFreshCatchVariableName() {
        Set<String> usedNames = new HashSet<>(declarationVariableNames.values());
        usedNames.addAll(argumentNames.values());
        usedNames.addAll(methodNames.values());
        String codeSoFar = testCode.toString();
        String candidate = "e";
        int suffix = 1;
        while (usedNames.contains(candidate) || containsIdentifierOutsideLiteralsAndComments(codeSoFar, candidate)) {
            candidate = "e" + suffix++;
        }
        return candidate;
    }

    private boolean containsIdentifierOutsideLiteralsAndComments(String code, String identifier) {
        if (code == null || code.isEmpty() || identifier == null || identifier.isEmpty()) {
            return false;
        }
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipQuotedLiteral(code, i, c);
                continue;
            }
            if (c == '/' && i + 1 < code.length()) {
                char next = code.charAt(i + 1);
                if (next == '/') {
                    i = skipLineComment(code, i);
                    continue;
                }
                if (next == '*') {
                    i = skipBlockComment(code, i);
                    continue;
                }
            }
            if (SnippetSourceSupport.startsWithIdentifier(code, i, identifier)
                    && SnippetSourceSupport.isIdentifierBoundary(code, i - 1)
                    && SnippetSourceSupport.isIdentifierBoundary(code, i + identifier.length())) {
                return true;
            }
            i++;
        }
        return false;
    }

    private int skipQuotedLiteral(String source, int start, char quote) {
        int i = start + 1;
        while (i < source.length()) {
            char current = source.charAt(i);
            i++;
            if (current == '\\' && i < source.length()) {
                i++;
                continue;
            }
            if (current == quote) {
                break;
            }
        }
        return i;
    }

    private int skipLineComment(String source, int start) {
        int i = start + 2;
        while (i < source.length()) {
            if (source.charAt(i++) == '\n') {
                break;
            }
        }
        return i;
    }

    private int skipBlockComment(String source, int start) {
        int i = start + 2;
        while (i < source.length()) {
            char current = source.charAt(i);
            i++;
            if (current == '*' && i < source.length() && source.charAt(i) == '/') {
                i++;
                break;
            }
        }
        return i;
    }

    private boolean shouldAssertThrowSite(String sourceClass) {
        if (sourceClass == null || sourceClass.isEmpty()) {
            return false;
        }
        String targetClass = Properties.TARGET_CLASS;
        if (targetClass == null || targetClass.trim().isEmpty()) {
            return true;
        }
        String targetPackage = "";
        int idx = targetClass.lastIndexOf('.');
        if (idx >= 0) {
            targetPackage = targetClass.substring(0, idx);
        }
        String sourcePackage = "";
        int sourceIdx = sourceClass.lastIndexOf('.');
        if (sourceIdx >= 0) {
            sourcePackage = sourceClass.substring(0, sourceIdx);
        }
        // Keep strict throw-site checks for SUT/package-owned failures only.
        // Third-party/JDK throw sites (eg Swing/GUI init internals) are unstable
        // across classloaders/instrumentation and should be matched by type only.
        return targetPackage.equals(sourcePackage);
    }

    private String getSourceClassName(Throwable exception) {
        StackTraceElement[] trace = exception.getStackTrace();
        if (trace == null || trace.length == 0 || trace[0] == null) {
            return null;
        }
        return trace[0].getClassName();
    }

    private boolean isValidSource(String sourceClass) {
        return (!sourceClass.startsWith(PackageInfo.getEvoSuitePackage() + ".")
                || sourceClass.startsWith(PackageInfo.getEvoSuitePackage() + ".runtime."))
                && !sourceClass.equals(URLClassLoader.class.getName())
                // Classloaders may differ, e.g. when running with ant
                && !sourceClass.startsWith(RegExp.class.getPackage().getName())
                && !sourceClass.startsWith("java.lang.System")
                && !sourceClass.startsWith("java.lang.String")
                && !sourceClass.startsWith("java.lang.Class")
                && !sourceClass.startsWith("sun.")
                && !sourceClass.startsWith("com.sun.")
                && !sourceClass.startsWith("jdk.internal.")
                && !sourceClass.startsWith("<evosuite>");
    }

    private final List<Class<?>> invalidExceptions = Arrays.asList(new Class<?>[]{
            StackOverflowError.class, // Might be thrown at different places
            AssertionError.class}     // Depends whether assertions are enabled or not
    );

    private boolean isExceptionToAssertThrownBy(Class<?> exceptionClass) {
        return !invalidExceptions.contains(exceptionClass);
    }

    private Class<?> getExceptionClassToUse(Throwable exception) {
        /*
            we can only catch a public class.
            for "readability" of tests, it shouldn't be a mock one either
          */
        Class<?> ex = exception.getClass();
        while (!Modifier.isPublic(ex.getModifiers())
                || EvoSuiteMock.class.isAssignableFrom(ex)
                // A class generated by Mockito (a mocked exception thrown by a mocked call)
                // exists only at run time and has no name a test can write.
                || ex.getName().contains("$MockitoMock$")
                || ex.getCanonicalName().startsWith("com.sun.")
                || TestUsageChecker.isInUnexportedPackage(ex)) {
            ex = ex.getSuperclass();
        }
        return ex;
    }

    private String getSimpleTypeName(Type type) {
        String typeName = getTypeName(type);
        if (type instanceof ParameterizedType) {
            String qualifier = getParameterizedOwnerQualifier((ParameterizedType) type);
            if (!qualifier.isEmpty() && typeName.startsWith(qualifier)) {
                return typeName.substring(qualifier.length());
            }
        }
        int dotIndex = typeName.lastIndexOf(".");
        if (dotIndex >= 0 && (dotIndex + 1) < typeName.length()) {
            typeName = typeName.substring(dotIndex + 1);
        }

        return typeName;
    }

    /**
     * The receiver expression of {@code outer.new Inner(...)}. javac resolves the simple name
     * {@code Inner} as a member type of the <em>static type</em> of {@code outer}, so when a
     * subclass declares a member class of the same name as the inherited one (for example
     * {@code CoverTree.MyHeap} hides {@code NearestNeighbourSearch.MyHeap}), the expression
     * would create the subclass's member instead. Casting the receiver to the class that really
     * declares {@code inner} selects the right one.
     */
    private String getInnerClassCreationReceiver(Class<?> inner, VariableReference outer) {
        String outerName = getVariableName(outer);
        Class<?> enclosing = inner.getEnclosingClass();
        Class<?> outerClass = outer.getVariableClass();
        if (enclosing == null || outerClass == null || outerClass.equals(enclosing)
                || !enclosing.isAssignableFrom(outerClass)) {
            return outerName;
        }
        try {
            if (findMemberClass(outerClass, inner.getSimpleName()) == inner) {
                return outerName;
            }
        } catch (LinkageError e) {
            return outerName;
        }
        return "((" + getClassName(enclosing) + ")" + outerName + ")";
    }

    /**
     * Finds the member class javac resolves for {@code simpleName} in {@code type}: own, then inherited.
     */
    private static Class<?> findMemberClass(Class<?> type, String simpleName) {
        if (type == null) {
            return null;
        }
        for (Class<?> member : type.getDeclaredClasses()) {
            if (member.getSimpleName().equals(simpleName)) {
                return member;
            }
        }
        Class<?> found = findMemberClass(type.getSuperclass(), simpleName);
        if (found != null) {
            return found;
        }
        for (Class<?> iface : type.getInterfaces()) {
            found = findMemberClass(iface, simpleName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private String getConstructorInvocationTypeName(Type type) {
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            for (Type argumentType : parameterizedType.getActualTypeArguments()) {
                if (isUnresolvedTypeVariableOrWildcard(argumentType)
                        || containsUnnameableType(argumentType)) {
                    return getClassName((Class<?>) parameterizedType.getRawType()) + "<>";
                }
            }
        }
        return getTypeName(type);
    }

    /**
     * The type javac gives {@code outer.new Inner(...)} is {@code Outer<X>.Inner}, where X comes
     * from the declared type of the enclosing instance, not from the constructor model's own
     * owner type (which can carry a different instantiation of the outer's type parameters).
     * Declaring the variable with the model's owner type then fails to compile, so only an
     * owner type that agrees with the enclosing instance keeps its qualifier.
     */
    private Type withEnclosingInstanceOwner(Type ownerType, VariableReference enclosingInstance) {
        Class<?> rawInner = safeErasure(ownerType);
        if (rawInner == null || rawInner.getDeclaringClass() == null
                || !(ownerType instanceof ParameterizedType)
                || !(((ParameterizedType) ownerType).getOwnerType() instanceof ParameterizedType)) {
            return ownerType;
        }
        // The enclosing instance may only inherit the outer class (class Derived extends
        // Outer<String>), so look at its supertype that is the inner class's declaring class.
        Type enclosingOuter = null;
        try {
            enclosingOuter = GenericUtils.getExactSuperType(
                    enclosingInstance.getType(), rawInner.getDeclaringClass());
        } catch (RuntimeException e) {
            // unresolvable: fall through to the raw declaration
        }
        if (enclosingOuter instanceof ParameterizedType
                && enclosingOuter.equals(((ParameterizedType) ownerType).getOwnerType())) {
            return ownerType;
        }
        // The model's instantiation of the outer disagrees with, or cannot be reconciled with,
        // the enclosing instance's type; later calls are chosen against the model, so a
        // qualified declaration would only move the compile error. Stay raw.
        return rawInner;
    }

    private String getSimpleConstructorInvocationTypeName(Type type) {
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            for (Type argumentType : parameterizedType.getActualTypeArguments()) {
                if (isUnresolvedTypeVariableOrWildcard(argumentType)
                        || containsUnnameableType(argumentType)) {
                    return getSimpleTypeName(parameterizedType.getRawType()) + "<>";
                }
            }
        }
        return getSimpleTypeName(type);
    }

    /** True when a later statement of the test assigns to the local variable this one defines. */
    private boolean isReassignedLocal(Statement definition) {
        if (test == null) {
            return false;
        }
        VariableReference defined = definition.getReturnValue();
        for (int i = definition.getPosition() + 1; i < test.size(); i++) {
            Statement later = test.getStatement(i);
            if (later instanceof AssignmentStatement) {
                VariableReference target = later.getReturnValue();
                if (!(target instanceof FieldReference) && !(target instanceof ArrayIndex)
                        && target.equals(defined)) {
                    return true;
                }
            }
        }
        return false;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.evosuite.testcase.TestVisitor#visitConstructorStatement(org.evosuite.testcase.ConstructorStatement)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitConstructorStatement(ConstructorStatement statement) {
        String result = "";
        GenericConstructor constructor = statement.getConstructor();
        Constructor<?> rawConstructor = constructor.getConstructor();
        VariableReference retval = statement.getReturnValue();
        String declarationVarName = getDeclarationVariableName(retval);
        Throwable exception = getException(statement);
        boolean isGenericConstructor = constructor.hasTypeParameters();
        boolean isNonStaticMemberClass = rawConstructor.getDeclaringClass().isMemberClass()
                && !constructor.isStatic()
                && !Modifier.isStatic(rawConstructor.getDeclaringClass().getModifiers());

        List<VariableReference> parameters = statement.getParameterReferences();
        if (!this.argumentNames.containsKey(retval) && VariableNameStrategyFactory.gatherInformation()) {
            final List<String> parameterNames = statement.obtainParameterNameListInOrder();
            int idx = 0;
            for (final VariableReference param : parameters) {
                this.argumentNames.put(param, parameterNames.get(idx));
                ++idx;
            }
        }
        int startPos = 0;
        if (isNonStaticMemberClass) {
            startPos = 1;
        }
        Type[] parameterTypes = constructor.getParameterTypes();
        boolean overloadedConstructorCall = constructor.isOverloaded(parameters)
                || requiresNullArgumentDisambiguation(rawConstructor, parameters, startPos);
        String parameterString = getParameterString(parameterTypes, parameters,
                isGenericConstructor,
                overloadedConstructorCall,
                startPos);

        // Use the constructor's owner type for the LHS declaration so that it
        // always matches the RHS "new OwnerType(...)".  The retval's variable class
        // can diverge from the constructor owner (e.g. java.sql.Date retval with a
        // java.util.Date constructor), and when both simple names collide the import
        // picks one while the other is fully qualified, producing uncompilable code.
        //
        // Whether the CONSTRUCTOR is source-accessible (-> direct "new Owner(...)"
        // vs. reflective fallback, see constructorAccessible below) is a different
        // question from whether the declaring TYPE itself can be named at all. A
        // private constructor on an otherwise perfectly nameable public class is a
        // common idiom (factory-method-only construction): the constructor needs
        // reflection, but the type name is entirely fine to declare and cast to.
        // Only when the type itself is not nameable (see
        // isDeclaredTypeNameableInGeneratedTest) does the declared/cast type need to
        // fall back to the nearest accessible supertype instead.
        boolean constructorAccessible = isSourceAccessibleFromGeneratedTest(rawConstructor);
        Type constructorDeclarationType = isNonStaticMemberClass
                ? withEnclosingInstanceOwner(constructor.getOwnerType(), parameters.get(0))
                : rawIfOutsideOfBounds(constructor.getOwnerType());
        if (isNonStaticMemberClass
                && constructorDeclarationType.equals(constructor.getOwnerType())
                && retval.getVariableClass() == rawConstructor.getDeclaringClass()
                && (retval.getType() instanceof Class<?>
                || retval.getGenericClass().hasWildcardOrTypeVariables())
                && retval.getGenericClass().isAssignableFrom(constructorDeclarationType)) {
            // A mutation can bind the constructor to Outer<String>.Inner while retaining a
            // raw or wildcard result variable. Narrowing that declaration changes the types
            // accepted by later calls, such as a raw inner.set(Integer).
            constructorDeclarationType = retval.getType();
        }
        String constructorTypeName = isDeclaredTypeNameableInGeneratedTest(rawConstructor.getDeclaringClass())
                ? getDeclarationTypeName(constructorDeclarationType)
                : getTypeName(findAccessibleRenderableSupertype(rawConstructor.getDeclaringClass()));
        if (!isNonStaticMemberClass && isReassignedLocal(statement)
                && rawConstructor.getDeclaringClass() != retval.getVariableClass()
                && retval.getVariableClass().isAssignableFrom(rawConstructor.getDeclaringClass())
                && isDeclaredTypeNameableInGeneratedTest(retval.getVariableClass())) {
            // The variable is written again later, with a value of the variable's own type, not
            // of the constructor's: java.io.File built by the MockFile constructor, then given a
            // de.schlichtherle.io.File. Declared as the narrower constructor owner it cannot take
            // that value.
            constructorTypeName = getDeclarationTypeName(retval.getType());
        }

        // The declaration prefix ("Type ") is prepended at the point where the
        // retval variable is first introduced. In the try-catch path the retval
        // is pre-declared with "Type var = null;" above the try block, so later
        // emissions inside the try body are plain assignments (prefix = "").
        // In the non-try path, the prefix must attach to whichever line actually
        // introduces the retval: the direct "new" call for accessible ctors, or
        // the final reflective newInstance() assignment for the fallback path.
        String declarationPrefix;
        if (shouldUseTryCatch(exception, statement.isDeclaredException(exception))) {
            String className = constructorTypeName;

            // FIXXME: Workaround for primitives:
            // But really, this can't really add any coverage, so we shouldn't be printing this in the first place!
            if (retval.isPrimitive()) {
                className = retval.getGenericClass().getUnboxedType().getSimpleName();
            }

            result = className + " " + declarationVarName + " = null;" + NEWLINE;
            result += "try {" + NEWLINE + "    ";
            declarationPrefix = "";
        } else {
            declarationPrefix = constructorTypeName + " ";
        }

        if (constructorAccessible) {
            if (isNonStaticMemberClass) {
                result += declarationPrefix + declarationVarName + " = "
                        + getInnerClassCreationReceiver(rawConstructor.getDeclaringClass(),
                                parameters.get(0))
                        + ".new "
                        + getSimpleConstructorInvocationTypeName(constructor.getOwnerType()) + "("
                        + parameterString + ");";
            } else {
                result += declarationPrefix + declarationVarName + " = new "
                        + getConstructorInvocationTypeName(
                                rawIfOutsideOfBounds(constructor.getOwnerType()))
                        + "(" + parameterString + ");";
            }
        } else {
            // Fallback for private/protected/package-private constructors that are not
            // source-accessible from the generated test package.
            String constructorVarName = "constructor" + statement.getPosition();
            String classExpression = getClassLiteralExpression(rawConstructor.getDeclaringClass());
            result += "java.lang.reflect.Constructor<?> " + constructorVarName + " = "
                    + classExpression + ".getDeclaredConstructor(";
            Class<?>[] rawParameterTypes = rawConstructor.getParameterTypes();
            for (int i = 0; i < rawParameterTypes.length; i++) {
                if (i > 0) {
                    result += ", ";
                }
                result += getClassLiteralExpression(rawParameterTypes[i]);
            }
            result += ");" + NEWLINE;
            result += constructorVarName + ".setAccessible(true);" + NEWLINE;
            String reflectiveParameterString = getParameterString(parameterTypes, parameters,
                    isGenericConstructor, overloadedConstructorCall, 0);
            reflectiveParameterString = normalizeReflectiveVarargsArguments(
                    reflectiveParameterString, rawParameterTypes.length);
            result += declarationPrefix + declarationVarName
                    + " = (" + constructorTypeName + ") "
                    + constructorVarName + ".newInstance(" + reflectiveParameterString + ");";
        }

        if (shouldUseTryCatch(exception, statement.isDeclaredException(exception))) {
            if (assertionsEnabledForRendering()) {
                result += generateFailAssertion(statement, exception);
            }

            result += NEWLINE + "}";// end try block

            result += generateCatchBlock(statement, exception);
        }

        testCode.append(result + NEWLINE);
        addAssertions(statement);
    }

    private String normalizeReflectiveVarargsArguments(String renderedArgs, int expectedArity) {
        if (expectedArity == 1 && renderedArgs != null && "null".equals(renderedArgs.trim())) {
            // Reflective varargs calls (e.g., Constructor.newInstance(Object...)) interpret
            // bare null as a null varargs array (zero invocation args). Cast to Object so
            // reflection receives one null element.
            return "(Object) null";
        }
        return renderedArgs;
    }

    private boolean isSourceAccessibleFromGeneratedTest(Constructor<?> constructor) {
        if (constructor == null) {
            return false;
        }
        String testPackageName = getGeneratedTestPackageName();
        if (!isTypeAccessibleFromGeneratedTest(constructor.getDeclaringClass(), testPackageName)) {
            return false;
        }
        int modifiers = constructor.getModifiers();
        if (Modifier.isPublic(modifiers)) {
            return true;
        }
        if (Modifier.isPrivate(modifiers)) {
            return false;
        }
        // Without a concrete output package we cannot safely assume package-private
        // access is legal; be conservative to avoid uncompilable tests.
        if (isEmptyPackageName(testPackageName)) {
            return false;
        }
        return isSamePackage(constructor.getDeclaringClass(), testPackageName);
    }

    /**
     * Renders {@code classLiteral} as either a {@code Foo.class} literal or, when
     * that is not referenceable from the generated test, a {@code Class.forName(...)}
     * call. Shared by the plain {@code Class}-typed {@link ConstantValue} case and by
     * the reflective {@code PrivateAccess} field accessors, both of which need a
     * {@code Class} expression for a type that may be a source-private nested class
     * (see {@link org.evosuite.setup.GeneratedTestAccess#isClassLiteralReferenceableFromGeneratedTest}).
     */
    private String getClassLiteralExpression(Class<?> classLiteral) {
        if (!classLiteral.isPrimitive() && !classLiteral.isArray()
                && !isClassLiteralReferenceableFromGeneratedTest(classLiteral)) {
            // A private (or otherwise unreferenceable) class cannot be named with a
            // ".class" literal, even from the same package (e.g.
            // PrivateAccess.getVariable(HttpConnection.Base.class, ...) where Base is
            // a private nested class). Obtain it reflectively; callers of this method
            // only ever feed a Class into reflective PrivateAccess calls, and the
            // enclosing test method already declares throws Throwable.
            return "Class.forName(\"" + classLiteral.getName() + "\")";
        }
        return getClassName(classLiteral) + ".class";
    }

    private boolean shouldUseTryCatch(Throwable t, boolean isDeclared) {
        return t != null
                && !(t instanceof OutOfMemoryError)
                && !TooManyResourcesException.isOrWraps(t)
                && !isEnvironmentSpecificError(t)
                && !isEvoReflectionHelperError(t)
                && !test.isFailing()
                && (Properties.CATCH_UNDECLARED_EXCEPTIONS || isDeclared);
    }

    /**
     * Errors caused by classloader/initialization issues are environment-specific
     * and not reproducible across classloader instances. They should not be wrapped
     * in try/catch as "expected behavior".
     */
    private static boolean isEnvironmentSpecificError(Throwable t) {
        return t instanceof NoClassDefFoundError
                || t instanceof ExceptionInInitializerError;
    }

    /**
     * Exceptions coming from EvoSuite's own reflective helpers (e.g., PrivateAccess)
     * are often classloader/state dependent and can differ between snippet execution
     * and JUnit re-run. Do not encode them as expected behavior in generated tests.
     */
    private static boolean isEvoReflectionHelperError(Throwable t) {
        if (t == null) {
            return false;
        }
        if (!(t instanceof IllegalArgumentException
                || t instanceof IllegalAccessException
                || t instanceof NullPointerException)) {
            return false;
        }
        for (StackTraceElement ste : t.getStackTrace()) {
            String cn = ste.getClassName();
            if ("org.evosuite.runtime.PrivateAccess".equals(cn)
                    || "org.evosuite.runtime.Reflection".equals(cn)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTransientGuiEnvironmentError(Throwable t) {
        if (t == null) {
            return false;
        }

        if (!(t instanceof NullPointerException
                || t instanceof IllegalStateException
                || t instanceof NoClassDefFoundError
                || t instanceof ExceptionInInitializerError
                || t instanceof UnsatisfiedLinkError
                || t instanceof InternalError
                || t instanceof java.awt.AWTError)) {
            return false;
        }

        for (Throwable current = t; current != null; current = current.getCause()) {
            for (StackTraceElement ste : current.getStackTrace()) {
                String cn = ste.getClassName();
                if (cn == null) {
                    continue;
                }
                if (cn.startsWith("java.awt.")
                        || cn.startsWith("javax.swing.")
                        || cn.startsWith("sun.awt.")
                        || cn.startsWith("sun.swing.")
                        || cn.startsWith("sun.java2d.")
                        || cn.startsWith("com.apple.laf.")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Generates a fail assertion for being inserted after a statement
     * generating an exception. Parameter "statement" is not used in the default
     * implementation but may be used in future extensions.
     *
     * @param statement the statement generating the exception.
     * @param exception the exception generated.
     * @return the generated fail assertion as a String.
     **/
    public String generateFailAssertion(AbstractStatement statement, Throwable exception) {
        Class<?> ex = getExceptionClassToUse(exception);

        // boolean isExpected = getDeclaredExceptions().contains(ex);
        // if (isExpected)
        if (isHeadlessExceptionType(ex)
                || isEvoReflectionHelperError(exception)
                || isTransientGuiEnvironmentError(exception)) {
            return "";
        }

        String stmt = "fail(\"Expecting exception: " + getClassName(ex) + "\");" + NEWLINE;

        if (isTestUnstable()) {
            /*
             * if the current test is unstable, then comment out all of its assertions.
             */
            stmt = "// " + stmt + getUnstableTestComment();
        }

        return NEWLINE + "    " + stmt;
    }

    private boolean isHeadlessExceptionType(Class<?> ex) {
        return ex != null && "java.awt.HeadlessException".equals(ex.getName());
    }

    /*
     * (non-Javadoc)
     *
     * @see org.evosuite.testcase.TestVisitor#visitArrayStatement(org.evosuite.testcase.ArrayStatement)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitArrayStatement(ArrayStatement statement) {
        VariableReference retval = statement.getReturnValue();
        String declarationVarName = getDeclarationVariableName(retval);
        List<Integer> lengths = statement.getLengths();

        String type = getClassName(retval);
        String multiDimensions = "";
        if (lengths.size() == 1) {
            type = type.replaceFirst("\\[\\]", "");
            multiDimensions = "[" + lengths.get(0) + "]";
            while (type.contains("[]")) {
                multiDimensions += "[]";
                type = type.replaceFirst("\\[\\]", "");
            }
        } else {
            type = type.replaceAll("\\[\\]", "");
            for (int length : lengths) {
                multiDimensions += "[" + length + "]";
            }
        }

        if (retval.getGenericClass().isGenericArray()) {
            if (lengths.size() > 1) {
                multiDimensions = "new int[] {" + lengths.get(0);
                for (int i = 1; i < lengths.size(); i++) {
                    multiDimensions += ", " + lengths.get(i);
                }
                multiDimensions += "}";
            } else {
                multiDimensions = "" + lengths.get(0);
            }

            testCode.append(getClassName(retval) + " " + declarationVarName + " = ("
                    + getClassName(retval) + ") " + getClassName(Array.class)
                    + ".newInstance("
                    + getClassName(retval.getComponentClass()).replaceAll("\\[\\]", "")
                    + ".class, " + multiDimensions + ");" + NEWLINE);

        } else {
            testCode.append(getClassName(retval) + " " + declarationVarName + " = new "
                    + type + multiDimensions + ";" + NEWLINE);
        }
        addAssertions(statement);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.evosuite.testcase.TestVisitor#visitAssignmentStatement(org.evosuite.testcase.AssignmentStatement)
     */

    /**
     * Checks whether a value renders as a null literal.
     */
    private boolean isNullLiteralValue(VariableReference value) {
        return value instanceof NullReference
                || (value instanceof ConstantValue && ((ConstantValue) value).getValue() == null);
    }

    /**
     * Cast prefix for a reference assignment {@code retval = value}. Normally
     * {@code "(TargetType) "}, but when the target is a parameterized type
     * {@code G<X>} and the value's static type is a subtype of the raw {@code G}
     * with a <em>different concrete</em> parameterization, a direct
     * {@code (G<X>) value} cast is generic-inconvertible and javac rejects it
     * (e.g. assigning a {@code DocumentDeserializer}, i.e.
     * {@code JsonDeserializer<Document>}, into a {@code JsonDeserializer<Object>}
     * field). The generic is then erased through an intermediate raw cast,
     * {@code "(G<X>)(G) "}, which is unchecked but legal.
     */
    private String getReferenceAssignmentCast(VariableReference retval, VariableReference value) {
        String target = getClassName(retval);
        if (parameterizedCastIsInconvertible(retval.getType(), value)) {
            Class<?> rawTarget = (Class<?>) ((ParameterizedType) retval.getType()).getRawType();
            return "(" + target + ")(" + getClassName(rawTarget) + ") ";
        }
        return "(" + target + ") ";
    }

    private boolean parameterizedCastIsInconvertible(Type targetType, VariableReference value) {
        if (!(targetType instanceof ParameterizedType) || value == null) {
            return false;
        }
        ParameterizedType parameterizedTarget = (ParameterizedType) targetType;
        if (!(parameterizedTarget.getRawType() instanceof Class<?>)) {
            return false;
        }
        Class<?> rawTarget = (Class<?>) parameterizedTarget.getRawType();
        Class<?> valueClass = value.getVariableClass();
        if (valueClass == null || !rawTarget.isAssignableFrom(valueClass)) {
            return false;
        }
        Type valueAsTarget;
        try {
            valueAsTarget = GenericUtils.getExactSuperType(value.getType(), rawTarget);
        } catch (Throwable ignored) {
            return false;
        }
        if (!(valueAsTarget instanceof ParameterizedType)) {
            // Raw or unknown parameterization: a plain (G<X>) cast is not provably
            // inconvertible, so leave the normal cast untouched.
            return false;
        }
        Type[] targetArgs = parameterizedTarget.getActualTypeArguments();
        Type[] valueArgs = ((ParameterizedType) valueAsTarget).getActualTypeArguments();
        for (int i = 0; i < targetArgs.length && i < valueArgs.length; i++) {
            // Only concrete, provably-different type arguments make the cast
            // inconvertible; wildcards/type variables on either side are convertible.
            if (targetArgs[i] instanceof Class<?> && valueArgs[i] instanceof Class<?>
                    && !targetArgs[i].equals(valueArgs[i])) {
                return true;
            }
            // A concrete class against a wildcard bounded by a parameterized type: HashMap's
            // Map<? extends K, ? extends V> copy constructor with V = List<String> does not take
            // a Map<String, Sequence> whose Sequence extends a raw ArrayList, because a raw
            // supertype is only unchecked-convertible at the top level, not as a type argument.
            if (targetArgs[i] instanceof WildcardType && valueArgs[i] instanceof Class<?>
                    && wildcardBoundRejectsClass((WildcardType) targetArgs[i], (Class<?>) valueArgs[i])) {
                return true;
            }
            // A concrete class against a parameterized type (eg Object against Map<?, ?>,
            // as in MapSerializer -> JsonSerializer<Map<?, ?>> cast to JsonSerializer<Object>)
            // is just as invariant-distinct: javac rejects the direct cast.
            if ((targetArgs[i] instanceof Class<?> && valueArgs[i] instanceof ParameterizedType)
                    || (targetArgs[i] instanceof ParameterizedType && valueArgs[i] instanceof Class<?>
                    && !valueArgs[i].equals(((ParameterizedType) targetArgs[i]).getRawType()))) {
                return true;
            }
            // An unresolved type variable in the value's type (eg the constructor's E leaking
            // into LinkedHashMap<E, Boolean>) is declared as "?". A G<?> variable cannot be
            // passed, nor cast with a plain cast skipped, where G<String> is expected, even
            // though erasure-based assignability accepts it.
            if (valueArgs[i] instanceof TypeVariable && requiresExactTypeArgument(targetArgs[i])) {
                return true;
            }
        }
        return false;
    }

    private boolean wildcardBoundRejectsClass(WildcardType wildcard, Class<?> valueArgument) {
        Type[] upperBounds = wildcard.getUpperBounds();
        if (wildcard.getLowerBounds().length > 0 || upperBounds.length != 1
                || !(upperBounds[0] instanceof ParameterizedType)) {
            return false;
        }
        ParameterizedType bound = (ParameterizedType) upperBounds[0];
        if (!(bound.getRawType() instanceof Class<?>)
                || !((Class<?>) bound.getRawType()).isAssignableFrom(valueArgument)) {
            return false;
        }
        Type valueAsBound;
        try {
            valueAsBound = GenericUtils.getExactSuperType(valueArgument, (Class<?>) bound.getRawType());
        } catch (Throwable ignored) {
            return false;
        }
        if (!(valueAsBound instanceof ParameterizedType)) {
            return true;
        }
        Type[] boundArgs = bound.getActualTypeArguments();
        Type[] valueArgs = ((ParameterizedType) valueAsBound).getActualTypeArguments();
        for (int i = 0; i < boundArgs.length && i < valueArgs.length; i++) {
            if (boundArgs[i] instanceof Class<?> && valueArgs[i] instanceof Class<?>
                    && !boundArgs[i].equals(valueArgs[i])) {
                return true;
            }
            // The supertype of a class extending a raw type keeps its own type variable
            // (List<E> for a class extending a raw ArrayList), which matches nothing concrete.
            if (valueArgs[i] instanceof TypeVariable && requiresExactTypeArgument(boundArgs[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether {@code typeArgument} is anything other than an unbounded wildcard or type variable.
     */
    private boolean requiresExactTypeArgument(Type typeArgument) {
        if (typeArgument instanceof Class<?> || typeArgument instanceof ParameterizedType
                || typeArgument instanceof GenericArrayType) {
            return true;
        }
        if (typeArgument instanceof WildcardType) {
            WildcardType wildcard = (WildcardType) typeArgument;
            if (wildcard.getLowerBounds().length > 0) {
                return true;
            }
            for (Type upper : wildcard.getUpperBounds()) {
                if (upper != null && !Object.class.equals(upper)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True when casting a value of {@code actualReturnType} to the parameterized
     * {@code declaredType} is generic-inconvertible. Both must share the same raw
     * class; the cast is inconvertible when any type-argument position is provably
     * distinct -- including a nested raw-vs-parameterized mismatch such as a
     * declared {@code Set<Class<Object>>} against an actual raw {@code Set<Class>}
     * (javac: "Set&lt;Class&gt; cannot be converted to Set&lt;Class&lt;Object&gt;&gt;"). Wildcards on
     * the actual side are convertible and never trigger this.
     */
    boolean returnCastIsInconvertible(Type declaredType, Type actualReturnType) {
        if (!(declaredType instanceof ParameterizedType)
                || !(actualReturnType instanceof ParameterizedType)) {
            return false;
        }
        ParameterizedType declared = (ParameterizedType) declaredType;
        ParameterizedType actual = (ParameterizedType) actualReturnType;
        if (declared.getRawType() != actual.getRawType()) {
            return false;
        }
        Type[] declaredArgs = declared.getActualTypeArguments();
        Type[] actualArgs = actual.getActualTypeArguments();
        for (int i = 0; i < declaredArgs.length && i < actualArgs.length; i++) {
            if (typeArgumentsProvablyDistinct(declaredArgs[i], actualArgs[i])) {
                return true;
            }
        }
        return false;
    }

    private boolean typeArgumentsProvablyDistinct(Type declared, Type actual) {
        if (declared instanceof Class<?> && actual instanceof Class<?>) {
            return !declared.equals(actual);
        }
        if (declared instanceof ParameterizedType) {
            Class<?> declaredRaw = (Class<?>) ((ParameterizedType) declared).getRawType();
            // Declared C<X> against the raw class C (no type arguments): distinct for
            // an invariant enclosing container (e.g. Class<Object> vs raw Class).
            if (actual instanceof Class<?> && actual.equals(declaredRaw)) {
                return true;
            }
            if (actual instanceof ParameterizedType
                    && ((ParameterizedType) actual).getRawType() == declaredRaw) {
                Type[] declaredArgs = ((ParameterizedType) declared).getActualTypeArguments();
                Type[] actualArgs = ((ParameterizedType) actual).getActualTypeArguments();
                for (int i = 0; i < declaredArgs.length && i < actualArgs.length; i++) {
                    if (typeArgumentsProvablyDistinct(declaredArgs[i], actualArgs[i])) {
                        return true;
                    }
                }
            }
        }
        // Wildcards / type variables on either side are convertible: not distinct.
        return false;
    }

    @Override
    public void visitAssignmentStatement(AssignmentStatement statement) {
        String cast = "";
        VariableReference retval = statement.getReturnValue();
        // Resolve once and reuse the same registry for both the naming and the
        // declaration decision below. getDeclarationVariableName mints a name AND
        // registers it as a side effect, so whether the normalized reference was
        // already a key in declarationVariableNames must be captured before that
        // call runs, not re-derived afterwards from something unrelated (e.g.
        // retval.getStPosition()) that can disagree with what actually got written.
        // Field and array-element targets are never declared locals: they must not touch
        // the registry at all (a second write to the same target would otherwise mint a
        // fresh suffixed name such as "obj.field_1"). They print their access expression.
        boolean isLocalTarget = !(retval instanceof FieldReference) && !(retval instanceof ArrayIndex);
        boolean alreadyNamed = false;
        String declarationVarName;
        if (isLocalTarget) {
            alreadyNamed = declarationVariableNames.containsKey(normalizeVariableReference(retval));
            declarationVarName = getDeclarationVariableName(retval);
        } else if (retval instanceof FieldReference) {
            declarationVarName = getFieldReferenceAccess((FieldReference) retval);
        } else {
            declarationVarName = getVariableName(retval);
        }
        VariableReference parameter = statement.getValue();

        if (isLocalTarget && alreadyNamed
                && declarationVarName.equals(getVariableName(parameter))) {
            // "x = x" assigns a declared local to itself: it does nothing and is not worth a
            // statement. Only reached when x was declared by an earlier statement.
            return;
        }

        if (isNullLiteralValue(parameter) && !retval.isPrimitive()) {
            // null converts to every reference type, so a cast can only do harm: a target whose
            // recorded type is wider than the field's declared type (Object for a CommonTree
            // field) turns a legal "x.tree = null" into "x.tree = (Object) null".
            cast = "";
        } else if (!retval.getVariableClass().equals(parameter.getVariableClass())) {
            if (retval.isWrapperType() && parameter.isPrimitive()) {
                cast = "(" + getTypeName(retval.getType()) + ") ";
                if (!ClassUtils.primitiveToWrapper(parameter.getVariableClass()).equals(retval.getVariableClass())) {
                    cast += "(" + ClassUtils.wrapperToPrimitive(retval.getVariableClass()) + ")";
                }

            } else if (retval.isPrimitive()
                    && parameter.isWrapperType()) {
                cast = "(" + getTypeName(retval.getType()) + ") ";
                if (!ClassUtils.primitiveToWrapper(retval.getVariableClass()).equals(parameter.getVariableClass())) {
                    cast += "(" + ClassUtils.wrapperToPrimitive(parameter.getVariableClass()) + ")";
                }
            } else if (retval.isWrapperType()
                    && parameter.isWrapperType()) {
                cast = "(" + getTypeName(retval.getType()) + ") ";
                // Unbox first to make cast work
                if (!ClassUtils.primitiveToWrapper(parameter.getVariableClass()).equals(retval.getVariableClass())) {
                    cast += "(" + ClassUtils.wrapperToPrimitive(retval.getVariableClass()) + ")";
                }
            } else {
                cast = getReferenceAssignmentCast(retval, parameter);
            }
        } else if (parameterizedCastIsInconvertible(retval.getType(), parameter)) {
            // Same raw class, provably different type arguments (a Vector<Object> variable written
            // into a Vector<Character> field): the assignment is rejected, and so is a plain cast.
            cast = getReferenceAssignmentCast(retval, parameter);
        } else if (requiresRawClassCast(retval.getType(), parameter.getType())) {
            // Both sides erase to the same raw class (java.lang.Class) -- which is why the
            // block above was skipped -- but their generic Class<...> type arguments differ
            // (eg a Class<Raw> literal assigned into a Class<Parameterized> field).
            // GenericClassUtils' documented raw-literal leniency accepts this at
            // the assignability level, but javac rejects the direct assignment. requiresRawClassCast
            // is the single predicate for this; a raw (Class) cast is the fix for a direct
            // assignment such as a field write, same as at a method-call argument.
            cast = "(" + getTypeName(Class.class) + ") ";
        }

        if (retval instanceof FieldReference) {
            FieldReference fieldReference = (FieldReference) retval;
            Field field = fieldReference.getField().getField();
            VariableReference source = fieldReference.getSource();
            // Finality, unlike visibility, is never something a cast or a reflective
            // PrivateAccess write can legally work around: a direct write is a compile
            // error, and a reflective write would only "work" because instrumentation
            // happened to strip ACC_FINAL for the bytecode-level static-reset
            // machinery - making a generated test silently depend on the agent being
            // present. So this check runs before, and independently of,
            // canUseDirectFieldAccess (which only ever reasons about visibility), and
            // on a final target it drops the statement instead of falling through to
            // either the direct- or the reflective-write branch below.
            if (TestClusterUtils.isFinalField(field)) {
                String targetClassName = source != null
                        ? source.getVariableClass().getName()
                        : field.getDeclaringClass().getName();
                logger.error("Dropping an assignment statement that targets final field "
                                + "{} (declaring class {}) via target class {}; statement "
                                + "position {} of test {}. Direct writes to final fields are "
                                + "never legal Java, so no code is emitted for this statement.",
                        field.getName(), field.getDeclaringClass().getName(), targetClassName,
                        statement.getPosition(), test != null ? test.getID() : -1);
                return;
            }
            if (!canUseDirectFieldAccess(field, source)) {
                String valueExpression = cast + getVariableName(parameter);
                testCode.append(getReflectiveFieldWrite(field, source, valueExpression)
                        + ";" + NEWLINE);
                addAssertions(statement);
                return;
            }
        }

        // If this assignment statement introduces a new variable (i.e., declarationVarName
        // was not already a registered name for the normalized retval), we need to include
        // the type declaration. For reassignments to already-named variables, field
        // references, or array indices, we omit the type since the variable was already
        // declared. This is derived from the same declarationVariableNames registry that
        // getDeclarationVariableName above just consulted/wrote, rather than from an
        // unrelated signal like retval.getStPosition(), which can disagree with it when
        // retval failed to resolve to its canonical defining-statement reference.
        boolean needsTypeDeclaration = isLocalTarget && !alreadyNamed;

        if (needsTypeDeclaration) {
            testCode.append(getClassName(retval) + " ");
        }
        testCode.append(declarationVarName);
        testCode.append(" = " + cast + getVariableName(parameter)
                + ";" + NEWLINE);
        addAssertions(statement);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.evosuite.testcase.TestVisitor#visitNullStatement(org.evosuite.testcase.NullStatement)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitNullStatement(NullStatement statement) {
        VariableReference retval = statement.getReturnValue();
        String varName = getDeclarationVariableName(retval);
        String declarationType = getNullDeclarationTypeName(retval);
        testCode.append(declarationType + " " + varName + " = null;" + NEWLINE);
    }

    private String getNullDeclarationTypeName(VariableReference retval) {
        if (retval == null) {
            return getClassName(Object.class);
        }
        Class<?> variableClass = retval.getVariableClass();
        if (variableClass == Void.class || variableClass == Void.TYPE) {
            // Void/void null references are internal sentinels for "unknown reference
            // type" during parsing. Emitting them as declaration types produces
            // uncompilable code (eg "void nullRef0 = null;").
            return getClassName(Object.class);
        }
        if (!isTypeAccessibleFromGeneratedTest(variableClass, getGeneratedTestPackageName())) {
            // Package-private (or otherwise inaccessible) types cannot legally appear
            // as a declared variable type in the generated test. Fall back to Object
            // so the rendered code compiles.
            return getClassName(Object.class);
        }
        return getClassName(retval);
    }

    /**
     * Visits a statement and appends its comment if present.
     *
     * @param statement the statement to visit.
     */
    @Override
    public void visitStatement(Statement statement) {
        if (!statement.getComment().isEmpty()) {
            String comment = statement.getComment();
            for (String line : comment.split("\n")) {
                testCode.append("// " + line + NEWLINE);
            }
        }
        appendPostProcessedComments(statement);
        super.visitStatement(statement);
        appendPostProcessedSectionBreak(statement);
    }

    private void appendPostProcessedComments(Statement statement) {
        if (test == null || statement == null) {
            return;
        }
        TestPresentationMetadata metadata = TestPresentationMetadata.get(test);
        if (metadata == null) {
            return;
        }
        int position = statement.getPosition();
        for (String comment : metadata.getCommentsAfter(position)) {
            testCode.append("// ").append(comment).append(NEWLINE);
        }
    }

    private void appendPostProcessedSectionBreak(Statement statement) {
        if (test == null || statement == null) {
            return;
        }
        TestPresentationMetadata metadata = TestPresentationMetadata.get(test);
        if (metadata == null) {
            return;
        }
        int position = statement.getPosition();
        if (metadata.hasSectionBreakAfter(position)) {
            testCode.append(NEWLINE);
        }
    }


    /**
     * Appends {@code mock(Foo.class, withSettings().stubOnly().defaultAnswer(answer));}.
     */
    private void appendStubOnlyMock(StringBuffer result, String rawClassName, String answer) {
        result.append("mock(").append(rawClassName)
                .append(".class, withSettings().stubOnly().invocationListeners(new ")
                .append(getClassName(MockInvocationLimit.class)).append("()).defaultAnswer(")
                .append(answer).append("));").append(NEWLINE);
    }
}
