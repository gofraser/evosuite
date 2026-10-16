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

import com.googlecode.gentyref.CaptureType;
import com.googlecode.gentyref.GenericTypeReflector;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.*;
import java.util.List;
import java.util.Map;

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

        if (isUninstantiatedClassTypeParameters(lhsType)
                && isRawCompatibleRightHandSide(lhsType, rhsType)) {
            Class<?> lhsRawClass = getRawClass(lhsType);
            Class<?> rhsRawClass = getRawClass(rhsType);
            if (lhsRawClass != null && rhsRawClass != null) {
                return lhsRawClass.isAssignableFrom(rhsRawClass);
            }
        }

        if (isClassLiteralAssignable(lhsType, rhsType)) {
            return true;
        }

        if (isAssignableViaExactSuperType(lhsType, rhsType)) {
            return true;
        }

        try {
            return TypeUtils.isAssignable(rhsType, lhsType);
        } catch (Throwable e) {
            logger.debug("Found unassignable type: " + e);
            return false;
        }
    }

    private static boolean isAssignableViaExactSuperType(Type lhsType, Type rhsType) {
        if (!(lhsType instanceof ParameterizedType)) {
            return false;
        }

        Class<?> lhsRawClass = getRawClass(lhsType);
        if (lhsRawClass == null) {
            return false;
        }

        Type exactSuperType;
        try {
            exactSuperType = GenericTypeReflector.getExactSuperType(rhsType, lhsRawClass);
        } catch (RuntimeException e) {
            // gentyref rejects types it cannot erase (wildcards nested in
            // arrays or bounds); not assignable is the safe answer.
            logger.debug("Cannot compute exact super type of {}: {}", rhsType, e.toString());
            return false;
        }
        if (exactSuperType == null) {
            return false;
        }

        if (exactSuperType instanceof Class && !isDefinitelyRaw(rhsType)) {
            // gentyref's own isMissingTypeParameters(Class) walks getEnclosingClass() without
            // checking Modifier.isStatic, so a static nested class of a generic outer class
            // (DOMDeserializer<T>.DocumentDeserializer) counts as raw and its whole supertype
            // chain is erased: the "exact" supertype comes back as the raw JsonDeserializer
            // class, which commons-lang3 would then accept for JsonDeserializer<Object>. The
            // rhs is not raw by the static-aware definition below, so do not trust the erased
            // result; the caller's commons-lang3 check resolves the type arguments correctly.
            return false;
        }

        if (TypeUtils.equals(lhsType, exactSuperType)) {
            return true;
        }

        try {
            return TypeUtils.isAssignable(exactSuperType, lhsType);
        } catch (Throwable e) {
            logger.debug("Found unassignable exact super type: " + e);
            return false;
        }
    }

    /**
     * Returns true if {@code type} is a plain {@link Class} that is genuinely raw, ie
     * {@link #isMissingTypeParameters(Type)} would say so. Any other kind of type (an
     * already-parameterized type, a type variable, a gentyref {@code CaptureType}, etc)
     * is never "raw" by construction, so this deliberately avoids calling
     * {@code isMissingTypeParameters} on anything it does not accept.
     */
    private static boolean isDefinitelyRaw(Type type) {
        return type instanceof Class && isMissingTypeParameters(type);
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
     * Returns true if the given type is parameterized only with type variables
     * declared by the raw class (or one of its enclosing classes).
     *
     * <p>This models unresolved declarations such as {@code Hashtable<K,V>},
     * where assignment should be checked using raw types because concrete
     * bindings for {@code K} and {@code V} are unknown at this point.
     */
    private static boolean isUninstantiatedClassTypeParameters(Type type) {
        if (!(type instanceof ParameterizedType)) {
            return false;
        }

        ParameterizedType parameterizedType = (ParameterizedType) type;
        if (!(parameterizedType.getRawType() instanceof Class)) {
            return false;
        }
        Class<?> rawClass = (Class<?>) parameterizedType.getRawType();
        if (Class.class.equals(rawClass)) {
            return false;
        }

        boolean hasTypeVariableArgument = false;
        for (Type argument : parameterizedType.getActualTypeArguments()) {
            if (argument instanceof TypeVariable) {
                hasTypeVariableArgument = true;
                if (!isDeclaredByRawClassOrEnclosing(rawClass, (TypeVariable<?>) argument)) {
                    return false;
                }
            } else if (argument instanceof CaptureType) {
                return false;
            } else {
                return false;
            }
        }
        return hasTypeVariableArgument;
    }

    private static boolean isDeclaredByRawClassOrEnclosing(Class<?> rawClass, TypeVariable<?> typeVariable) {
        GenericDeclaration declaration = typeVariable.getGenericDeclaration();
        for (Class<?> current = rawClass; current != null; current = current.getEnclosingClass()) {
            if (declaration == current) {
                return true;
            }
        }
        return false;
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

    private static boolean isRawCompatibleRightHandSide(Type lhsType, Type rhsType) {
        if (isConcreteInstantiation(rhsType)) {
            return true;
        }

        if (rhsType instanceof Class) {
            Class<?> lhsRawClass = getRawClass(lhsType);
            // Raw runtime classes (eg, Properties.class) should be assignable to unresolved
            // generic targets (eg, Map<K,V>) using raw type compatibility.
            return lhsRawClass != null
                    && Map.class.isAssignableFrom(lhsRawClass)
                    && !isMissingTypeParameters(rhsType);
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
     * Allow assignment to Class&lt;T&gt; when both operands are Class&lt;...&gt; and the right-hand type
     * argument is contained by the left-hand one, so that the raw reification of a class literal
     * ({@code Class<LinkedList>} from {@code LinkedList.class}) satisfies {@code Class<LinkedList<String>>}.
     *
     * <p>Class&lt;T&gt; is invariant in T, so this must compare the <em>full</em> type argument structurally
     * rather than its raw erasure: a {@code Class<List<Integer>>} is not a {@code Class<List<String>>} even
     * though both erase to {@code Class<List>}. The one exception is a wildcard, which follows the normal
     * Java wildcard-containment rules (JLS 4.5.1) instead of plain equality: {@code Class<?>} accepts any
     * {@code Class<...>}, and {@code Class<? extends Number>} accepts {@code Class<Integer>}. A wildcard
     * nested *inside* a type argument (eg, the {@code ?} in {@code Class<List<?>>}) does not get this
     * leniency: at that nesting level {@code List<?>} and {@code List<String>} are compared structurally,
     * so {@code Class<List<?>>} does not accept {@code Class<List<String>>}.
     */
    private static boolean isClassLiteralAssignable(Type lhsType, Type rhsType) {
        Class<?> lhsRawClass = getRawClass(lhsType);
        Class<?> rhsRawClass = getRawClass(rhsType);
        if (!Class.class.equals(lhsRawClass) || !Class.class.equals(rhsRawClass)) {
            return false;
        }

        Type lhsClassArg = getClassLiteralTypeArgument(lhsType);
        Type rhsClassArg = getClassLiteralTypeArgument(rhsType);
        if (lhsClassArg == null || rhsClassArg == null) {
            return false;
        }
        return isContainedTypeArgument(rhsClassArg, lhsClassArg);
    }

    private static Type getClassLiteralTypeArgument(Type classType) {
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
        return actualArgs[0];
    }

    /**
     * Tells whether the type argument {@code inner} is contained by the type argument {@code outer}
     * (ie, {@code inner <= outer} per JLS 4.5.1), which is the rule Java uses to decide whether
     * {@code C<outer>} is a supertype of {@code C<inner>} for the same generic declaration {@code C}
     * (here always {@code java.lang.Class}).
     *
     * <ul>
     *     <li>If {@code outer} is an unbounded wildcard ({@code ?}), everything is contained.</li>
     *     <li>If {@code outer} is {@code ? extends U}, {@code inner} is contained when its own effective
     *     upper bound is a subtype of {@code U}.</li>
     *     <li>If {@code outer} is {@code ? super L}, {@code inner} is contained when its own effective
     *     lower bound is a supertype of {@code L}.</li>
     *     <li>Otherwise {@code outer} is a concrete (non-wildcard) type argument, and containment requires
     *     exact structural equality with {@code inner} (Class&lt;T&gt; is invariant in T) -- with one
     *     legacy exception: a class literal such as {@code LinkedList.class} is reified only to the raw
     *     {@code Class<LinkedList>}, never to {@code Class<LinkedList<String>>}, so Java itself has no
     *     syntax to obtain a fully concrete {@code Class<LinkedList<String>>} value from one. EvoSuite
     *     still needs such a raw class literal to satisfy any instantiation of the same raw class (see
     *     commit 90e6a0906, which introduced this helper, and the erasure-based comparison it always
     *     used here), so a raw (type-parameter-missing) {@code inner} is contained whenever its class
     *     equals the erasure of {@code outer}, regardless of {@code outer}'s own type arguments.</li>
     * </ul>
     */
    private static boolean isContainedTypeArgument(Type inner, Type outer) {
        if (outer instanceof WildcardType) {
            WildcardType outerWildcard = (WildcardType) outer;
            Type[] outerUpperBounds = outerWildcard.getUpperBounds();
            Type[] outerLowerBounds = outerWildcard.getLowerBounds();

            if (outerLowerBounds.length == 0 && isObjectType(outerUpperBounds[0])) {
                // outer == "?", the unbounded wildcard: it contains every type argument.
                return true;
            }

            if (outerLowerBounds.length == 0) {
                // outer == "? extends U"
                Type innerUpperBound = effectiveUpperBoundForContainment(inner);
                return innerUpperBound != null && isSubtype(innerUpperBound, outerUpperBounds[0]);
            }

            // outer == "? super L"
            Type innerLowerBound = effectiveLowerBoundForContainment(inner);
            return innerLowerBound != null && isSubtype(outerLowerBounds[0], innerLowerBound);
        }

        // outer is a concrete type argument: Class<T> is invariant in T, so nothing short of
        // structural equality qualifies, and a wildcard on the inner side can never match a
        // concrete outer type (eg, Class<List<?>> does not accept Class<List<String>>).
        if (inner instanceof WildcardType) {
            return false;
        }

        // Legacy raw-class-literal leniency (see the class-level javadoc above): a class literal
        // is always reified to its raw class, so treat that raw class as matching any
        // instantiation of itself, the same way the erasure-only comparison this helper has
        // always used for the top-level Class<...> argument did.
        if (isMissingTypeParameters(inner)) {
            Class<?> outerRawClass = getRawClass(outer);
            if (inner.equals(outerRawClass)) {
                return true;
            }
        }

        return TypeUtils.equals(inner, outer);
    }

    /**
     * The effective upper bound of a type argument for the purposes of wildcard containment: a plain
     * type is its own upper bound, {@code ? extends S} contributes {@code S}, and {@code ?}
     * contributes {@code Object}. A lower-bounded wildcard ({@code ? super S}) has no useful upper
     * bound other than {@code Object}, which is only reachable when the outer bound is also
     * {@code Object} (already handled by the unbounded-wildcard short-circuit above), so this
     * returns {@code null} for it.
     */
    private static Type effectiveUpperBoundForContainment(Type inner) {
        if (inner instanceof WildcardType) {
            WildcardType innerWildcard = (WildcardType) inner;
            if (innerWildcard.getLowerBounds().length > 0) {
                return null;
            }
            return innerWildcard.getUpperBounds()[0];
        }
        return inner;
    }

    /**
     * The effective lower bound of a type argument for the purposes of wildcard containment: a plain
     * type is its own lower bound, and {@code ? super S} contributes {@code S}. An upper-bounded or
     * unbounded wildcard has no lower bound to offer.
     */
    private static Type effectiveLowerBoundForContainment(Type inner) {
        if (inner instanceof WildcardType) {
            WildcardType innerWildcard = (WildcardType) inner;
            if (innerWildcard.getLowerBounds().length == 0) {
                return null;
            }
            return innerWildcard.getLowerBounds()[0];
        }
        return inner;
    }

    private static boolean isObjectType(Type type) {
        return Object.class.equals(type);
    }

    private static boolean isSubtype(Type subType, Type superType) {
        if (isObjectType(superType) || TypeUtils.equals(subType, superType)) {
            return true;
        }
        try {
            return TypeUtils.isAssignable(subType, superType);
        } catch (Throwable e) {
            logger.debug("Found unassignable type argument bound: " + e);
            return false;
        }
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
            // parameter. Static nested types (enums, interfaces, static classes) do NOT
            // capture their enclosing class's type parameters, so stop traversal there.
            for (Class<?> clazz = (Class<?>) type; clazz != null; clazz = clazz.getEnclosingClass()) {
                if (clazz.getTypeParameters().length != 0) {
                    return true;
                }
                if (Modifier.isStatic(clazz.getModifiers())) {
                    break;
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
