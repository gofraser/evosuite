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
import org.apache.commons.lang3.reflect.TypeUtils;
import org.evosuite.Properties;
import org.evosuite.utils.ParameterizedTypeImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class GenericUtils {

    /**
     * Constant to represent @NotNull annotation.
     */
    public static final String NONNULL = "Nonnull";

    /**
     * Checks if the type is assignable to the type variable.
     *
     * @param type         the type
     * @param typeVariable the type variable
     * @return true if assignable
     */
    public static boolean isAssignable(Type type, TypeVariable<?> typeVariable) {
        boolean isAssignable = true;
        for (Type boundType : typeVariable.getBounds()) {
            // Have to resolve the type because a typevariable may have a reference to itself
            // in its bounds
            Type resolvedBoundType = GenericUtils.replaceTypeVariable(boundType,
                    typeVariable, type);
            if (!GenericClassUtils.isAssignable(resolvedBoundType, type)) {
                isAssignable = false;
                break;
            }
        }
        return isAssignable;
    }

    private static final Logger logger = LoggerFactory.getLogger(GenericUtils.class);

    /**
     * Returns a string representation of the type variable map sorted by keys and values.
     *
     * @param map the map
     * @return the string representation
     */
    public static String stableTypeVariableMapToString(Map<TypeVariable<?>, Type> map) {
        if (map == null) {
            return "null";
        }
        if (map.isEmpty()) {
            return "{}";
        }
        List<Entry<TypeVariable<?>, Type>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Comparator
                .comparing((Entry<TypeVariable<?>, Type> e) -> typeVariableKeyForSort(e.getKey()))
                .thenComparing(e -> typeValueForSort(e.getValue())));

        StringBuilder builder = new StringBuilder("{");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            Entry<TypeVariable<?>, Type> entry = entries.get(i);
            builder.append(typeVariableDisplay(entry.getKey()))
                    .append("=")
                    .append(typeValueDisplay(entry.getValue()));
        }
        builder.append("}");
        return builder.toString();
    }

    static List<TypeVariable<?>> sortedTypeVariables(Map<TypeVariable<?>, ?> map) {
        if (map == null || map.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<TypeVariable<?>> variables = new ArrayList<>(map.keySet());
        variables.sort(Comparator.comparing(GenericUtils::typeVariableKeyForSort));
        return variables;
    }

    private static String typeVariableKeyForSort(TypeVariable<?> variable) {
        if (variable == null) {
            return "null";
        }
        String name = variable.getName();
        String declaration = String.valueOf(variable.getGenericDeclaration());
        return name + "|" + declaration;
    }

    private static String typeValueForSort(Type value) {
        return typeValueDisplay(value);
    }

    private static String typeVariableDisplay(TypeVariable<?> variable) {
        if (variable == null) {
            return "null";
        }
        return variable.getName();
    }

    private static String typeValueDisplay(Type value) {
        if (value instanceof TypeVariable<?>) {
            return ((TypeVariable<?>) value).getName();
        }
        return String.valueOf(value);
    }

    /**
     * Replaces type variables in the target type using the provided map.
     *
     * <p>Each substitution is applied via {@link #replaceTypeVariable(Type, TypeVariable, Type)},
     * which bounds its own descent into {@code returnType}'s nested structure at
     * {@link Properties#MAX_GENERIC_DEPTH}; that cap resets for every entry of {@code typeMap}, so
     * it is what protects against runaway recursion here rather than the size of {@code typeMap}.
     *
     * <p>Before applying an entry, we check whether its replacement type is self-referential --
     * whether it contains the very variable it is replacing, either directly (e.g. {@code T ->
     * Sequence<T>}) or by following other entries of {@code typeMap} (e.g. {@code S -> Sequence<T>}
     * with {@code T -> Something<S>}). F-bounded declarations such as {@code <T, S extends
     * Sequence<T>, F extends FastaRecord<T,S>>} produce exactly this shape. Substituting the raw
     * replacement in that case reintroduces the variable into {@code returnType}, so it stays
     * eligible for substitution by a later entry (or a later call over the same map), and the type
     * keeps growing every round instead of reaching a fixed point -- this is what previously drove
     * >60-frame recursion in {@link org.evosuite.utils.generic.GenericClassImpl#satisfiesBoundaries(
     * TypeVariable, Map)}'s {@code isConcreteType}/{@code hasWildcardOrTypeVariable} helpers. When
     * that shape is detected we fall back to {@link #replaceTypeVariablesWithWildcards(Type)} for
     * that entry's replacement, so the substitution result never grows beyond the original nesting
     * plus one level. The normal, non-cyclic case is unaffected: the raw replacement is used exactly
     * as before.
     *
     * @param targetType the target type
     * @param typeMap    the map of type variables to types
     * @return the type with replaced variables
     */
    public static Type replaceTypeVariables(Type targetType,
                                            Map<TypeVariable<?>, Type> typeMap) {
        Type returnType = targetType;
        for (Entry<TypeVariable<?>, Type> entry : typeMap.entrySet()) {
            TypeVariable<?> variable = entry.getKey();
            Type replacement = entry.getValue();
            // A bare `variable -> variable` entry (e.g. a self-bounded type variable the map
            // could not resolve further) is a no-op substitution, not a growth case: it is
            // handled below by replaceTypeVariable's own "leave unresolved variables as-is"
            // branch. Only a replacement that *contains* `variable` somewhere inside a larger
            // structure re-introduces it into returnType, so exclude that trivial identity case
            // from the growth check.
            if (!replacement.equals(variable)
                    && referencesVariable(replacement, variable, typeMap, new HashSet<>(), 0)) {
                replacement = replaceTypeVariablesWithWildcards(replacement);
            }
            returnType = replaceTypeVariable(returnType, variable, replacement);
        }

        return returnType;
    }

    /**
     * Returns whether {@code type} contains {@code variable}, either directly or by following
     * other type variables through {@code typeMap} (so e.g. with {@code typeMap = {S: Sequence<T>,
     * T: Something<S>}}, {@code referencesVariable(Something<S>, T, typeMap, ...)} is {@code true}
     * because {@code S} maps to something containing {@code T}). {@code visiting} guards against
     * cycles purely among the map's own keys (e.g. {@code A -> B, B -> A}) that do not involve
     * {@code variable}, and {@code depth} bounds the structural descent the same way {@link
     * #replaceTypeVariable(Type, TypeVariable, Type, int)} does, in case the type itself is
     * pathologically deep.
     */
    private static boolean referencesVariable(Type type, TypeVariable<?> variable,
                                               Map<TypeVariable<?>, Type> typeMap,
                                               Set<TypeVariable<?>> visiting, int depth) {
        if (type == null || depth > Properties.MAX_GENERIC_DEPTH) {
            return false;
        }
        if (type instanceof TypeVariable<?>) {
            TypeVariable<?> current = (TypeVariable<?>) type;
            if (current.equals(variable)) {
                return true;
            }
            if (!visiting.add(current)) {
                // Already following this variable elsewhere on the current chain: a cycle among
                // other variables that does not (so far) lead back to `variable`.
                return false;
            }
            try {
                Type mapped = typeMap.get(current);
                return mapped != null && referencesVariable(mapped, variable, typeMap, visiting, depth + 1);
            } finally {
                visiting.remove(current);
            }
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                if (referencesVariable(argument, variable, typeMap, visiting, depth + 1)) {
                    return true;
                }
            }
            return referencesVariable(parameterizedType.getOwnerType(), variable, typeMap, visiting, depth + 1);
        }
        if (type instanceof GenericArrayType) {
            return referencesVariable(((GenericArrayType) type).getGenericComponentType(), variable,
                    typeMap, visiting, depth + 1);
        }
        if (type instanceof WildcardType) {
            WildcardType wildcardType = (WildcardType) type;
            for (Type bound : wildcardType.getUpperBounds()) {
                if (referencesVariable(bound, variable, typeMap, visiting, depth + 1)) {
                    return true;
                }
            }
            for (Type bound : wildcardType.getLowerBounds()) {
                if (referencesVariable(bound, variable, typeMap, visiting, depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /**
     * Replaces type variables with wildcards in the target type.
     *
     * @param targetType the target type
     * @return the type with wildcards
     */
    public static Type replaceTypeVariablesWithWildcards(Type targetType) {
        return replaceTypeVariablesWithWildcards(targetType, 0);
    }

    /**
     * Structural-recursion-bounded implementation of {@link #replaceTypeVariablesWithWildcards(Type)}.
     *
     * <p>Same rationale and cap as {@link #replaceTypeVariable(Type, TypeVariable, Type, int)}:
     * this descends into {@code targetType}'s nested ParameterizedType structure (owner and actual
     * type arguments) with no base case other than running out of structure, so a type built from
     * chained bounded type parameters that reference each other (F-bounded polymorphism) can make
     * that descent effectively unbounded. This is on the same hot path as that method --
     * {@link org.evosuite.utils.generic.GenericClassImpl#satisfiesBoundaries(TypeVariable, Map)}
     * calls it once per candidate while {@code CastClassManager} scans the cast-class pool -- so we
     * cap it identically at {@link Properties#MAX_GENERIC_DEPTH}, returning the type as-is (type
     * variables intact) once exceeded rather than recursing further.
     */
    private static Type replaceTypeVariablesWithWildcards(Type targetType, int depth) {
        if (depth > Properties.MAX_GENERIC_DEPTH) {
            return targetType;
        }
        if (targetType instanceof TypeVariable) {
            TypeVariable<?> typeVariable = (TypeVariable<?>) targetType;
            return new WildcardTypeImpl(typeVariable.getBounds(), new Type[]{});
        } else if (targetType instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) targetType;
            Type owner = null;
            if (parameterizedType.getOwnerType() != null) {
                owner = replaceTypeVariablesWithWildcards(parameterizedType.getOwnerType(), depth + 1);
            }
            Type[] currentParameters = parameterizedType.getActualTypeArguments();
            Type[] parameters = new Type[currentParameters.length];
            for (int i = 0; i < parameters.length; i++) {
                parameters[i] = replaceTypeVariablesWithWildcards(currentParameters[i], depth + 1);
            }
            return new ParameterizedTypeImpl((Class<?>) parameterizedType.getRawType(),
                    parameters, owner);
        }
        return targetType;
    }

    /**
     * Replaces a specific type variable with the given type in the target type.
     *
     * @param targetType   the target type
     * @param variable     the type variable to replace
     * @param variableType the replacement type
     * @return the new type
     */
    public static Type replaceTypeVariable(Type targetType, TypeVariable<?> variable,
                                           Type variableType) {
        return replaceTypeVariable(targetType, variable, variableType, 0);
    }

    /**
     * Structural-recursion-bounded implementation of {@link #replaceTypeVariable(Type, TypeVariable, Type)}.
     *
     * <p>{@code depth} tracks how deep we have descended into {@code targetType}'s nested
     * ParameterizedType/GenericArrayType/WildcardType structure for this single substitution.
     * Self-referential / F-bounded type variables (e.g. {@code S extends Sequence<T>} chained with
     * further bounded type parameters that reference {@code S} back) can make that structure
     * effectively unbounded, which previously showed up as the GA thread recursing 10-15+ frames
     * deep in this method. We cap the descent at {@link Properties#MAX_GENERIC_DEPTH}, the same
     * constant {@link GenericClassImpl#getGenericInstantiation(Map, int)} uses to bound generic
     * instantiation depth, and simply stop substituting (returning the type as-is, variables and
     * wildcards intact) once it is exceeded, rather than recursing further.
     */
    private static Type replaceTypeVariable(Type targetType, TypeVariable<?> variable,
                                            Type variableType, int depth) {
        if (depth > Properties.MAX_GENERIC_DEPTH) {
            return targetType;
        }
        if (targetType instanceof Class<?>) {
            return targetType;
        } else if (targetType instanceof GenericArrayType) {
            GenericArrayType arrayType = (GenericArrayType) targetType;
            Type componentType = replaceTypeVariable(arrayType.getGenericComponentType(),
                    variable, variableType, depth + 1);
            return GenericArrayTypeImpl.createArrayType(componentType);

        } else if (targetType instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) targetType;
            Type ownerType = null;
            if (parameterizedType.getOwnerType() != null) {
                ownerType = replaceTypeVariable(parameterizedType.getOwnerType(), variable,
                        variableType, depth + 1);
            }
            Type[] originalParameterTypes = parameterizedType.getActualTypeArguments();
            Type[] parameterTypes = new Type[originalParameterTypes.length];
            for (int i = 0; i < originalParameterTypes.length; i++) {
                parameterTypes[i] = replaceTypeVariable(originalParameterTypes[i],
                        variable, variableType, depth + 1);
            }

            /*
            if (variableType instanceof ParameterizedType) {
                ParameterizedType parameterizedVars = (ParameterizedType) variableType;
                Map<TypeVariable<?>, Type> subTypes = TypeUtils.getTypeArguments(parameterizedVars);
                for (Entry<TypeVariable<?>, Type> subTypeEntry : subTypes.entrySet()) {
                    if (pType.getOwnerType() != null) {
                        ownerType = replaceTypeVariable(pType.getOwnerType(),
                                                        subTypeEntry.getKey(),
                                                        subTypeEntry.getValue());
                    }
                    for (int i = 0; i < originalParameterTypes.length; i++) {
                        parameterTypes[i] = replaceTypeVariable(originalParameterTypes[i],
                                                                subTypeEntry.getKey(),
                                                                subTypeEntry.getValue());
                    }

                }
            }
            */

            return new ParameterizedTypeImpl((Class<?>) parameterizedType.getRawType(),
                    parameterTypes, ownerType);

        } else if (targetType instanceof WildcardType) {
            WildcardType wildcardType = (WildcardType) targetType;
            Type[] originalUpperBounds = wildcardType.getUpperBounds();
            Type[] originalLowerBounds = wildcardType.getLowerBounds();
            Type[] upperBounds = new Type[originalUpperBounds.length];
            Type[] lowerBounds = new Type[originalLowerBounds.length];

            for (int i = 0; i < originalUpperBounds.length; i++) {
                upperBounds[i] = replaceTypeVariable(originalUpperBounds[i], variable,
                        variableType, depth + 1);
            }
            for (int i = 0; i < originalLowerBounds.length; i++) {
                lowerBounds[i] = replaceTypeVariable(originalLowerBounds[i], variable,
                        variableType, depth + 1);
            }

            return new WildcardTypeImpl(upperBounds, lowerBounds);
        } else if (targetType instanceof TypeVariable<?>) {
            if (targetType.equals(variable)) {
                //logger.debug("Do equal: " + variable + "/" + targetType);
                return variableType;
            } else {
                //logger.debug("Do not equal: " + variable + "/" + targetType);
                //logger.debug("Do not equal: " + variable.getGenericDeclaration() + "/"
                //        + ((TypeVariable<?>) targetType).getGenericDeclaration());
                return targetType;
            }
        } else {
            //logger.debug("Unknown type of class " + targetType.getClass() + ": "
            //        + targetType);
            return targetType;
        }
    }

    /**
     * Returns a map of matching type parameters between two generic array types.
     *
     * @param p1 the first generic array type
     * @param p2 the second generic array type
     * @return the map of matching type parameters
     */
    public Map<TypeVariable<?>, Type> getMatchingTypeParameters(GenericArrayType p1,
                                                                GenericArrayType p2) {
        if (p1.getGenericComponentType() instanceof ParameterizedType
                && p2.getGenericComponentType() instanceof ParameterizedType) {
            return getMatchingTypeParameters((ParameterizedType) p1.getGenericComponentType(),
                    (ParameterizedType) p2.getGenericComponentType());
        } else {
            Map<TypeVariable<?>, Type> map = new HashMap<>();
            return map;
        }
    }

    /**
     * TODO: Try to match p2 superclasses.
     *
     * @param p1 Desired TypeVariable assignment
     * @param p2 Generic type with the TypeVariables that need assignment
     * @return a map of matching type parameters
     */
    public static Map<TypeVariable<?>, Type> getMatchingTypeParameters(
            ParameterizedType p1, ParameterizedType p2) {
        logger.debug("Matching generic types between " + p1 + " and " + p2);
        Map<TypeVariable<?>, Type> map = new HashMap<>();
        if (!p1.getRawType().equals(p2.getRawType())) {
            logger.debug("Raw types do not match!");

            GenericClass<?> ownerClass = GenericClassFactory.get(p2);

            if (GenericClassUtils.isSubclass(p1.getRawType(), p2.getRawType())) {
                logger.debug(p1 + " is a super type of " + p2);
                Map<TypeVariable<?>, Type> commonsMap =
                        TypeUtils.determineTypeArguments((Class<?>) p2.getRawType(), p1);
                logger.debug("Adding to map: " + commonsMap);
                // TODO: Now we would need to iterate over the type parameters, and update the map?
                //map.putAll(commonsMap);

                for (TypeVariable<?> t : map.keySet()) {
                    logger.debug(t + ": " + t.getGenericDeclaration());
                }

                // For each type variable of the raw type, map the parameter type to that type
                Type[] p2TypesA = ((Class<?>) p2.getRawType()).getTypeParameters();
                Type[] p2TypesB = p2.getActualTypeArguments();
                for (int i = 0; i < p2TypesA.length; i++) {
                    Type a = p2TypesA[i];
                    Type b = p2TypesB[i];
                    logger.debug("Should be mapping " + a + " and " + b);
                    if (a instanceof TypeVariable<?>) {
                        logger.debug(a + " is a type variable: "
                                + ((TypeVariable<?>) a).getGenericDeclaration());
                        if (b instanceof TypeVariable<?>) {
                            logger.debug(b + " is a type variable: "
                                    + ((TypeVariable<?>) b).getGenericDeclaration());
                            if (commonsMap.containsKey(a)
                                    && !(commonsMap.get(a) instanceof WildcardType)
                                    && !(commonsMap.get(a) instanceof TypeVariable<?>)) {
                                map.put((TypeVariable<?>) b, commonsMap.get(a));
                            }
                            //else
                            //  map.put((TypeVariable<?>)a, b);
                        }
                    }

                    //                  if(b instanceof TypeVariable<?>) {
                    //                      if(map.containsKey(a))
                    //                          map.put((TypeVariable<?>)b, map.get(a));
                    //                      //else
                    //                      //  map.put((TypeVariable<?>)b, a);
                    //                  }

                    logger.debug("Updated map: " + stableTypeVariableMapToString(map));
                }

            }


            for (GenericClass<?> interfaceClass : ownerClass.getInterfaces()) {
                if (interfaceClass.isParameterizedType()) {
                    map.putAll(getMatchingTypeParameters(p1, (ParameterizedType) interfaceClass.getType()));
                } else {
                    logger.debug("Interface " + interfaceClass + " is not parameterized");
                }
            }
            if (ownerClass.getRawClass().getSuperclass() != null) {
                GenericClass<?> ownerSuperClass = ownerClass.getSuperClass();
                if (ownerSuperClass.isParameterizedType()) {
                    map.putAll(getMatchingTypeParameters(p1, (ParameterizedType) ownerSuperClass.getType()));
                } else {
                    logger.debug("Super type " + ownerSuperClass + " is not parameterized");
                }
            }
            return map;
        }

        for (int i = 0; i < p1.getActualTypeArguments().length; i++) {
            Type t1 = p1.getActualTypeArguments()[i];
            Type t2 = p2.getActualTypeArguments()[i];
            if (t1 == t2) {
                continue;
            }
            logger.debug("First match: " + t1 + " - " + t2);
            if (t1 instanceof TypeVariable<?>) {
                map.put((TypeVariable<?>) t1, t2);
            }
            if (t2 instanceof TypeVariable<?>) {
                map.put((TypeVariable<?>) t2, t1);
            } else if (t2 instanceof ParameterizedType && t1 instanceof ParameterizedType) {
                map.putAll(getMatchingTypeParameters((ParameterizedType) t1,
                        (ParameterizedType) t2));
            }
            logger.debug("Updated map: " + stableTypeVariableMapToString(map));

        }

        if (p1.getOwnerType() != null && p1.getOwnerType() instanceof ParameterizedType
                && p2.getOwnerType() instanceof ParameterizedType) {
            map.putAll(getMatchingTypeParameters((ParameterizedType) p1.getOwnerType(),
                    (ParameterizedType) p2.getOwnerType()));
        }

        return map;
    }

    /**
     * Returns true if the annotation is present in the annotationList, false otherwise.
     *
     * @param annotationList     the list of annotations
     * @param annotationTypeName the name of the annotation type
     * @return boolean
     */
    public static boolean isAnnotationTypePresent(Annotation[] annotationList, String annotationTypeName) {
        for (Annotation annotation : annotationList) {

            if ((null != annotationTypeName)
                    && annotationTypeName.equalsIgnoreCase(annotation.annotationType().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Like {@link GenericTypeReflector#getExactSuperType}, but without its treatment of a static
     * nested class of a generic outer class as raw. gentyref's raw check walks the enclosing
     * classes without looking at {@code static}, so for {@code DefaultContig.Builder extends
     * AbstractContigBuilder<AssembledRead, ...>} it answers the erased
     * {@code AbstractContigBuilder}, and every parameter that mentions the superclass's type
     * variable ({@code Iterable<P>}) is erased with it.
     */
    public static Type getExactSuperType(Type type, Class<?> searchClass) {
        Type exact = GenericTypeReflector.getExactSuperType(type, searchClass);
        if (!(exact instanceof Class<?>) || searchClass.getTypeParameters().length == 0
                || !(type instanceof Class<?>)) {
            return exact;
        }
        Class<?> clazz = (Class<?>) type;
        if (clazz.getTypeParameters().length != 0 || !clazz.isMemberClass()
                || !Modifier.isStatic(clazz.getModifiers())) {
            return exact;
        }
        Map<TypeVariable<?>, Type> arguments = TypeUtils.getTypeArguments(clazz, searchClass);
        if (arguments == null) {
            return exact;
        }
        for (TypeVariable<?> variable : searchClass.getTypeParameters()) {
            if (arguments.get(variable) == null) {
                return exact;
            }
        }
        return TypeUtils.parameterize(searchClass, arguments);
    }
}
