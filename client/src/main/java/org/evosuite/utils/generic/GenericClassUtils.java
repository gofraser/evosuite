/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 * <p>
 * This file is part of EvoSuite.
 * <p>
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 * <p>
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 * <p>
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.utils.generic;

import com.googlecode.gentyref.CaptureType;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.*;
import java.util.List;

/**
 * Utility class for {@code GenericClassImpl}.
 */
public class GenericClassUtils {

    private static final Logger logger = LoggerFactory.getLogger(GenericClassUtils.class);

    private GenericClassUtils() {
    }

    /**
     * Tells whether the type {@code rhsType} (on the right-hand side of an assignment) can be
     * assigned to the type {@code lhsType} (on the left-hand side of an assignment).
     *
     * @param lhsType the type on the left-hand side (target type)
     * @param rhsType the type on the right-hand side (subject type to be assigned to target type) a
     *                {@link java.lang.reflect.Type} object.
     * @return {@code true} if {@code rhsType} is assignable to {@code lhsType}
     */
    public static boolean isAssignable(Type lhsType, Type rhsType) {
        if (rhsType == null || lhsType == null) {
            return false;
        }

        /*
         * Handle raw type assignments where EvoSuite has synthesized type variables
         * (eg, ArrayList<E>) for a raw field (eg, ArrayList). In such cases we
         * should allow assignment based on raw type compatibility only.
         *
         * This keeps strict generic checks when the SUT actually declares concrete
         * type arguments (eg, ArrayList<String>), but avoids rejecting valid raw
         * assignments (eg, ArrayList<Object> -> ArrayList<E>).
         */
        if (isPurelyGeneric(lhsType) && isConcreteInstantiation(rhsType)) {
            Class<?> lhsRawClass = getRawClass(lhsType);
            Class<?> rhsRawClass = getRawClass(rhsType);
            if (lhsRawClass != null && rhsRawClass != null) {
                return lhsRawClass.isAssignableFrom(rhsRawClass);
            }
        }

        if (isClassLiteralAssignable(lhsType, rhsType)) {
            return true;
        }

        try {
            return TypeUtils.isAssignable(rhsType, lhsType);
        } catch (Throwable e) {
            logger.debug("Found unassignable type: " + e);
            return false;
        }
    }

    /**
     * Returns true if the given type is a parameterized type or a generic array
     * where all type arguments are "external" type variables (ie, not declared
     * by the raw class itself).
     */
    private static boolean isPurelyGeneric(Type type) {
        if (type instanceof ParameterizedType) {
            ParameterizedType paramType = (ParameterizedType) type;
            Type rawType = paramType.getRawType();
            Class<?> rawClass = rawType instanceof Class ? (Class<?>) rawType : null;
            boolean hasExternalTypeVariable = false;
            for (Type t : paramType.getActualTypeArguments()) {
                if (!isTypeVariableLike(t)) {
                    return false;
                }
                if (t instanceof CaptureType) {
                    hasExternalTypeVariable = true;
                } else if (rawClass != null
                        && ((TypeVariable<?>) t).getGenericDeclaration() != rawClass) {
                    hasExternalTypeVariable = true;
                }
            }
            return hasExternalTypeVariable;
        } else if (type instanceof GenericArrayType) {
            return isPurelyGeneric(((GenericArrayType) type).getGenericComponentType());
        }
        return false;
    }

    private static boolean isTypeVariableLike(Type type) {
        return type instanceof TypeVariable || type instanceof CaptureType;
    }

    /**
     * Returns the raw class for assignment compatibility checks.
     * This intentionally ignores wildcard and type-variable erasure to avoid
     * over-approximating assignability.
     */
    private static Class<?> getRawClass(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            Type rawType = ((ParameterizedType) type).getRawType();
            if (rawType instanceof Class) {
                return (Class<?>) rawType;
            }
        }
        if (type instanceof GenericArrayType) {
            Class<?> componentType = getRawClass(((GenericArrayType) type).getGenericComponentType());
            if (componentType != null) {
                return Array.newInstance(componentType, 0).getClass();
            }
        }
        return null;
    }

    /**
     * Returns true if the type is a concrete parameterized/generic-array instantiation.
     * This excludes wildcard and type-variable based types to avoid over-approximating.
     */
    private static boolean isConcreteInstantiation(Type type) {
        if (type instanceof ParameterizedType) {
            for (Type argument : ((ParameterizedType) type).getActualTypeArguments()) {
                if (!isConcreteTypeArgument(argument)) {
                    return false;
                }
            }
            return true;
        }
        if (type instanceof GenericArrayType) {
            return isConcreteInstantiation(((GenericArrayType) type).getGenericComponentType());
        }
        return false;
    }

    private static boolean isConcreteTypeArgument(Type type) {
        if (type instanceof Class) {
            return true;
        }
        if (type instanceof ParameterizedType) {
            return isConcreteInstantiation(type);
        }
        if (type instanceof GenericArrayType) {
            return isConcreteInstantiation(type);
        }
        return false;
    }

    /**
     * Class literals are reified only to raw classes (eg, LinkedList.class has type Class&lt;LinkedList&gt;).
     * Allow assignment to Class&lt;T&gt; when both operands are Class&lt;...&gt; and the nested class erasures match
     * the normal class-assignability relation.
     */
    private static boolean isClassLiteralAssignable(Type lhsType, Type rhsType) {
        Class<?> lhsRawClass = getRawClass(lhsType);
        Class<?> rhsRawClass = getRawClass(rhsType);
        if (!Class.class.equals(lhsRawClass) || !Class.class.equals(rhsRawClass)) {
            return false;
        }

        Class<?> lhsClassArg = getClassLiteralTypeArgument(lhsType);
        Class<?> rhsClassArg = getClassLiteralTypeArgument(rhsType);
        return lhsClassArg != null && rhsClassArg != null && lhsClassArg.isAssignableFrom(rhsClassArg);
    }

    private static Class<?> getClassLiteralTypeArgument(Type classType) {
        if (!(classType instanceof ParameterizedType)) {
            return null;
        }
        ParameterizedType parameterized = (ParameterizedType) classType;
        if (!(parameterized.getRawType() instanceof Class) || !Class.class.equals(parameterized.getRawType())) {
            return null;
        }
        Type[] actualArgs = parameterized.getActualTypeArguments();
        if (actualArgs.length != 1) {
            return null;
        }
        return getRawClass(actualArgs[0]);
    }


    /**
     * Checks if {@code type} is a instanceof {@code java.lang.Class}. If so, this method checks if type or an
     * enclosing class has a type parameter.
     *
     * <p>If type is not an instance of java.lang.Class, it is assumed that no type parameter is missing.
     *
     * @param type The type which should be checked.
     * @return Whether at least one missing type parameter was found.
     */
    public static boolean isMissingTypeParameters(Type type) {
        if (type instanceof Class) {
            // Handle nested classes: check if any of the enclosing classes declares a type
            // parameter.
            for (Class<?> clazz = (Class<?>) type; clazz != null; clazz = clazz.getEnclosingClass()) {
                if (clazz.getTypeParameters().length != 0) {
                    return true;
                }
            }

            return false;
        }

        if (type instanceof ParameterizedType || type instanceof GenericArrayType
                || type instanceof TypeVariable || type instanceof WildcardType) { // TODO what about CaptureType?
            return false;
        }

        // Should not happen unless we have a custom implementation of the Type interface.
        throw new AssertionError("Unexpected type " + type.getClass());
    }

    /**
     * Tells whether {@code subclass} extends or implements the given {@code superclass}.
     *
     * @param superclass the superclass
     * @param subclass   the subclass
     * @return {@code true} if {@code subclass} is a subclass of {@code superclass}
     */
    public static boolean isSubclass(Type superclass, Type subclass) {
        List<Class<?>> superclasses = ClassUtils.getAllSuperclasses((Class<?>) subclass);
        List<Class<?>> interfaces = ClassUtils.getAllInterfaces((Class<?>) subclass);
        return superclasses.contains(superclass) || interfaces.contains(superclass);
    }

}
