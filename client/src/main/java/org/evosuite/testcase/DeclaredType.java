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

import com.googlecode.gentyref.GenericTypeReflector;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericUtils;

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

import static org.evosuite.setup.GeneratedTestAccess.isDeclaredTypeNameableInGeneratedTest;

/**
 * How the variable a constructor statement defines is declared in the rendered test. The
 * declaration names the constructor's owner type rather than the variable's own type, so javac
 * sees that type for every later use of the variable, and the cast decisions have to see it as
 * well.
 *
 * <p>The owner type is used so that the declaration always matches the right-hand side
 * {@code new Owner(...)}. The variable's class can diverge from the constructor owner (a
 * java.sql.Date variable with a java.util.Date constructor), and when both simple names collide
 * the import picks one while the other is fully qualified, producing uncompilable code.
 *
 * <p>Whether the constructor is source-accessible (a direct {@code new Owner(...)} or the
 * reflective fallback) is a different question from whether the constructed class can be named
 * at all. A private constructor on an otherwise nameable public class is a common idiom
 * (factory-method-only construction): the constructor needs reflection, but the class is fine
 * to declare and cast to. Only when the class itself cannot be named does the declaration fall
 * back to its nearest accessible supertype.
 */
final class DeclaredType {

    /** The constructor statement that defines the variable. */
    final ConstructorStatement definition;

    /** The class the constructor creates. */
    final Class<?> constructedClass;

    /** A non-static member class, created through an enclosing instance. */
    final boolean inner;

    /** Whether the test can name the constructed class; if not, a supertype is declared. */
    final boolean nameable;

    /** The owner type the declaration names when the constructed class can be named. */
    final Type ownerType;

    /**
     * The variable is written again later with a value of the variable's own type, not of the
     * constructor's (java.io.File built by the MockFile constructor, then given a
     * de.schlichtherle.io.File). Declared as the narrower constructor owner it cannot take that
     * value, so it is declared with its own type.
     */
    final boolean declaredWithVariableType;

    private DeclaredType(ConstructorStatement definition, TestCase test) {
        this.definition = definition;
        GenericConstructor constructor = definition.getConstructor();
        VariableReference retval = definition.getReturnValue();
        constructedClass = constructor.getConstructor().getDeclaringClass();
        inner = constructedClass.isMemberClass()
                && !constructor.isStatic()
                && !Modifier.isStatic(constructedClass.getModifiers());
        nameable = isDeclaredTypeNameableInGeneratedTest(constructedClass);
        Type declarationType = inner
                ? withEnclosingInstanceOwner(constructor.getOwnerType(),
                        definition.getParameterReferences().get(0))
                : rawIfOutsideOfBounds(constructor.getOwnerType());
        if (inner
                && declarationType.equals(constructor.getOwnerType())
                && retval.getVariableClass() == constructedClass
                && (retval.getType() instanceof Class<?>
                || retval.getGenericClass().hasWildcardOrTypeVariables())
                && retval.getGenericClass().isAssignableFrom(declarationType)) {
            // A mutation can bind the constructor to Outer<String>.Inner while retaining a
            // raw or wildcard result variable. Narrowing that declaration changes the types
            // accepted by later calls, such as a raw inner.set(Integer).
            declarationType = retval.getType();
        }
        ownerType = declarationType;
        declaredWithVariableType = !inner
                && constructedClass != retval.getVariableClass()
                && retval.getVariableClass().isAssignableFrom(constructedClass)
                && isReassignedLocal(definition, test)
                && isDeclaredTypeNameableInGeneratedTest(retval.getVariableClass());
    }

    /** The declaration of the variable {@code statement} defines in {@code test}. */
    static DeclaredType of(ConstructorStatement statement, TestCase test) {
        return new DeclaredType(statement, test);
    }

    /**
     * The declaration of {@code variable} when a constructor statement of {@code test} defines
     * it, or null when another kind of statement does or the variable is not found.
     */
    static DeclaredType ofVariable(TestCase test, VariableReference variable) {
        int position = test != null ? test.positionOfReturnValue(variable) : -1;
        if (position < 0) {
            return null;
        }
        Statement definition = test.getStatement(position);
        if (!(definition instanceof ConstructorStatement) || definition.getReturnValue() != variable) {
            return null;
        }
        return new DeclaredType((ConstructorStatement) definition, test);
    }

    /** True when a later statement of the test assigns to the local variable this one defines. */
    private static boolean isReassignedLocal(Statement definition, TestCase test) {
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

    /**
     * The type javac gives {@code outer.new Inner(...)} is {@code Outer<X>.Inner}, where X comes
     * from the declared type of the enclosing instance, not from the constructor model's own
     * owner type (which can carry a different instantiation of the outer's type parameters).
     * Declaring the variable with the model's owner type then fails to compile, so only an
     * owner type that agrees with the enclosing instance keeps its qualifier.
     */
    static Type withEnclosingInstanceOwner(Type ownerType, VariableReference enclosingInstance) {
        Class<?> rawInner = CastPolicy.safeErasure(ownerType);
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

    /**
     * A type argument that implements only the raw form of a parameterized bound (UURI, which
     * is a raw Comparable, for E extends Comparable&lt;E&gt;) is not within the bound for javac,
     * although the search treats the raw interface as satisfying it. Such a type is declared
     * and constructed raw, as no parameterization of it compiles.
     */
    static Type rawIfOutsideOfBounds(Type type) {
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
                        && GenericUtils.getExactSuperType(arguments[i], boundClass)
                        instanceof Class<?>) {
                    return rawClass;
                }
            }
        }
        return type;
    }
}
