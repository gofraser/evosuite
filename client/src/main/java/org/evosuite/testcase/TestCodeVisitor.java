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
import java.util.function.Function;
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

    /**
     * The cast decisions for calls, receivers and assignments; it reaches visitor state only through this host.
     */
    final CastPolicy castPolicy = new CastPolicy(new CastPolicy.Host() {
        @Override
        public String getClassName(Class<?> clazz) {
            return TestCodeVisitor.this.getClassName(clazz);
        }

        @Override
        public String getClassName(VariableReference var) {
            return TestCodeVisitor.this.getClassName(var);
        }

        @Override
        public String getTypeName(Type type) {
            return TestCodeVisitor.this.getTypeName(type);
        }

        @Override
        public String getVariableName(VariableReference var) {
            return TestCodeVisitor.this.getVariableName(var);
        }

        @Override
        public boolean canUseDirectFieldAccess(Field field, VariableReference source) {
            return TestCodeVisitor.this.canUseDirectFieldAccess(field, source);
        }

        @Override
        public VariableReference normalizeVariableReference(VariableReference var) {
            return TestCodeVisitor.this.normalizeVariableReference(var);
        }

        @Override
        public int safePosition(VariableReference variableReference) {
            return TestCodeVisitor.this.safePosition(variableReference);
        }

        @Override
        public TestCase test() {
            return test;
        }
    });

    protected VariableNameStrategy variableNameStrategy = VariableNameStrategyFactory.get();
    /**
     * Names every variable in place of the naming strategy, for an assertion rendered on its own
     * (see {@link #getStandaloneAssertionCode}); null while rendering a test.
     */
    private Function<VariableReference, String> nameResolver = null;
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
                ? CastPolicy.safeErasure(argument) : null;
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
            Class<?> boundErasure = CastPolicy.safeErasure(bound);
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
                        && CastPolicy.safeErasure(bound).equals(Object.class))) {
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
        if (nameResolver != null) {
            return nameResolver.apply(var);
        }
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
            if (castPolicy.isFieldReferenceErasedByRawReceiver(var, var.getVariableClass())) {
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
        } else if (PrimitiveAssertion.needsUnboxingCast(source, value)
                || isPrimitiveVariableDeclaredAsWrapper(source, value)) {
            // The variable class and the value disagree (eg a variable of class long whose
            // declaration is the constructor owner Integer), or the variable of class int is
            // itself declared Integer: without a cast assertEquals(int, Integer) is ambiguous
            // between (long, long) and (Object, Object).
            stmt += "assertEquals(" + NumberFormatter.getNumberString(value, this)
                    + ", (" + NumberFormatter.getBoxedClassName(value) + ")"
                    + getVariableName(source) + ");";
        } else {
            stmt += "assertEquals(" + NumberFormatter.getNumberString(value, this) + ", "
                    + getVariableName(source) + ");";
        }

        testCode.append(stmt);
    }


    /**
     * Whether a variable of a primitive class is declared with that primitive's wrapper (eg
     * {@code Integer int0 = new Integer(2)}), so that comparing it with a boxed number
     * {@code value} is ambiguous.
     */
    private boolean isPrimitiveVariableDeclaredAsWrapper(VariableReference source, Object value) {
        Class<?> sourceClass = source.getVariableClass();
        if (!sourceClass.isPrimitive() || !ClassUtils.isPrimitiveWrapper(value.getClass())
                || value instanceof Boolean || source.getTestCase() == null) {
            return false;
        }
        int position = source.getTestCase().positionOfReturnValue(source);
        if (position < 0) {
            return false;
        }
        Statement definition = source.getTestCase().getStatement(position);
        if (!(definition instanceof ConstructorStatement) && !(definition instanceof MethodStatement)) {
            return false;
        }
        return declarationTypeName(source, definition).equals(getClassName(ClassUtils.primitiveToWrapper(sourceClass)));
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
        Class<?> rawClass = null;
        if (type instanceof Class<?>) {
            rawClass = (Class<?>) type;
        } else if (type instanceof ParameterizedType && ((ParameterizedType) type).getRawType() instanceof Class<?>) {
            rawClass = (Class<?>) ((ParameterizedType) type).getRawType();
        }
        if (rawClass != null) {
            // A class the test cannot name is declared with an accessible supertype.
            return rawClass.isPrimitive() || rawClass.isArray() || isDeclaredTypeNameableInGeneratedTest(rawClass)
                    ? rawClass : findAccessibleRenderableSupertype(rawClass);
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

        visitAssertionOfItsType(assertion);
        if (assertion.hasComment()) {
            testCode.append(assertion.getComment());
        }
    }

    /**
     * The code of {@code assertion} on its own, outside a rendered test, as it appears in logs and
     * LLM prompts: rendered as in a test, but with each variable written as its own
     * {@link VariableReference#getName() name}, and without the comments a test adds.
     *
     * @param assertion the assertion to render
     * @return the assertion's code
     */
    public static String getStandaloneAssertionCode(Assertion assertion) {
        TestCodeVisitor visitor = new TestCodeVisitor();
        visitor.nameResolver = VariableReference::getName;
        visitor.visitAssertionOfItsType(assertion);
        return visitor.testCode.toString();
    }

    private void visitAssertionOfItsType(Assertion assertion) {
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
                testCode.append(declarationTypeName(retval, statement) + " "
                        + getDeclarationVariableName(retval) + " = null;" + NEWLINE);

            } else {
                String escapedString = StringUtil.getEscapedString((String) value);
                testCode.append(declarationTypeName(retval, statement) + " "
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
            builder.append(declarationTypeName(retval, statement));
            builder.append(" ");
            builder.append(getDeclarationVariableName(retval));
            builder.append(" = ");
            builder.append(getClassName(literalClass));
            builder.append(".class;");
            builder.append(NEWLINE);
            testCode.append(builder.toString());
        } else {
            testCode.append(declarationTypeName(retval, statement) + " " + getDeclarationVariableName(retval)
                    + " = " + NumberFormatter.getNumberString(value, this) + ";" + NEWLINE);
        }
        addAssertions(statement);
    }

    /**
     * The type {@code origin} declares its variable {@code retval} with. Each kind of statement
     * has its own policy:
     * <ul>
     *     <li>null: {@code Object} for a class the test cannot access, and for the {@code Void}
     *     sentinels; otherwise the variable's type;</li>
     *     <li>constructor: the constructor's owner type, as written after {@code new}; the
     *     nearest accessible supertype when the class cannot be named; the variable's own type for
     *     a local that is written again later with a value of that type;</li>
     *     <li>method: the variable's type; an accessible supertype for a class that cannot be
     *     named; the erased return type when the resolved return type still depends on the
     *     method's type parameters;</li>
     *     <li>class literal: {@code Class<Literal>}, or {@code Class<?>} for a primitive or
     *     inaccessible literal;</li>
     *     <li>string literal: {@code String};</li>
     *     <li>field read and assignment: the variable's type, or the nearest accessible
     *     supertype when the class cannot be named;</li>
     *     <li>number, enum, array and mock: the variable's type, even for a class the test cannot
     *     name, as the value expression names the class as well ({@code new Hidden[1]},
     *     {@code mock(Hidden.class)}).</li>
     * </ul>
     */
    private String declarationTypeName(VariableReference retval, Statement origin) {
        if (origin instanceof NullStatement) {
            return getNullDeclarationTypeName(retval);
        }
        if (origin instanceof ConstructorStatement) {
            return getConstructorDeclarationTypeName((ConstructorStatement) origin);
        }
        if (origin instanceof MethodStatement) {
            return castPolicy.getMethodReturnDeclarationType(((MethodStatement) origin).getMethod(), retval);
        }
        if (origin instanceof ClassPrimitiveStatement) {
            return getClassLiteralDeclarationTypeName((Class<?>) ((ClassPrimitiveStatement) origin).getValue());
        }
        if (origin instanceof StringPrimitiveStatement && ((StringPrimitiveStatement) origin).getValue() != null) {
            return ((Class<?>) retval.getType()).getSimpleName();
        }
        if (origin instanceof FieldStatement || origin instanceof AssignmentStatement) {
            Class<?> variableClass = retval.getVariableClass();
            if (!variableClass.isPrimitive() && !variableClass.isArray()
                    && !isDeclaredTypeNameableInGeneratedTest(variableClass)) {
                return getTypeName(findAccessibleRenderableSupertype(variableClass));
            }
        }
        return getClassName(retval);
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
        String localName = getDeclarationVariableName(retval);
        GenericField field = statement.getField();
        boolean needsCast = !retval.isAssignableFrom(field.getFieldType())
                || castPolicy.isFieldReadErasedByRawReceiver(field.getField(), statement.getSource(),
                        retval.getVariableClass());
        String castTypeName = declarationTypeName(retval, statement);
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
            builder.append(declarationTypeName(retval, statement));
            builder.append(" ");
            builder.append(localName);
            // A primitive field (boolean flag = PropsValues.FLAG) cannot be pre-declared as null.
            builder.append(" = ").append(retval.isPrimitive() ? retval.getDefaultValueString() : "null")
                    .append(";");
            builder.append(NEWLINE);
            builder.append("try {");
            builder.append(NEWLINE);
            builder.append("    ");
        } else {
            builder.append(declarationTypeName(retval, statement));
            builder.append(" ");
        }
        if (!field.isStatic()) {
            VariableReference source = statement.getSource();
            builder.append(localName);
            builder.append(" = ");
            builder.append(getFieldAccess(field.getField(), source, castTypeName, needsCast));
        } else {
            builder.append(localName);
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
            builder.append(" ").append(chooseFreshCatchVariableName()).append(") {}");
        }
        builder.append(NEWLINE);

        testCode.append(builder.toString());
        addAssertions(statement);
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
        String variableType = declarationTypeName(retval, st);
        // Register the declaration: a later assignment to this local must see it as already
        // declared, or it is rendered as a second declaration under a "_1" name.
        result.append(variableType).append(" ").append(getDeclarationVariableName(retval)).append(" = ");
        if (!variableType.equals(rawClassName)) {
            //this can happen in case of generics, eg
            //Foo<String> foo = (Foo<String>) mock(Foo.class);
            result.append("(").append(variableType).append(") ");
        }

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

            // A mock whose stubs lag behind its last execution is rendered with its pending calls
            // answered by null, read from a copy so that rendering leaves the test unchanged.
            // Ideally an unstubbed call would violate an assumption to avoid false positives when
            // the SUT evolves, but such a test would then fail the JUnit check.
            //when(...).thenReturn(...)
            for (FunctionalMockStatement.Stub stub : st.getStubsToRender()) {
                MethodDescriptor md = stub.getMethod();
                List<VariableReference> params = stub.getValues();

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
                            if (castPolicy.isDefinitelyInconvertibleReferenceCast(types[i], params.get(i).getType())) {
                                // The search stubbed an erased type variable with a value of
                                // another type ((String) typeEnum does not compile); doReturn
                                // takes Object, so pass the value uncast, as it was returned.
                                types[i] = Object.class;
                            }
                        }
                    }

                    parameterString = castPolicy.getParameterString(types, params, false, isOverloaded, 0);
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
                
                MethodDescriptor.StubTarget stubTarget =
                        md.getStubTarget(st.getTargetClass(), retval.getGenericClass());
                java.lang.reflect.Method originalMethod = stubTarget.getMethod();

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
                        .append(stubTarget.getMatchers()).append(");").append(NEWLINE);

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
        if ((!this.argumentNames.containsKey(retval) || retval.isVoid())
                && VariableNameStrategyFactory.gatherInformation()) {
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
        String declarationTypeName = declarationTypeName(retval, statement);
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
        Type ownerTypeForParameterRendering = statement.getCallee() != null
                ? statement.getCallee().getType()
                : method.getOwnerType();
        boolean forceRawReceiverInvocation = castPolicy.shouldForceRawReceiverInvocation(
                statement, method, parameters);
        Type[] renderedParameterTypes = forceRawReceiverInvocation
                ? method.getMethod().getParameterTypes()
                : method.getParameterTypes();
        renderedParameterTypes = castPolicy.preserveConcreteGenericParameterDeclarations(
                method, renderedParameterTypes);
        Type parameterRenderingOwnerType = forceRawReceiverInvocation
                ? method.getMethod().getDeclaringClass()
                : ownerTypeForParameterRendering;
        Class<?> overloadReceiverType = statement.getCallee() != null
                ? statement.getCallee().getVariableClass() : null;
        boolean overloadedMethodCall = method.isOverloaded(parameters, overloadReceiverType)
                || castPolicy.requiresNullArgumentDisambiguation(
                        method.getMethod(), parameters, 0, overloadReceiverType);
        String parameterString = castPolicy.getParameterString(renderedParameterTypes,
                parameters, isGenericMethod,
                overloadedMethodCall, 0, parameterRenderingOwnerType,
                method.getMethod().getGenericParameterTypes());

        String calleeStr;
        // The class the receiver is cast to; written raw, it erases a generic return type.
        Class<?> receiverCastClass = null;
        if (method.isStatic()) {
            calleeStr = getClassName(method.getMethod().getDeclaringClass());
        } else {
            CastPolicy.ReceiverCast receiverCast = castPolicy.receiverCastTarget(statement, forceRawReceiverInvocation);
            if (receiverCast == null) {
                calleeStr = getVariableName(statement.getCallee());
            } else {
                receiverCastClass = receiverCast.target;
                calleeStr = castPolicy.castReceiver(receiverCast, statement.getCallee());
            }
        }

        CastPolicy.ReturnCast returnCast = unused ? CastPolicy.ReturnCast.NONE
                : castPolicy.returnCastFor(statement, isGenericMethod,
                forceRawReceiverInvocation, receiverCastClass, renderedParameterTypes);
        String returnCastStr = returnCast.kind == CastPolicy.ReturnCast.Kind.NONE
                ? "" : "(" + returnCast.typeName + ")";
        calleeStr = returnCastStr + calleeStr;
        String invocation = "";
        if (!retval.isVoid() && !unused) {
            // An unused return value is not assigned at all.
            invocation += declarationVarName + " = ";
        }
        invocation += calleeStr + "." + method.getName() + "(" + parameterString + ");";
        result += wrapThrowing(statement, exception, invocation);

        testCode.append(result + NEWLINE);
        addAssertions(statement);
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
                if (castPolicy.isUnresolvedTypeVariableOrWildcard(argumentType)
                        || containsUnnameableType(argumentType)) {
                    return getClassName((Class<?>) parameterizedType.getRawType()) + "<>";
                }
            }
        }
        return getTypeName(type);
    }

    private String getSimpleConstructorInvocationTypeName(Type type) {
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            for (Type argumentType : parameterizedType.getActualTypeArguments()) {
                if (castPolicy.isUnresolvedTypeVariableOrWildcard(argumentType)
                        || containsUnnameableType(argumentType)) {
                    return getSimpleTypeName(parameterizedType.getRawType()) + "<>";
                }
            }
        }
        return getSimpleTypeName(type);
    }

    /** See {@link #declarationTypeName}: the type a constructor's variable is declared with. */
    private String getConstructorDeclarationTypeName(ConstructorStatement statement) {
        GenericConstructor constructor = statement.getConstructor();
        Constructor<?> rawConstructor = constructor.getConstructor();
        VariableReference retval = statement.getReturnValue();
        List<VariableReference> parameters = statement.getParameterReferences();
        boolean isNonStaticMemberClass = rawConstructor.getDeclaringClass().isMemberClass()
                && !constructor.isStatic()
                && !Modifier.isStatic(rawConstructor.getDeclaringClass().getModifiers());
        // Use the constructor's owner type for the LHS declaration so that it
        // always matches the RHS "new OwnerType(...)".  The retval's variable class
        // can diverge from the constructor owner (e.g. java.sql.Date retval with a
        // java.util.Date constructor), and when both simple names collide the import
        // picks one while the other is fully qualified, producing uncompilable code.
        //
        // Whether the CONSTRUCTOR is source-accessible (-> direct "new Owner(...)"
        // vs. reflective fallback, see visitConstructorStatement) is a different
        // question from whether the declaring TYPE itself can be named at all. A
        // private constructor on an otherwise perfectly nameable public class is a
        // common idiom (factory-method-only construction): the constructor needs
        // reflection, but the type name is entirely fine to declare and cast to.
        // Only when the type itself is not nameable (see
        // isDeclaredTypeNameableInGeneratedTest) does the declared/cast type need to
        // fall back to the nearest accessible supertype instead.
        Type constructorDeclarationType = isNonStaticMemberClass
                ? CastPolicy.withEnclosingInstanceOwner(constructor.getOwnerType(), parameters.get(0))
                : castPolicy.rawIfOutsideOfBounds(constructor.getOwnerType());
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
        return constructorTypeName;
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
                || castPolicy.requiresNullArgumentDisambiguation(rawConstructor, parameters, startPos);
        String parameterString = castPolicy.getParameterString(parameterTypes, parameters,
                isGenericConstructor,
                overloadedConstructorCall,
                startPos);

        boolean constructorAccessible = isSourceAccessibleFromGeneratedTest(rawConstructor);
        String constructorTypeName = declarationTypeName(retval, statement);

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
            declarationPrefix = "";
        } else {
            declarationPrefix = constructorTypeName + " ";
        }

        String invocation = "";
        if (constructorAccessible) {
            if (isNonStaticMemberClass) {
                invocation += declarationPrefix + declarationVarName + " = "
                        + getInnerClassCreationReceiver(rawConstructor.getDeclaringClass(),
                                parameters.get(0))
                        + ".new "
                        + getSimpleConstructorInvocationTypeName(constructor.getOwnerType()) + "("
                        + parameterString + ");";
            } else {
                invocation += declarationPrefix + declarationVarName + " = new "
                        + getConstructorInvocationTypeName(
                                castPolicy.rawIfOutsideOfBounds(constructor.getOwnerType()))
                        + "(" + parameterString + ");";
            }
        } else {
            // Fallback for private/protected/package-private constructors that are not
            // source-accessible from the generated test package.
            String constructorVarName = "constructor" + statement.getPosition();
            String classExpression = getClassLiteralExpression(rawConstructor.getDeclaringClass());
            invocation += "java.lang.reflect.Constructor<?> " + constructorVarName + " = "
                    + classExpression + ".getDeclaredConstructor(";
            Class<?>[] rawParameterTypes = rawConstructor.getParameterTypes();
            for (int i = 0; i < rawParameterTypes.length; i++) {
                if (i > 0) {
                    invocation += ", ";
                }
                invocation += getClassLiteralExpression(rawParameterTypes[i]);
            }
            invocation += ");" + NEWLINE;
            invocation += constructorVarName + ".setAccessible(true);" + NEWLINE;
            String reflectiveParameterString = castPolicy.getParameterString(parameterTypes, parameters,
                    isGenericConstructor, overloadedConstructorCall, 0);
            reflectiveParameterString = normalizeReflectiveVarargsArguments(
                    reflectiveParameterString, rawParameterTypes.length);
            invocation += declarationPrefix + declarationVarName
                    + " = (" + constructorTypeName + ") "
                    + constructorVarName + ".newInstance(" + reflectiveParameterString + ");";
        }
        result += wrapThrowing(statement, exception, invocation);

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

    /**
     * Wraps the code of a call that threw {@code exception} in a try block, followed by the
     * fail assertion and the catch block. A call whose exception is not to be caught (see
     * {@link #shouldUseTryCatch}) is returned as it is. A value assigned inside the try block
     * must be declared before it by the caller.
     */
    private String wrapThrowing(AbstractStatement statement, Throwable exception, String code) {
        if (!shouldUseTryCatch(exception, statement.isDeclaredException(exception))) {
            return code;
        }
        String result = "try {" + NEWLINE + "    " + code;
        if (assertionsEnabledForRendering()) {
            result += generateFailAssertion(statement, exception);
        }
        result += NEWLINE + "}";
        return result + generateCatchBlock(statement, exception);
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

            testCode.append(declarationTypeName(retval, statement) + " " + declarationVarName + " = ("
                    + getClassName(retval) + ") " + getClassName(Array.class)
                    + ".newInstance("
                    + getClassName(retval.getComponentClass()).replaceAll("\\[\\]", "")
                    + ".class, " + multiDimensions + ");" + NEWLINE);

        } else {
            testCode.append(declarationTypeName(retval, statement) + " " + declarationVarName + " = new "
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
                cast = castPolicy.getReferenceAssignmentCast(retval, parameter);
            }
        } else if (castPolicy.parameterizedCastIsInconvertible(retval.getType(), parameter)) {
            // Same raw class, provably different type arguments (a Vector<Object> variable written
            // into a Vector<Character> field): the assignment is rejected, and so is a plain cast.
            cast = castPolicy.getReferenceAssignmentCast(retval, parameter);
        } else if (castPolicy.requiresRawClassCast(retval.getType(), parameter.getType())) {
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
            testCode.append(declarationTypeName(retval, statement) + " ");
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
        String declarationType = declarationTypeName(retval, statement);
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
