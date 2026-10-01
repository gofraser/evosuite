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

import com.googlecode.gentyref.TypeToken;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Deterministic unit test for the {@code VariableResolver.tryCreateStandardContainer} half of
 * commit 3f7bb5186 ("Bind owner type variables before satisfying generic call parameters").
 *
 * <p>Before that fix, {@code tryCreateStandardContainer} always wrapped the chosen implementation
 * class (eg, {@code LinkedHashSet}) in a raw {@code GenericConstructor}, so the resulting
 * statement declared a raw {@code LinkedHashSet} rather than {@code LinkedHashSet<String>}: an
 * unbound {@code LinkedHashSet<E>} is unchecked-assignable to a {@code Set<X>} request for any
 * {@code X}, which is exactly the hole that let EvoSuite pass a {@code LinkedHashSet<E>} as both
 * a {@code List<Integer>} and a {@code Set<String>} parameter of the same constructor call
 * (Defects4J Collections-21). This test calls the resolver directly, bypassing the mutation
 * machinery that {@link TestMutatorGenericConstructorTest} goes through -- that test offers
 * several self-consistent List/Set pairs for the mutator to pick from and does not fail when the
 * owner-binding fix is reverted, because an unbound pick can still land on a self-consistent pair
 * by chance; this test has only one implementation to pick and asserts its type argument
 * directly, so it cannot pass by accident.</p>
 */
class VariableResolverGenericContainerTest {

    @Test
    void standardContainerDeclaresParameterizedOwnerTypeNotRaw() throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        VariableResolver resolver = new VariableResolver(TestFactory.getInstance());

        Type requestedSetOfString = new TypeToken<Set<String>>() {
        }.getType();

        VariableResolutionConfig config = new VariableResolutionConfig.Builder()
                .withAllowNull(false)
                .withCanUseMocks(false)
                .withCanReuseExistingVariables(false)
                .withPreferConcreteGenerators(true)
                .build();

        VariableReference result = resolver.createObject(
                test, requestedSetOfString, 0, GenerationContext.fromDepth(0), config);

        Statement created = test.getStatement(result.getStPosition());
        assertInstanceOf(ConstructorStatement.class, created,
                "the standard-container fallback should construct the implementation directly: "
                        + test.toCode());
        ConstructorStatement constructorStatement = (ConstructorStatement) created;

        assertEquals(LinkedHashSet.class, constructorStatement.getConstructor().getRawGeneratedType());

        Type ownerType = constructorStatement.getConstructor().getOwnerType();
        assertInstanceOf(ParameterizedType.class, ownerType,
                "LinkedHashSet's owner type must be parameterized with String, not left raw -- "
                        + "an unbound LinkedHashSet<E> is unchecked-assignable to a Set<X> request "
                        + "for any X, which is the Collections-21 bug: " + test.toCode());
        ParameterizedType parameterizedOwnerType = (ParameterizedType) ownerType;
        assertEquals(1, parameterizedOwnerType.getActualTypeArguments().length);
        assertEquals(String.class, parameterizedOwnerType.getActualTypeArguments()[0]);

        // Belt-and-braces: the returned variable's own declared type must carry the same
        // String argument, not a raw/unbound LinkedHashSet.
        Type resultType = result.getGenericClass().getType();
        assertInstanceOf(ParameterizedType.class, resultType);
        assertEquals(String.class, ((ParameterizedType) resultType).getActualTypeArguments()[0]);
    }
}
