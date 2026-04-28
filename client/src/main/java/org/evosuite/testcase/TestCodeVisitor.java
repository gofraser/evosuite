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
import org.evosuite.runtime.TooManyResourcesException;
import org.evosuite.runtime.ViolatedAssumptionAnswer;
import org.evosuite.runtime.mock.EvoSuiteMock;
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
import org.evosuite.utils.StringUtil;
import org.evosuite.utils.generic.*;

import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.util.*;

import static java.util.stream.Collectors.toCollection;

/**
 * The TestCodeVisitor is a visitor that produces a String representation of a
 * test case. This is the preferred way to produce executable code from EvoSuite
 * tests.
 *
 * @author Gordon Fraser
 */
public class TestCodeVisitor extends TestVisitor {

    protected StringBuilder testCode = new StringBuilder();

    protected static final String NEWLINE = System.getProperty("line.separator");

    protected final Map<Integer, Throwable> exceptions = new HashMap<>();

    protected TestCase test = null;

    protected final Map<Class<?>, String> classNames = new HashMap<>();

    protected VariableNameStrategy variableNameStrategy = VariableNameStrategyFactory.get();
    private boolean customVariableNameStrategy = false;
    private boolean emitAssertions = true;

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
     * Dictionaries for naming information.
     */
    protected Map<VariableReference, String> methodNames = new HashMap<>();
    protected Map<VariableReference, String> argumentNames = new HashMap<>();
    private int nullDeclarationCounter = 0;
    private final Map<VariableReference, String> declarationVariableNames = new IdentityHashMap<>();
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
        return getTypeName(var.getType());
    }

    private String getTypeName(ParameterizedType type) {
        String name = getClassName((Class<?>) type.getRawType());
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

                    name += getTypeParameterName(types[i]);
                }
                name += ">";
            }
        }
        return name;
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

                if (!first) {
                    ret += ", ";
                }
                ret += " super " + getTypeParameterName(bound);
                first = false;
            }
            for (Type bound : ((WildcardType) type).getUpperBounds()) {
                if (bound == null
                        || (!(bound instanceof CaptureType)
                        && GenericTypeReflector.erase(bound).equals(Object.class))) {
                    continue;
                }

                if (!first) {
                    ret += ", ";
                }
                ret += " extends " + getTypeParameterName(bound);
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

        GenericClass<?> c = GenericClassFactory.get(clazz);
        String name = c.getSimpleName();
        boolean useCanonicalName = false;
        if (hasSimpleNameConflict(clazz, name)) {
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

    private boolean hasSimpleNameConflict(Class<?> clazz, String simpleName) {
        String canonicalName = clazz.getCanonicalName();
        for (Map.Entry<Class<?>, String> entry : classNames.entrySet()) {
            if (!simpleName.equals(entry.getKey().getSimpleName())) {
                continue;
            }
            Class<?> existingClass = entry.getKey();
            String existingCanonical = existingClass.getCanonicalName();
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
                return getClassName((Class<?>) cval.getValue()) + ".class";
            }
            return var.getName();
        } else if (var instanceof FieldReference) {
            VariableReference source = ((FieldReference) var).getSource();
            GenericField field = ((FieldReference) var).getField();
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
            if (VariableNameStrategyFactory.gatherInformation()) {
                information.put("MethodNames", methodNames);
                information.put("ArgumentNames", argumentNames);
                variableNameStrategy.addVariableInformation(information);
            }
            return variableNameStrategy.getNameForVariable(normalized);
        }
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
        declarationVariableNames.put(normalized, name);
        return name;
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
        try {
            return variableReference.getStPosition();
        } catch (AssertionError ignored) {
            return -1;
        }
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
        } else if (value.getClass().isEnum()) {
            stmt += "assertEquals(" + NumberFormatter.getNumberString(value, this) + ", "
                    + getVariableName(source) + ");";
            // Make sure the enum is imported in the JUnit test
            getClassName(value.getClass());
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
            } else if (value.getClass().isEnum()) {
                stmt += "assertEquals(" + NumberFormatter.getNumberString(value, this)
                        + ", " + getVariableName(source) + ");";
            } else {
                stmt += "assertEquals(" + NumberFormatter.getNumberString(value, this)
                        + ", (" + NumberFormatter.getBoxedClassName(value) + ")"
                        + getVariableName(source) + ");";
            }
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
        stmt += length + ", " + getVariableName(source) + ".length);";

        testCode.append(stmt);
    }

    protected void visitContainsAssertion(ContainsAssertion assertion) {
        VariableReference containerObject = assertion.getSource();
        VariableReference containedObject = assertion.getContainedVariable();

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

        String target = "";
        if (Modifier.isStatic(field.getModifiers())) {
            target = getClassName(field.getDeclaringClass()) + "." + field.getName();
        } else {
            target = getVariableName(source) + "." + field.getName();
        }

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

        if (value == null) {
            testCode.append("assertNull(" + getVariableName(source) + "."
                    + inspector.getMethodCall() + "());");
        } else if (value.getClass().equals(Long.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", ");
            if (ClassUtils.isPrimitiveWrapper(generatedType)) {
                testCode.append("(long)");
            }
            testCode.append(getVariableName(source) + "." + inspector.getMethodCall() + "());");
        } else if (value.getClass().equals(Short.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", ");
            if (ClassUtils.isPrimitiveWrapper(generatedType)) {
                testCode.append("(short)");
            }
            testCode.append(getVariableName(source) + "." + inspector.getMethodCall() + "());");
        } else if (value.getClass().equals(Integer.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", ");
            if (ClassUtils.isPrimitiveWrapper(generatedType)) {
                testCode.append("(int)");
            }
            testCode.append(getVariableName(source) + "." + inspector.getMethodCall() + "());");
        } else if (value.getClass().equals(Byte.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", ");
            if (ClassUtils.isPrimitiveWrapper(generatedType)) {
                testCode.append("(byte)");
            }
            testCode.append(getVariableName(source) + "." + inspector.getMethodCall() + "());");
        } else if (value.getClass().equals(Float.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", ");
            testCode.append(getVariableName(source) + "." + inspector.getMethodCall()
                    + "(), " + NumberFormatter.getNumberString(Properties.FLOAT_PRECISION, this) + ");");
        } else if (value.getClass().equals(Double.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", ");
            testCode.append(getVariableName(source) + "." + inspector.getMethodCall()
                    + "(), " + NumberFormatter.getNumberString(Properties.DOUBLE_PRECISION, this) + ");");
        } else if (value.getClass().equals(Character.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", ");
            if (ClassUtils.isPrimitiveWrapper(generatedType)) {
                testCode.append("(char)");
            }
            testCode.append(getVariableName(source) + "." + inspector.getMethodCall() + "());");
        } else if (value.getClass().equals(String.class)) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", ");
            testCode.append(getVariableName(source) + "." + inspector.getMethodCall() + "());");
        } else if (value.getClass().isEnum() || value instanceof Enum) {
            testCode.append("assertEquals(" + NumberFormatter.getNumberString(value, this) + ", "
                    + getVariableName(source) + "." + inspector.getMethodCall() + "());");
            // Make sure the enum is imported in the JUnit test
            getClassName(value.getClass());

        } else if (value.getClass().equals(boolean.class) || value.getClass().equals(Boolean.class)) {
            if ((Boolean) value) {
                testCode.append("assertTrue(" + getVariableName(source) + "."
                        + inspector.getMethodCall() + "());");
            } else {
                testCode.append("assertFalse(" + getVariableName(source) + "."
                        + inspector.getMethodCall() + "());");
            }

        } else {
            testCode.append("assertEquals(" + value + ", " + getVariableName(source) + "."
                    + inspector.getMethodCall() + "());");
        }
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
        } else if (assertion instanceof CodeAssertion) {
            testCode.append(assertion.getCode());
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
            // The declaration side must stay legal for primitive class literals
            // (e.g., boolean.class cannot be represented as Class<boolean>).
            builder.append("Class<?>");
            builder.append(" ");
            builder.append(getDeclarationVariableName(retval));
            builder.append(" = ");
            builder.append(getClassName(((Class<?>) value)));
            builder.append(".class;");
            builder.append(NEWLINE);
            testCode.append(builder.toString());
        } else {
            testCode.append(getClassName(retval) + " " + getDeclarationVariableName(retval) + " = "
                    + NumberFormatter.getNumberString(value, this) + ";" + NEWLINE);
        }
        addAssertions(statement);
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

        String castStr = "";
        StringBuilder builder = new StringBuilder();

        VariableReference retval = statement.getReturnValue();
        GenericField field = statement.getField();

        if (!retval.isAssignableFrom(field.getFieldType())) {
            castStr += "(" + getClassName(retval) + ")";
        }

        if (exception != null) {
            builder.append(getClassName(retval));
            builder.append(" ");
            builder.append(getVariableName(retval));
            builder.append(" = null;");
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
            builder.append(castStr);
            builder.append(getVariableName(source));
        } else {
            builder.append(getVariableName(retval));
            builder.append(" = ");
            builder.append(castStr);
            builder.append(getClassName(field.getField().getDeclaringClass()));
        }
        builder.append(".");
        builder.append(field.getName());
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
            boolean requiresRawClassCast = isClassType(declaredParamType)
                    && isClassType(actualParamType)
                    && !declaredParamType.equals(actualParamType);
            Class<?> rawParamClass = declaredParamType instanceof WildcardType ? Object.class
                    : safeErasure(declaredParamType);
            if (rawParamClass.isPrimitive() && argumentIsNull) {
                parameterString += getPrimitiveNullCast(rawParamClass);
            } else if (isGenericMethod && !(declaredParamType instanceof WildcardType)) {
                if (!declaredParamType.equals(actualParamType) || argumentIsNull) {
                    parameterString += "(" + getCastTypeName(declaredParamType, actualParamType) + ") ";
                    if (name.contains("(short")) {
                        name = name.replace("(short)", "");
                    }
                    if (name.contains("(byte")) {
                        name = name.replace("(byte)", "");
                    }

                }
            } else if (requiresRawClassCast) {
                parameterString += "(" + getTypeName(Class.class) + ") ";
            } else if (argumentIsNull) {
                // Casting null to a reference type is only needed to disambiguate
                // overloaded signatures. Otherwise it can introduce fragile type
                // references that are unnecessary for compilation.
                if (isOverloaded) {
                    // For unresolved generic params (eg TypeVariable/Wildcard with erasure Object),
                    // an explicit cast like (Object) null can break invocation on parameterized
                    // receivers (eg List<Node>.add(E) rejects Object). Keep plain null.
                    if (!isUnresolvedTypeVariableOrWildcard(declaredParamType)
                            && !shouldSkipErasedObjectCast(declaredParamType, null, ownerType)) {
                        parameterString += "(" + getTypeName(declaredParamType) + ") ";
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
        int pos = reference.getStPosition();
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

    /**
     * For Class literals with nested generic arguments, casting to the fully parameterized
     * type may be a compile-time error (e.g. Class&lt;LinkedList&gt; to Class&lt;LinkedList&lt;Integer&gt;&gt;).
     * In those cases we fall back to a raw Class cast.
     */
    private String getCastTypeName(Type declaredParamType, Type actualParamType) {
        if (isClassType(declaredParamType) && isClassType(actualParamType)
                && !declaredParamType.equals(actualParamType)) {
            return getTypeName(Class.class);
        }
        if (requiresRawCastTypeName(declaredParamType)) {
            return getTypeName(safeErasure(declaredParamType));
        }
        return getTypeName(declaredParamType);
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

    private boolean isUnresolvedTypeVariableOrWildcard(Type type) {
        if (type instanceof TypeVariable || type instanceof WildcardType) {
            return true;
        }
        if (type instanceof CaptureType) {
            return ((CaptureType) type).getLowerBounds().length == 0;
        }
        return false;
    }

    private Class<?> safeErasure(Type type) {
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
        String code = normalizeBinaryInnerClassLiterals(statement.getSourceCode());
        String returnExpression = normalizeBinaryInnerClassLiterals(statement.getReturnExpression());
        if (statement.isParsedFromLlm()) {
            code = promoteUndeclaredNewAssignmentsToDeclarations(code, statement.getBindings());
        }
        ensureSnippetImports(code, returnExpression, statement.getBindings());

        // Substitute original LLM variable names with EvoSuite-generated names
        Map<String, VariableReference> bindings = statement.getBindings();
        if (!bindings.isEmpty()) {
            for (Map.Entry<String, VariableReference> entry : bindings.entrySet()) {
                String originalName = entry.getKey();
                String evoName = getSnippetBindingName(entry.getValue());
                if (evoName != null && !originalName.equals(evoName)) {
                    // Replace whole-word occurrences only (word boundary = not preceded/followed by
                    // a Java identifier character).
                    code = code.replaceAll(
                            "(?<![A-Za-z0-9_$])" + java.util.regex.Pattern.quote(originalName) + "(?![A-Za-z0-9_$])",
                            java.util.regex.Matcher.quoteReplacement(evoName));
                }
            }
        }

        testCode.append(code);
        if (!code.endsWith("\n")) {
            testCode.append(NEWLINE);
        }
        if (returnExpression != null && !returnExpression.trim().isEmpty()
                && !statement.getReturnValue().isVoid()) {
            String generatedName = getVariableName(statement.getReturnValue());
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

    /**
     * Converts class literals written with JVM/binary inner-class separators ('$')
     * into source-level separators ('.').
     * Example:
     * shaded.org.evosuite.runtime.System$SystemExitException.class
     * ->
     * shaded.org.evosuite.runtime.System.SystemExitException.class
     */
    private String normalizeBinaryInnerClassLiterals(String source) {
        if (source == null || source.indexOf('$') < 0 || source.indexOf(".class") < 0) {
            return source;
        }
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\b([A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)+"
                        + "\\$[A-Za-z_][A-Za-z0-9_$]*(?:\\$[A-Za-z_][A-Za-z0-9_$]*)*)\\.class\\b");
        java.util.regex.Matcher matcher = pattern.matcher(source);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String binaryTypeName = matcher.group(1);
            String sourceTypeName = binaryTypeName.replace('$', '.');
            matcher.appendReplacement(out,
                    java.util.regex.Matcher.quoteReplacement(sourceTypeName + ".class"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * LLM responses sometimes contain assignments inside preserved blocks without a prior
     * declaration (eg "s = new Scanner(...)"). When we keep those blocks as uninterpreted
     * code, generated tests do not compile. For first-time local assignments to constructor
     * expressions, rewrite to a declaration ("Scanner s = new Scanner(...)") unless the
     * variable is already known from parser bindings or earlier declarations in the same
     * snippet.
     */
    private String promoteUndeclaredNewAssignmentsToDeclarations(String code,
                                                                  Map<String, VariableReference> bindings) {
        if (code == null || code.isEmpty()) {
            return code;
        }

        Set<String> declaredNames = new HashSet<>();
        if (bindings != null) {
            declaredNames.addAll(bindings.keySet());
        }

        String[] lines = code.split("\\R", -1);
        java.util.regex.Pattern declarationPattern = java.util.regex.Pattern.compile(
                "^\\s*(?:final\\s+)?(?:[A-Za-z_$][A-Za-z0-9_$.]*(?:\\s*<[^>]*>)?(?:\\s*\\[\\s*\\])*)\\s+"
                        + "([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:[=;,)].*)?$");
        java.util.regex.Pattern newAssignmentPattern = java.util.regex.Pattern.compile(
                "^(\\s*)([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*new\\s+([A-Za-z_$][A-Za-z0-9_$.]*)\\s*\\(.*$");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            java.util.regex.Matcher declMatcher = declarationPattern.matcher(line);
            if (declMatcher.matches()) {
                declaredNames.add(declMatcher.group(1));
                continue;
            }

            java.util.regex.Matcher assignMatcher = newAssignmentPattern.matcher(line);
            if (!assignMatcher.matches()) {
                continue;
            }
            String indent = assignMatcher.group(1);
            String variable = assignMatcher.group(2);
            String typeName = assignMatcher.group(3);
            if (declaredNames.contains(variable)) {
                continue;
            }
            if (isJavaKeyword(variable)) {
                continue;
            }
            lines[i] = indent + typeName + " " + variable + line.substring(indent.length() + variable.length());
            declaredNames.add(variable);
        }
        return String.join(NEWLINE, lines);
    }

    private boolean isJavaKeyword(String token) {
        return "abstract".equals(token) || "assert".equals(token) || "boolean".equals(token)
                || "break".equals(token) || "byte".equals(token) || "case".equals(token)
                || "catch".equals(token) || "char".equals(token) || "class".equals(token)
                || "const".equals(token) || "continue".equals(token) || "default".equals(token)
                || "do".equals(token) || "double".equals(token) || "else".equals(token)
                || "enum".equals(token) || "extends".equals(token) || "final".equals(token)
                || "finally".equals(token) || "float".equals(token) || "for".equals(token)
                || "goto".equals(token) || "if".equals(token) || "implements".equals(token)
                || "import".equals(token) || "instanceof".equals(token) || "int".equals(token)
                || "interface".equals(token) || "long".equals(token) || "native".equals(token)
                || "new".equals(token) || "package".equals(token) || "private".equals(token)
                || "protected".equals(token) || "public".equals(token) || "return".equals(token)
                || "short".equals(token) || "static".equals(token) || "strictfp".equals(token)
                || "super".equals(token) || "switch".equals(token) || "synchronized".equals(token)
                || "this".equals(token) || "throw".equals(token) || "throws".equals(token)
                || "transient".equals(token) || "try".equals(token) || "void".equals(token)
                || "volatile".equals(token) || "while".equals(token);
    }

    private void ensureSnippetImports(String code,
                                      String returnExpression,
                                      Map<String, VariableReference> bindings) {
        String combined = ((code == null) ? "" : code) + "\n"
                + ((returnExpression == null) ? "" : returnExpression);
        if (combined.trim().isEmpty()) {
            return;
        }

        Set<String> boundNames = new HashSet<>();
        if (bindings != null) {
            boundNames.addAll(bindings.keySet());
        }

        Set<String> seen = new HashSet<>();
        registerSnippetImports(combined, "\\b([A-Z][A-Za-z0-9_]*)\\s*\\.", boundNames, seen);
        registerSnippetImports(combined, "\\b([A-Z][A-Za-z0-9_]*)\\s*\\.class\\b", boundNames, seen);
        registerSnippetImports(combined, "\\(\\s*([A-Z][A-Za-z0-9_]*)\\s*\\)", boundNames, seen);
        registerSnippetImports(combined, "\\binstanceof\\s+([A-Z][A-Za-z0-9_]*)\\b", boundNames, seen);
        registerSnippetImports(combined,
                "\\bnew\\s+([A-Z][A-Za-z0-9_]*)\\s*(?:<[^>]*>)?\\s*\\(",
                boundNames,
                seen);
        // Method return types inside preserved anonymous classes / local classes,
        // e.g. "public Vector<Group> getGroups() { ... }".
        registerSnippetImports(combined,
                "\\b(?:public|protected|private)\\s+"
                        + "(?:static\\s+|final\\s+|abstract\\s+|synchronized\\s+)*"
                        + "([A-Z][A-Za-z0-9_]*)\\s*(?:<[^\\n\\r{};=]*>)?\\s+"
                        + "[A-Za-z_$][A-Za-z0-9_$]*\\s*\\(",
                boundNames,
                seen);
        // Plain declarations inside preserved snippets, e.g. "List results = ...;"
        // or "Map<String, Integer> m;" do not contain '.', '.class', casts, or 'new'.
        registerSnippetImports(combined,
                "\\b([A-Z][A-Za-z0-9_]*)\\s*(?:<[^;{}()=]*>)?\\s*(?:\\[\\s*\\])*\\s+"
                        + "[A-Za-z_$][A-Za-z0-9_$]*\\s*(?==|;|,|\\))",
                boundNames,
                seen);
        registerGenericTypeArgumentImports(combined, boundNames, seen);
        registerThrownTypeImports(combined, boundNames, seen);
    }

    private void registerSnippetImports(String text,
                                        String regex,
                                        Set<String> boundNames,
                                        Set<String> seen) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(text);
        while (matcher.find()) {
            String simpleName = matcher.group(1);
            if (!seen.add(simpleName)) {
                continue;
            }
            if (boundNames.contains(simpleName)) {
                continue;
            }
            Class<?> resolved = resolveSimpleClassName(simpleName);
            if (resolved != null) {
                getClassName(resolved);
            }
        }
    }

    private void registerThrownTypeImports(String text,
                                           Set<String> boundNames,
                                           Set<String> seen) {
        java.util.regex.Matcher throwsMatcher =
                java.util.regex.Pattern.compile("\\bthrows\\s+([^\\{;]+)").matcher(text);
        while (throwsMatcher.find()) {
            String clause = throwsMatcher.group(1);
            java.util.regex.Matcher typeMatcher =
                    java.util.regex.Pattern.compile("(?<![A-Za-z0-9_$.])([A-Z][A-Za-z0-9_]*)\\b")
                            .matcher(clause);
            while (typeMatcher.find()) {
                String simpleName = typeMatcher.group(1);
                if (!seen.add(simpleName) || boundNames.contains(simpleName)) {
                    continue;
                }
                Class<?> resolved = resolveSimpleClassName(simpleName);
                if (resolved != null) {
                    getClassName(resolved);
                }
            }
        }
    }

    private void registerGenericTypeArgumentImports(String text,
                                                    Set<String> boundNames,
                                                    Set<String> seen) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("<([^<>]+)>").matcher(text);
        while (matcher.find()) {
            String genericClause = matcher.group(1);
            java.util.regex.Matcher typeMatcher =
                    java.util.regex.Pattern.compile("(?<![A-Za-z0-9_$.])([A-Z][A-Za-z0-9_]*)\\b")
                            .matcher(genericClause);
            while (typeMatcher.find()) {
                String simpleName = typeMatcher.group(1);
                if (!seen.add(simpleName) || boundNames.contains(simpleName)) {
                    continue;
                }
                Class<?> resolved = resolveSimpleClassName(simpleName);
                if (resolved != null) {
                    getClassName(resolved);
                }
            }
        }
    }

    private Class<?> resolveSimpleClassName(String simpleName) {
        for (Map.Entry<Class<?>, String> entry : classNames.entrySet()) {
            if (simpleName.equals(entry.getValue())) {
                return entry.getKey();
            }
        }

        // If the snippet refers to a nested type by simple name (e.g. ChangeEventType.USER),
        // try declared member classes of already-known classes first.
        for (Class<?> knownClass : classNames.keySet()) {
            Class<?> nested = resolveNestedSimpleName(knownClass, simpleName);
            if (nested != null) {
                return nested;
            }
        }

        ClassLoader sut = null;
        try {
            sut = TestGenerationContext.getInstance().getClassLoaderForSUT();
        } catch (Throwable ignored) {
            // Best effort only.
        }

        String[] commonPackages = new String[]{
                "java.lang",
                "java.util",
                "java.util.concurrent",
                "java.util.regex",
                "java.util.stream",
                "java.nio",
                "java.nio.charset",
                "java.nio.file",
                "java.io",
                "java.net",
                "java.time",
                "java.math",
                "java.lang.reflect",
                "java.awt",
                "javax.naming",
                "javax.naming.directory",
                "javax.naming.ldap",
                "javax.swing",
                "org.junit.jupiter.api"
        };

        for (String pkg : commonPackages) {
            Class<?> resolved = tryLoadClass(pkg + "." + simpleName, sut);
            if (resolved != null) {
                return resolved;
            }
        }

        // Try same-package siblings of already-known classes. This helps preserved
        // snippets that use simple names from the CUT API package, e.g.
        // ChartDataModelEvent when ChartDataModelListener is already known.
        for (Class<?> knownClass : classNames.keySet()) {
            Package knownPackage = knownClass.getPackage();
            if (knownPackage == null) {
                continue;
            }
            String pkg = knownPackage.getName();
            if (pkg == null || pkg.isEmpty()) {
                continue;
            }
            Class<?> sibling = tryLoadClass(pkg + "." + simpleName, sut);
            if (sibling != null) {
                return sibling;
            }
        }

        // Resolve snippet-only simple names via known classes' related API types.
        // This covers preserved declarations like "Instances x = ...;" where the
        // type is not in common packages but is reachable from the CUT signature.
        for (Class<?> knownClass : classNames.keySet()) {
            Class<?> related = resolveRelatedSimpleName(knownClass, simpleName, 2, new HashSet<Class<?>>());
            if (related != null) {
                return related;
            }
        }

        // Common third-party utility classes that frequently appear in
        // preserved LLM snippets as qualified calls (e.g. Mockito.when(...)).
        if ("Mockito".equals(simpleName)) {
            Class<?> shadedMockito = tryLoadClass("shaded.org.evosuite.shaded.org.mockito.Mockito", sut);
            if (shadedMockito != null) {
                return shadedMockito;
            }
            Class<?> mockito = tryLoadClass("org.mockito.Mockito", sut);
            if (mockito != null) {
                return mockito;
            }
        }

        if (Properties.CLASS_PREFIX != null && !Properties.CLASS_PREFIX.trim().isEmpty()) {
            return tryLoadClass(Properties.CLASS_PREFIX + "." + simpleName, sut);
        }
        return null;
    }

    private Class<?> resolveRelatedSimpleName(Class<?> owner,
                                              String simpleName,
                                              int depth,
                                              Set<Class<?>> visited) {
        if (owner == null || simpleName == null || simpleName.isEmpty() || depth < 0) {
            return null;
        }
        if (!visited.add(owner)) {
            return null;
        }

        if (simpleName.equals(owner.getSimpleName())) {
            return owner;
        }

        for (Class<?> nested : safeDeclaredClasses(owner)) {
            if (simpleName.equals(nested.getSimpleName())) {
                return nested;
            }
        }

        if (depth == 0) {
            return null;
        }

        Class<?> superClass = owner.getSuperclass();
        Class<?> fromSuper = resolveRelatedSimpleName(superClass, simpleName, depth - 1, visited);
        if (fromSuper != null) {
            return fromSuper;
        }

        for (Class<?> iface : safeInterfaces(owner)) {
            Class<?> fromIface = resolveRelatedSimpleName(iface, simpleName, depth - 1, visited);
            if (fromIface != null) {
                return fromIface;
            }
        }

        for (java.lang.reflect.Field field : safeDeclaredFields(owner)) {
            Class<?> match = resolveRelatedType(field.getGenericType(), simpleName, depth - 1, visited);
            if (match != null) {
                return match;
            }
        }

        for (java.lang.reflect.Constructor<?> ctor : safeDeclaredConstructors(owner)) {
            for (Type parameter : ctor.getGenericParameterTypes()) {
                Class<?> match = resolveRelatedType(parameter, simpleName, depth - 1, visited);
                if (match != null) {
                    return match;
                }
            }
            for (Type exception : ctor.getGenericExceptionTypes()) {
                Class<?> match = resolveRelatedType(exception, simpleName, depth - 1, visited);
                if (match != null) {
                    return match;
                }
            }
        }

        for (java.lang.reflect.Method method : safeDeclaredMethods(owner)) {
            Class<?> returnMatch = resolveRelatedType(method.getGenericReturnType(), simpleName, depth - 1, visited);
            if (returnMatch != null) {
                return returnMatch;
            }
            for (Type parameter : method.getGenericParameterTypes()) {
                Class<?> match = resolveRelatedType(parameter, simpleName, depth - 1, visited);
                if (match != null) {
                    return match;
                }
            }
            for (Type exception : method.getGenericExceptionTypes()) {
                Class<?> match = resolveRelatedType(exception, simpleName, depth - 1, visited);
                if (match != null) {
                    return match;
                }
            }
        }

        return null;
    }

    private Class<?> resolveRelatedType(Type type,
                                        String simpleName,
                                        int depth,
                                        Set<Class<?>> visited) {
        Class<?> raw = safeErasure(type);
        if (raw == null) {
            return null;
        }
        if (raw.isArray()) {
            raw = raw.getComponentType();
        }
        return resolveRelatedSimpleName(raw, simpleName, depth, visited);
    }

    private java.lang.reflect.Field[] safeDeclaredFields(Class<?> type) {
        try {
            return type.getDeclaredFields();
        } catch (Throwable ignored) {
            return new java.lang.reflect.Field[0];
        }
    }

    private java.lang.reflect.Constructor<?>[] safeDeclaredConstructors(Class<?> type) {
        try {
            return type.getDeclaredConstructors();
        } catch (Throwable ignored) {
            return new java.lang.reflect.Constructor<?>[0];
        }
    }

    private java.lang.reflect.Method[] safeDeclaredMethods(Class<?> type) {
        try {
            return type.getDeclaredMethods();
        } catch (Throwable ignored) {
            return new java.lang.reflect.Method[0];
        }
    }

    private Class<?>[] safeInterfaces(Class<?> type) {
        try {
            return type.getInterfaces();
        } catch (Throwable ignored) {
            return new Class<?>[0];
        }
    }

    private Class<?>[] safeDeclaredClasses(Class<?> type) {
        try {
            return type.getDeclaredClasses();
        } catch (Throwable ignored) {
            return new Class<?>[0];
        }
    }

    private Class<?> resolveNestedSimpleName(Class<?> owner, String simpleName) {
        if (owner == null || simpleName == null || simpleName.isEmpty()) {
            return null;
        }
        try {
            for (Class<?> nested : owner.getDeclaredClasses()) {
                if (simpleName.equals(nested.getSimpleName())) {
                    return nested;
                }
            }
        } catch (Throwable ignored) {
            // Best effort only.
        }
        return null;
    }

    private Class<?> tryLoadClass(String fqcn, ClassLoader preferred) {
        try {
            if (preferred != null) {
                return Class.forName(fqcn, false, preferred);
            }
        } catch (ClassNotFoundException | LinkageError ignored) {
            // Try fallback loader below.
        }
        try {
            // initialize=false: we only need the Class object for name resolution
            // during code generation. Running <clinit> outside the sandboxed
            // executor thread has caused non-determinism in the past.
            return Class.forName(fqcn, false, ClassLoader.getSystemClassLoader());
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
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
        result.append(variableType).append(" ").append(getVariableName(retval)).append(" = ");
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
        if (st.doesNeedToUpdateInputs()) {
            try {
                st.updateMockedMethods();
            } catch (Exception e) {
                // ignore
            }
            st.fillWithNullRefs();

            // result += "mock(" + rawClassName + ".class);" + NEWLINE;
        } else {
            // result += "mock(" + rawClassName + ".class, new "
            //         + ViolatedAssumptionAnswer.class.getSimpleName() + "());" + NEWLINE;
        }

        if (st instanceof FunctionalMockForAbstractClassStatement) {
            result.append("mock(").append(rawClassName).append(".class, CALLS_REAL_METHODS);").append(NEWLINE);
        } else if (st.isUseLenientDefaultAnswer()) {
            // DMoN-promoted mocks use LenientMockAnswer to match the lenient default
            // answer used during search (returns sensible non-null values for all
            // return types).  No explicit doReturn stubs are needed.
            result.append("mock(").append(rawClassName).append(".class, new ")
                    .append(LenientMockAnswer.class.getSimpleName()).append("());").append(NEWLINE);
        } else {
            result.append("mock(").append(rawClassName).append(".class, new ")
                    .append(ViolatedAssumptionAnswer.class.getSimpleName()).append("());").append(NEWLINE);
        }

        // DMoN lenient mocks: skip doReturn stubs — RETURNS_MOCKS handles all calls.
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
                        }
                    }

                    parameterString = getParameterString(types, params, false, isOverloaded, 0);
                    // TODO unsure of these parameters
                } else {

                    //if return type is a primitive, then things can get complicated due to autoboxing :(

                    parameterString = getParameterStringForFMthatReturnPrimitive(returnType.getRawClass(), params);
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
                result.append("doReturn(").append(parameterString)
                        .append(").when(").append(getVariableName(retval)).append(")");
                result.append(".").append(md.getMethodName()).append("(")
                        .append(md.getInputParameterMatchers()).append(");").append(NEWLINE);

            }

        } // end if (!st.isUseLenientDefaultAnswer())

        testCode.append(result);
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
        boolean isGenericMethod = method.hasTypeParameters();
        String declarationTypeName = getMethodReturnDeclarationType(method, retval);
        if (exception != null && !statement.isDeclaredException(exception)) {
            result += "// Undeclared exception!" + NEWLINE;
        }
        boolean lastStatement = statement.getPosition() == statement.getTestCase().size() - 1;
        boolean referenced = test != null && (test.hasReferences(retval)
                || assertionReferencesReturnValue(statement, retval)
                || assertionReferencesReturnValueInTest(retval));
        boolean unused = !Properties.ASSERTIONS ? exception != null : !referenced;

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
        String parameterString = getParameterString(method.getParameterTypes(),
                parameters, isGenericMethod,
                method.isOverloaded(parameters), 0, ownerTypeForParameterRendering);

        String calleeStr = "";
        boolean requiresReturnCast = !unused
                && (!retval.isAssignableFrom(method.getReturnType())
                || requiresReturnValueCastForRawReceiver(statement, method, retval))
                && !retval.getVariableClass().isAnonymousClass()
                // Static generic methods are a special case where we shouldn't add a cast
                && !(isGenericMethod && method.getParameterTypes().length == 0 && method.isStatic());
        if (requiresReturnCast) {
            String name = getClassName(retval);
            if (!name.matches(".*\\.\\d+$")) {
                calleeStr = "(" + name + ")";
            }
        }
        if (method.isStatic()) {
            calleeStr += getClassName(method.getMethod().getDeclaringClass());
        } else {
            VariableReference callee = statement.getCallee();
            if (callee instanceof ConstantValue) {
                Class<?> declaringClass = method.getMethod().getDeclaringClass();
                Class<?> calleeClass = callee.getVariableClass();
                String calleeName = getVariableName(callee);
                // If the constant is not assignable to the declaring class, add an (Object) cast first
                // to ensure the generated code compiles (e.g., (Target)(Object)"str").
                if (calleeClass != null && !declaringClass.isAssignableFrom(calleeClass)) {
                    calleeStr += "((" + getClassName(declaringClass) + ")(Object)" + calleeName + ")";
                } else {
                    calleeStr += "((" + getClassName(declaringClass) + ")" + calleeName + ")";
                }
            } else {
                boolean calleeDeclaresMethod =
                        hasMethodBySignatureName(callee.getVariableClass(), method.getMethod());
                // If the method is not public and this is a subclass in a different package we need to cast
                if (!method.isPublic() && !method.getDeclaringClass().equals(callee.getVariableClass())
                        && callee.isAssignableTo(method.getMethod().getDeclaringClass())) {
                    String packageName1 = ClassUtils.getPackageName(method.getDeclaringClass());
                    String packageName2 = ClassUtils.getPackageName(callee.getVariableClass());
                    if (!packageName1.equals(packageName2)) {
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
            if (Properties.ASSERTIONS) {
                result += generateFailAssertion(statement, exception);
            }

            result += NEWLINE + "}";// end try block

            result += generateCatchBlock(statement, exception);
        }

        testCode.append(result + NEWLINE);
        addAssertions(statement);
    }

    private String getMethodReturnDeclarationType(GenericMethod method, VariableReference retval) {
        String renderedType = getClassName(retval);
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
        Class<?> rawCalleeClass = callee.getVariableClass();
        if (rawCalleeClass.getTypeParameters().length == 0) {
            return false;
        }
        if (!getClassName(callee).equals(getClassName(rawCalleeClass))) {
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

    private boolean dependsOnGenericTypeInformation(Type type) {
        if (type == null) {
            return false;
        }
        if (type instanceof TypeVariable || type instanceof WildcardType || type instanceof CaptureType) {
            return true;
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            if (dependsOnGenericTypeInformation(parameterizedType.getRawType())) {
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

        Class<?> ex = getExceptionClassToUse(exception);

        // preparing the catch block
        if (!(exception instanceof RuntimeException) && !(exception instanceof Error)) {
            // This is a checked exception.
            if (statement.isDeclaredException(exception)) {
                result += " catch(" + getClassName(ex) + " e) {" + NEWLINE;
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
                result += " catch(" + getClassName(Throwable.class) + " e) {" + NEWLINE;
            }
        } else {
            String className = getClassName(ex);
            if (className.contains("MockitoMock")) {
                className = getClassName(Throwable.class);
            }
            result += " catch(" + className + " e) {" + NEWLINE;
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
                && isExceptionToAssertThrownBy(ex) && !Properties.NO_RUNTIME_DEPENDENCY) {
            /*
                do not check source if it comes from a non-runtime evosuite
                class. this could happen if source is an instrumentation done
                during search which is not applied to runtime
             */

            //from class EvoAssertions
            result += "    verifyException(\"" + sourceClass + "\", e);" + NEWLINE;
        }

        result += "}" + NEWLINE;// closing the catch block
        return result;
    }

    private String getSourceClassName(Throwable exception) {
        if (exception.getStackTrace() == null || exception.getStackTrace().length == 0) {
            return null;
        }
        return exception.getStackTrace()[0].getClassName();
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
                || ex.getCanonicalName().startsWith("com.sun.")) {
            ex = ex.getSuperclass();
        }
        return ex;
    }

    private String getSimpleTypeName(Type type) {
        String typeName = getTypeName(type);
        int dotIndex = typeName.lastIndexOf(".");
        if (dotIndex >= 0 && (dotIndex + 1) < typeName.length()) {
            typeName = typeName.substring(dotIndex + 1);
        }

        return typeName;
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
        String parameterString = getParameterString(parameterTypes, parameters,
                isGenericConstructor,
                constructor.isOverloaded(parameters),
                startPos);

        // Use the constructor's owner type for the LHS declaration so that it
        // always matches the RHS "new OwnerType(...)".  The retval's variable class
        // can diverge from the constructor owner (e.g. java.sql.Date retval with a
        // java.util.Date constructor), and when both simple names collide the import
        // picks one while the other is fully qualified, producing uncompilable code.
        String constructorTypeName = getTypeName(constructor.getOwnerType());

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

        if (isSourceAccessibleFromGeneratedTest(rawConstructor)) {
            if (isNonStaticMemberClass) {
                result += declarationPrefix + declarationVarName + " = "
                        + getVariableName(parameters.get(0))
                        + ".new "
                        + getSimpleTypeName(constructor.getOwnerType()) + "("
                        + parameterString + ");";
            } else {
                result += declarationPrefix + declarationVarName + " = new "
                        + getTypeName(constructor.getOwnerType())
                        + "(" + parameterString + ");";
            }
        } else {
            // Fallback for private/protected/package-private constructors that are not
            // source-accessible from the generated test package.
            String constructorVarName = "constructor" + statement.getPosition();
            String classLiteral = getClassName(rawConstructor.getDeclaringClass());
            result += "java.lang.reflect.Constructor<?> " + constructorVarName + " = "
                    + classLiteral + ".class.getDeclaredConstructor(";
            Class<?>[] rawParameterTypes = rawConstructor.getParameterTypes();
            for (int i = 0; i < rawParameterTypes.length; i++) {
                if (i > 0) {
                    result += ", ";
                }
                result += getClassName(rawParameterTypes[i]) + ".class";
            }
            result += ");" + NEWLINE;
            result += constructorVarName + ".setAccessible(true);" + NEWLINE;
            String reflectiveParameterString = getParameterString(parameterTypes, parameters,
                    isGenericConstructor, constructor.isOverloaded(parameters), 0);
            result += declarationPrefix + declarationVarName
                    + " = (" + constructorTypeName + ") "
                    + constructorVarName + ".newInstance(" + reflectiveParameterString + ");";
        }

        if (shouldUseTryCatch(exception, statement.isDeclaredException(exception))) {
            if (Properties.ASSERTIONS) {
                result += generateFailAssertion(statement, exception);
            }

            result += NEWLINE + "}";// end try block

            result += generateCatchBlock(statement, exception);
        }

        testCode.append(result + NEWLINE);
        addAssertions(statement);
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
        // No configured output package: assume the generated test can reside in
        // the constructor's own package, so package-private access is legal.
        if (isEmptyPackageName(testPackageName)) {
            return true;
        }
        return isSamePackage(constructor.getDeclaringClass(), testPackageName);
    }

    private static boolean isTypeAccessibleFromGeneratedTest(Class<?> type, String testPackageName) {
        if (type == null) {
            return false;
        }

        Class<?> enclosing = type.getEnclosingClass();
        if (enclosing != null && !isTypeAccessibleFromGeneratedTest(enclosing, testPackageName)) {
            return false;
        }

        int modifiers = type.getModifiers();
        if (Modifier.isPublic(modifiers)) {
            return true;
        }
        if (Modifier.isPrivate(modifiers)) {
            return false;
        }
        // No configured output package: treat package-private types as accessible,
        // since the generated test can be emitted into the type's own package.
        if (isEmptyPackageName(testPackageName)) {
            return true;
        }
        return isSamePackage(type, testPackageName);
    }

    private static boolean isEmptyPackageName(String packageName) {
        return packageName == null || packageName.isEmpty();
    }

    private String getGeneratedTestPackageName() {
        String classPrefix = Properties.CLASS_PREFIX;
        if (classPrefix == null) {
            return "";
        }
        return classPrefix.trim();
    }

    private static boolean isSamePackage(Class<?> clazz, String packageName) {
        if (clazz == null) {
            return false;
        }
        Package pkg = clazz.getPackage();
        String classPackage = (pkg == null) ? "" : pkg.getName();
        String testPackage = packageName == null ? "" : packageName;
        return classPackage.equals(testPackage);
    }

    private boolean shouldUseTryCatch(Throwable t, boolean isDeclared) {
        return t != null
                && !(t instanceof OutOfMemoryError)
                && !(t instanceof TooManyResourcesException)
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
        if (isHeadlessExceptionType(ex) || isEvoReflectionHelperError(exception)) {
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
     * {@inheritDoc}
     */
    @Override
    public void visitAssignmentStatement(AssignmentStatement statement) {
        String cast = "";
        VariableReference retval = statement.getReturnValue();
        String declarationVarName = getDeclarationVariableName(retval);
        VariableReference parameter = statement.getValue();

        if (!retval.getVariableClass().equals(parameter.getVariableClass())) {
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
                cast = "(" + getClassName(retval) + ") ";
            }
        }

        // If this assignment statement introduces a new variable (i.e., the retval is
        // "owned" by this statement), we need to include the type declaration.
        // For reassignments to existing variables, field references, or array indices,
        // we omit the type since the variable was already declared.
        boolean needsTypeDeclaration = !(retval instanceof FieldReference)
                && !(retval instanceof ArrayIndex)
                && retval.getStPosition() == statement.getPosition();

        if (needsTypeDeclaration) {
            testCode.append(getClassName(retval) + " ");
            testCode.append(declarationVarName);
        } else {
            testCode.append(getVariableName(retval));
        }
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
        testCode.append(getClassName(retval) + " " + varName + " = null;" + NEWLINE);
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
        super.visitStatement(statement);
    }


}
