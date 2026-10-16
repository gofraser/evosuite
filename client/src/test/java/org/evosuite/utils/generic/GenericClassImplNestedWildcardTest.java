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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.HashMap;
import java.util.Map;

/**
 * A wildcard nested inside a type argument that is copied verbatim from the requested type
 * (the {@code ?} in {@code Map<TypeVariable<?>, Type>}) must stay a wildcard: generics are
 * invariant, so {@code HashMap<TypeVariable<Method>, Type>} would not compile there.
 */
public class GenericClassImplNestedWildcardTest {

    @SuppressWarnings("unused")
    private interface Signatures {
        void mapOfWildcardTypeVariable(Map<TypeVariable<?>, Type> typeVarAssigns);

        void mapOfMethodTypeVariable(Map<TypeVariable<Method>, Type> typeVarAssigns);

        void hashMapOfMethodTypeVariable(HashMap<TypeVariable<Method>, Type> typeVarAssigns);
    }

    private static Type parameterType(String methodName) throws NoSuchMethodException {
        for (Method method : Signatures.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method.getGenericParameterTypes()[0];
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    /**
     * The path TestCluster takes when adapting the HashMap generator to a requested type.
     */
    @Test
    public void nestedWildcardInInvariantTypeArgumentIsPreserved() throws Exception {
        ParameterizedType requestedType = (ParameterizedType) parameterType("mapOfWildcardTypeVariable");
        GenericClass<?> requested = new GenericClassImpl(requestedType);

        GenericClass<?> hashMapGenerator = new GenericClassImpl(HashMap.class);
        GenericClass<?> instantiated = hashMapGenerator.getWithParametersFromSuperclass(requested);

        Assertions.assertEquals(HashMap.class, instantiated.getRawClass());
        Assertions.assertTrue(instantiated.getType() instanceof ParameterizedType,
                "Expected a parameterized HashMap<..., ...>, got: " + instantiated.getTypeName());

        Type keyArgument = ((ParameterizedType) instantiated.getType()).getActualTypeArguments()[0];
        Assertions.assertTrue(keyArgument instanceof ParameterizedType,
                "Expected the key argument to still be TypeVariable<...>, got: " + keyArgument);
        Type nestedWildcard = ((ParameterizedType) keyArgument).getActualTypeArguments()[0];
        Assertions.assertTrue(nestedWildcard instanceof WildcardType,
                "Nested wildcard was resolved to a concrete class: " + instantiated.getTypeName());

        Assertions.assertTrue(GenericClassUtils.isAssignable(requestedType, instantiated.getType()),
                instantiated.getTypeName() + " is not assignable to " + requested.getTypeName());
    }

    @Test
    public void mapOfConcreteTypeVariableIsNotAssignableToMapOfWildcardTypeVariable() throws Exception {
        Type requested = parameterType("mapOfWildcardTypeVariable");
        Type concrete = parameterType("hashMapOfMethodTypeVariable");

        Assertions.assertFalse(GenericClassUtils.isAssignable(requested, concrete),
                "HashMap<TypeVariable<Method>, Type> must not be assignable to Map<TypeVariable<?>, Type>");
    }
}
