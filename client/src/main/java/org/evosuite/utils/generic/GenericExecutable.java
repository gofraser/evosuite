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
package org.evosuite.utils.generic;

import com.googlecode.gentyref.GenericTypeReflector;
import org.evosuite.testcase.ConstraintHelper;
import org.evosuite.testcase.variable.NullReference;
import org.evosuite.testcase.variable.VariableReference;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

/**
 * A shared superclass for the common functionality of {@link GenericMethod} and
 * {@link GenericConstructor}, similar in spirit to {@link java.lang.reflect.Executable Executable}
 * from the Java Reflection API.
 *
 * @param <T> the type of the subclass
 * @param <U> the type of the executable (Method or Constructor)
 */
public abstract class GenericExecutable<T extends GenericExecutable<T, U>, U extends Executable>
        extends GenericAccessibleObject<T> {

    /**
     * Constructor.
     *
     * @param owner the class where this accessible object is located in
     */
    GenericExecutable(final GenericClass<?> owner) {
        super(owner);
    }

    public abstract Type[] getRawParameterTypes();

    public abstract Type getReturnType();

    /**
     * Returns the exact parameter types of the given method in the given type.
     * This may be different from <tt>m.getGenericParameterTypes()</tt> when the
     * method was declared in a superclass, or <tt>type</tt> has a type
     * parameter that is used in one of the parameters, or <tt>type</tt> is a
     * raw type.
     */
    public Type[] getExactParameterTypes(U m, Type type) {
        Type[] parameterTypes = m.getGenericParameterTypes();
        Type exactDeclaringType = getExactDeclaringType(m, type);
        if (exactDeclaringType == null) {
            return m.getParameterTypes();
        }

        Type[] result = new Type[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            result[i] = mapTypeParameters(parameterTypes[i], exactDeclaringType);
        }
        return result;
    }

    /**
     * Returns the parameterization of the class declaring {@code m} as seen from {@code type},
     * which the declared generic return and parameter types of {@code m} are mapped through.
     *
     * @param m    the executable
     * @param type the type the executable is accessed on
     * @return the exact declaring type, or {@code null} if it cannot be resolved, in which case
     *         the caller falls back to the erased types
     */
    static Type getExactDeclaringType(Executable m, Type type) {
        Class<?> declaringClass = m.getDeclaringClass();
        Type exactDeclaringType;
        try {
            exactDeclaringType = GenericUtils.getExactSuperType(GenericTypeReflector.capture(type),
                    declaringClass);
        } catch (TypeNotPresentException e) {
            // May happen with dependency issues involving annotations:
            // https://bugs.java.com/view_bug.do?bug_id=JDK-7183985
            logger.debug("Cannot resolve the declaring type of {} in {}: {}", m, type, e.toString());
            return null;
        }
        if (exactDeclaringType == null) {
            // Expected when 'type' and the declaring class are loaded by different classloaders
            // (same name, different Class objects); only an assignable raw type is unexpected.
            Class<?> rawType = getRawClass(type);
            if (rawType != null && declaringClass.isAssignableFrom(rawType)) {
                logger.debug("The executable {} is not a member of type {} - declared in {}"
                        + " [unexpected, rawType={}]", m, type, declaringClass, rawType);
            }
        }
        return exactDeclaringType;
    }

    private static Class<?> getRawClass(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            Type raw = ((ParameterizedType) type).getRawType();
            if (raw instanceof Class<?>) {
                return (Class<?>) raw;
            }
        }
        return null;
    }

    public abstract Type[] getParameterTypes();

    public abstract Parameter[] getParameters();

    @Override
    public final boolean isField() {
        return false;
    }

    /**
     * Tells whether there the owning class contains an overloaded executable with the given list
     * of parameters.
     *
     * @param parameters the parameter list of the overloaded executable
     * @return {@code} true if there is an overloaded executable, {@code false} otherwise
     */
    public abstract boolean isOverloaded(List<VariableReference> parameters);

    protected boolean isNullArgument(VariableReference parameter) {
        if (parameter instanceof NullReference) {
            return true;
        }
        try {
            return parameter.getTestCase() != null && ConstraintHelper.isNull(parameter, parameter.getTestCase());
        } catch (AssertionError | RuntimeException ignored) {
            return false;
        }
    }

    protected boolean isAssignableToParameter(VariableReference parameter, Type targetType) {
        if (isNullArgument(parameter)) {
            try {
                Class<?> rawClass = GenericClassFactory.get(targetType).getRawClass();
                return rawClass == null || !rawClass.isPrimitive();
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return parameter.isAssignableTo(targetType);
    }

    /**
     * Returns the fully qualified name concatenated with the descriptor of the underlying
     * executable.
     *
     * @return the fully qualified name concatenated with the descriptor
     */
    public abstract String getNameWithDescriptor();

    /**
     * Returns the descriptor for the underlying executable.
     *
     * @return the descriptor
     */
    public abstract String getDescriptor();
}
