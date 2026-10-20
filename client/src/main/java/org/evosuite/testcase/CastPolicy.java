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
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.evosuite.Properties;
import org.evosuite.testcase.statements.ClassPrimitiveStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.UninterpretedStatement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ConstantValue;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericClassUtils;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.GenericUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.evosuite.setup.GeneratedTestAccess.findAccessibleRenderableSupertype;
import static org.evosuite.setup.GeneratedTestAccess.getGeneratedTestPackageName;
import static org.evosuite.setup.GeneratedTestAccess.isDeclaredTypeNameableInGeneratedTest;
import static org.evosuite.setup.GeneratedTestAccess.isEmptyPackageName;
import static org.evosuite.setup.GeneratedTestAccess.isSamePackage;
import static org.evosuite.setup.GeneratedTestAccess.isTypeAccessibleFromGeneratedTest;

/**
 * The cast decisions of {@link TestCodeVisitor}: which casts the arguments of a call need (null
 * arguments of overloaded calls, raw and inconvertible parameterizations), whether the receiver
 * of an instance call is cast and how its result is cast back (bridges, raw receivers, static
 * generic factories), and the cast of a reference assignment.
 *
 * <p>The only visitor state it reaches is through {@link Host}. Every type name it writes comes
 * from the host, which also registers the imports, so the order of those calls is part of the
 * rendered output.</p>
 */
class CastPolicy {

    /**
     * The visitor state the cast decisions read.
     */
    interface Host {
        /**
         * Registers {@code clazz} with the visitor and returns its rendered name.
         */
        String getClassName(Class<?> clazz);

        /**
         * The type name of a variable's declaration.
         */
        String getClassName(VariableReference var);

        String getTypeName(Type type);

        String getVariableName(VariableReference var);

        boolean canUseDirectFieldAccess(Field field, VariableReference source);

        VariableReference normalizeVariableReference(VariableReference var);

        int safePosition(VariableReference variableReference);

        /**
         * The test being rendered, or null outside of a render.
         */
        TestCase test();
    }

    private final Host host;

    CastPolicy(Host host) {
        this.host = host;
    }

    private String getPrimitiveNullCast(Class<?> declaredParamType) {
        String castString = "";
        castString += "(" + host.getTypeName(declaredParamType) + ") ";
        castString += "(" + host.getTypeName(ClassUtils.primitiveToWrapper(declaredParamType))
                + ") ";

        return castString;
    }

    String getParameterString(Type[] parameterTypes,
                              List<VariableReference> parameters, boolean isGenericMethod,
                              boolean isOverloaded, int startPos) {
        return getParameterString(parameterTypes, parameters, isGenericMethod, isOverloaded, startPos, null);
    }

    String getParameterString(Type[] parameterTypes,
                              List<VariableReference> parameters, boolean isGenericMethod,
                              boolean isOverloaded, int startPos, Type ownerType) {
        return getParameterString(parameterTypes, parameters, isGenericMethod, isOverloaded,
                startPos, ownerType, null);
    }

    String getParameterString(Type[] parameterTypes,
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
            String name = host.getVariableName(parameters.get(i));
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
                parameterString += "(" + host.getClassName(
                        (Class<?>) ((ParameterizedType) declaredParamType).getRawType()) + ") " + name;
                continue;
            }
            if (rawParamClass.isPrimitive() && argumentIsNull) {
                parameterString += getPrimitiveNullCast(rawParamClass);
            } else if (!argumentIsNull && isInconvertibleArrayArgument(declaredParamType, actualParamType)) {
                // "(Foo[]) stringArray" is rejected by javac; the argument is passed as it is
                // and the call result is cast raw (see requiresRawReturnCastForArrayArgument).
            } else if (rawClassLiteralArgument) {
                parameterString += "(" + host.getTypeName(Class.class) + ") ";
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
                parameterString += "(" + host.getTypeName(Class.class) + ") ";
            } else if (isOverloaded && !argumentIsNull && parameters.get(i).isPrimitive()
                    && isBoxedByReferenceParameter(rawParamClass, parameters.get(i).getVariableClass())) {
                // A primitive handed to a reference parameter is only boxed in javac's second
                // resolution phase, so a sibling overload taking the primitive wins: with
                // remove(Object) and remove(int), "list.remove(intValue)" calls remove(int).
                // The wrapper cast boxes in the first phase, binds the reference overload, and
                // stays valid for a type variable bound to that wrapper.
                parameterString += "(" + host.getClassName(ClassUtils.primitiveToWrapper(
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
                            parameterString += "(" + host.getTypeName(declaredParamType)
                                    + ") ";
                        }
                    } else {
                        // if (!GenericClass.isAssignable(GenericTypeReflector.getArrayComponentType(declaredParamType),
                        // GenericTypeReflector.getArrayComponentType(actualParamType))) {
                        parameterString += "(" + host.getTypeName(declaredParamType) + ") ";
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
                    parameterString += "(" + host.getTypeName(declaredParamType) + ") ";
                } else if (parameterClass.isPrimitive()
                        && parameters.get(i).isWrapperType()) {
                    parameterString += "(" + host.getTypeName(declaredParamType) + ") ";
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
            return host.getTypeName(erasedType);
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
            return host.getClassName((Class<?>) ((ParameterizedType) declaredParamType).getRawType());
        }
        return host.getTypeName(declaredParamType);
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
        String rendered = host.getVariableName(parameter);
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

    boolean requiresNullArgumentDisambiguation(Constructor<?> constructor,
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

    boolean requiresNullArgumentDisambiguation(Method method,
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
        if (reference == null || host.test() == null) {
            return false;
        }
        int pos = host.safePosition(reference);
        if (pos < 0 || pos >= host.test().size()) {
            return false;
        }
        Statement statement = host.test().getStatement(pos);
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
                return host.getTypeName(findAccessibleGenericSupertype(erased, genericParameterType));
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
            return host.getTypeName(Class.class);
        }
        if (requiresRawCastForInconvertibleParameterizedTypes(declaredParamType, actualParamType)) {
            return host.getTypeName(safeErasure(declaredParamType));
        }
        if (requiresRawCastTypeName(declaredParamType)) {
            return host.getTypeName(safeErasure(declaredParamType));
        }
        return host.getTypeName(declaredParamType);
    }

    boolean requiresRawCastForInconvertibleParameterizedTypes(Type declaredParamType, Type actualParamType) {
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
    boolean requiresRawClassCast(Type declaredType, Type actualType) {
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
                || host.test() == null || source == null) {
            return false;
        }
        // A value that only exists in the rendering (eg the null a pending mock stub is answered
        // with) is defined by no statement and has no position; it is not a class literal.
        int position = host.safePosition(source);
        if (position < 0 || position >= host.test().size()
                || !(host.test().getStatement(position) instanceof ClassPrimitiveStatement)
                || host.test().getStatement(position).getReturnValue() != source) {
            return false;
        }
        Type[] arguments = ((ParameterizedType) targetType).getActualTypeArguments();
        return arguments.length == 1 && arguments[0] instanceof ParameterizedType;
    }

    boolean isUnresolvedTypeVariableOrWildcard(Type type) {
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

    /** The cast in front of a call whose result is assigned to a variable. */
    static final class ReturnCast {
        enum Kind { NONE, TYPED, RAW }

        static final ReturnCast NONE = new ReturnCast(Kind.NONE, null);

        final Kind kind;
        /** The name written in the cast: the variable's type, or its raw class. */
        final String typeName;

        ReturnCast(Kind kind, String typeName) {
            this.kind = kind;
            this.typeName = typeName;
        }
    }

    /**
     * Whether the result of a call needs a cast to be assigned to its variable, and which. A typed
     * cast is needed when javac sees a return type the variable's type cannot take without one;
     * a raw cast, unchecked but legal, when javac sees a parameterization no typed cast converts.
     * Either can be turned into a raw cast by a return type that is generic-inconvertible with
     * the variable's type.
     */
    ReturnCast returnCastFor(MethodStatement statement, boolean isGenericMethod,
                             boolean forceRawReceiverInvocation, Class<?> receiverCastClass,
                             Type[] renderedParameterTypes) {
        VariableReference retval = statement.getReturnValue();
        GenericMethod method = statement.getMethod();
        List<VariableReference> parameters = statement.getParameterReferences();
        // A cast gives javac no target type to infer a zero-argument static generic call from.
        boolean zeroArgumentStaticGenericCall = isGenericMethod
                && method.getParameterTypes().length == 0 && method.isStatic();
        boolean typedCast = (!retval.isAssignableFrom(method.getReturnType())
                || requiresReturnValueCastForRawReceiver(statement, method, retval)
                || requiresReturnCastDueToRawParameterCast(
                        isGenericMethod, renderedParameterTypes, parameters, method, retval)
                || (forceRawReceiverInvocation
                && dependsOnGenericTypeInformation(method.getMethod().getGenericReturnType()))
                || requiresReturnCastForRawReceiverCast(receiverCastClass, method, retval)
                || requiresStaticGenericReturnCast(method, retval))
                && !retval.getVariableClass().isAnonymousClass()
                && !zeroArgumentStaticGenericCall;
        boolean rawCast = (method.isStatic()
                ? zeroArgumentStaticGenericCall && uninferableStaticGenericReturn(method, retval)
                : returnDependsOnRedeclaredReceiver(statement, method, retval))
                || (!retval.isVoid() && retval.getType() instanceof ParameterizedType
                && requiresRawReturnCastForArrayArgument(renderedParameterTypes, parameters));
        if (!typedCast && !rawCast) {
            return ReturnCast.NONE;
        }
        String name = getAccessibleGenericReturnTypeName(method, retval);
        // An anonymous class (Outer.1) cannot be named in a cast. This tests the rendered name, not
        // the class: a class rendered as an accessible supertype is cast to that supertype, and a
        // local class is cast to its rendered name as before.
        if (name.matches(".*\\.\\d+$")) {
            return ReturnCast.NONE;
        }
        if (retval.getType() instanceof ParameterizedType
                && (rawCast || returnCastIsInconvertible(retval.getType(),
                        method.getMethod().getGenericReturnType()))) {
            // The declared parameterization is generic-inconvertible with the
            // method's return type -- typically a variable typed
            // Set<Class<Object>> receiving a raw Set<Class> return (old
            // Mockito-internal APIs). "(Set<Class<Object>>) x" does not
            // compile; a raw cast is an unchecked but legal conversion that
            // still assigns to the parameterized variable.
            return new ReturnCast(ReturnCast.Kind.RAW, host.getClassName(
                    (Class<?>) ((ParameterizedType) retval.getType()).getRawType()));
        }
        return new ReturnCast(ReturnCast.Kind.TYPED, name);
    }

    /** The class the receiver of an instance call is cast to, and whether a space follows the cast. */
    static final class ReceiverCast {
        final Class<?> target;
        final boolean spaced;

        ReceiverCast(Class<?> target, boolean spaced) {
            this.target = target;
            this.spaced = spaced;
        }
    }

    /**
     * {@code ((Target) callee)}, through {@code (Object)} when the callee's class is unrelated to
     * the target, as javac rejects a direct cast between unrelated classes. The space after the
     * cast differs between the reasons for the cast, and is kept as it is.
     */
    String castReceiver(ReceiverCast cast, VariableReference callee) {
        String targetName = host.getClassName(cast.target);
        String calleeName = host.getVariableName(callee);
        Class<?> calleeClass = callee.getVariableClass();
        if (calleeClass != null && !cast.target.isAssignableFrom(calleeClass)) {
            return "((" + targetName + ")(Object)" + calleeName + ")";
        }
        return "((" + targetName + ")" + (cast.spaced ? " " : "") + calleeName + ")";
    }

    /**
     * The class the receiver of an instance call has to be viewed as for javac to bind the call
     * to the method the test executed, or null when the receiver is written as it is. The first
     * reason that applies decides.
     */
    ReceiverCast receiverCastTarget(MethodStatement statement, boolean forceRawReceiverInvocation) {
        GenericMethod method = statement.getMethod();
        VariableReference callee = statement.getCallee();
        Class<?> declaringClass = method.getMethod().getDeclaringClass();
        Class<?> bridgeSource;
        if (forceRawReceiverInvocation) {
            return new ReceiverCast(declaringClass, false);
        }
        if (!(callee instanceof ConstantValue)
                && (bridgeSource = bridgeSourceDeclaringClass(method.getMethod())) != null) {
            // The search called a synthetic bridge, typically to feed an argument of the
            // wrong type into a generic override. Source code naming the method binds to the
            // override with the specific parameter type; viewing the receiver as the generic
            // declaring class binds to the erased signature the bridge implements.
            return new ReceiverCast(bridgeSource, true);
        }
        if (!(callee instanceof ConstantValue)
                && (bridgeSource = genericDeclaringClassOverriddenByBridge(method.getMethod(),
                callee.getVariableClass())) != null) {
            // The generic method itself was called on a subclass receiver whose override
            // narrows the parameter type (ResourceTrackerImpl<T>.calculateBytes(T) on a
            // StringTracker, argument not a String). Source code naming the method on the
            // subclass binds to the override; the raw generic class binds to this method.
            return new ReceiverCast(bridgeSource, true);
        }
        if (callee instanceof ConstantValue) {
            // A constant is rendered as its literal, which is viewed as the declaring class
            // (through Object when unrelated, e.g. (Target)(Object)"str").
            return new ReceiverCast(declaringClass, false);
        }
        if (isShadowedByInaccessibleOverride(method.getMethod(), callee.getVariableClass())) {
            // The receiver's static type overrides this protected/package-private method in
            // another package, where javac resolves the call and finds it inaccessible.
            // Viewing the receiver as the declaring class (which is in the test's package)
            // resolves it there; the call still dispatches to the override at run time.
            return new ReceiverCast(declaringClass, true);
        }
        // Only a package-private method can become accessible through this cast. The
        // declaring package must also be the package of the generated test.
        if (method.isDefault() && !method.getDeclaringClass().equals(callee.getVariableClass())
                && callee.isAssignableTo(declaringClass)) {
            String packageName1 = ClassUtils.getPackageName(method.getDeclaringClass());
            String packageName2 = ClassUtils.getPackageName(callee.getVariableClass());
            if (packageName1.equals(Properties.CLASS_PREFIX)
                    && !packageName1.equals(packageName2)) {
                return new ReceiverCast(declaringClass, false);
            }
            return null;
        }
        boolean calleeDeclaresMethod = hasMethodBySignatureName(callee.getVariableClass(), method.getMethod());
        if (calleeDeclaresMethod && callee.isAssignableTo(declaringClass)) {
            return null;
        }
        try {
            // If the concrete callee class has that method then it's ok
            callee.getVariableClass().getDeclaredMethod(method.getName(), method.getRawParameterTypes());
            return null;
        } catch (NoSuchMethodException | NoClassDefFoundError e) {
            // If not we need to cast to the declaring class (through Object when the callee is
            // unrelated, to keep the code compilable).
            return new ReceiverCast(declaringClass, true);
        }
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
    Type[] preserveConcreteGenericParameterDeclarations(GenericMethod method,
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

    boolean shouldForceRawReceiverInvocation(MethodStatement statement,
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
    Type declaredVariableType(TestCase testCase, VariableReference callee) {
        int calleePosition = testCase != null ? testCase.positionOfReturnValue(callee) : -1;
        if (calleePosition >= 0) {
            Statement definition = testCase.getStatement(calleePosition);
            if (definition instanceof ConstructorStatement
                    && definition.getReturnValue() == callee) {
                GenericConstructor constructor = ((ConstructorStatement) definition).getConstructor();
                Class<?> declaringClass = constructor.getConstructor().getDeclaringClass();
                if (constructor.getOwnerType() instanceof ParameterizedType
                        && !(declaringClass.isMemberClass() && !Modifier.isStatic(declaringClass.getModifiers()))
                        && isDeclaredTypeNameableInGeneratedTest(declaringClass)) {
                    return rawIfOutsideOfBounds(constructor.getOwnerType());
                }
                // An inner class whose model owner disagrees with the enclosing instance is
                // declared raw, and javac erases the members of a raw inner class.
                if (constructor.getOwnerType() instanceof ParameterizedType
                        && isInnerClassOfGenericClass(declaringClass)
                        && isDeclaredTypeNameableInGeneratedTest(declaringClass)
                        && withEnclosingInstanceOwner(constructor.getOwnerType(),
                        ((ConstructorStatement) definition).getParameterReferences().get(0))
                        instanceof Class<?>) {
                    return declaringClass;
                }
            }
        }
        return callee.getType();
    }

    /** Whether {@code type} is a non-static member of a class with type parameters, at any depth. */
    private static boolean isInnerClassOfGenericClass(Class<?> type) {
        Class<?> current = type;
        while (current.isMemberClass() && !Modifier.isStatic(current.getModifiers())) {
            current = current.getDeclaringClass();
            if (current.getTypeParameters().length > 0) {
                return true;
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

    boolean isDefinitelyInconvertibleReferenceCast(Type declaredType, Type actualType) {
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
                return host.getTypeName(findAccessibleGenericSupertype(returnedClass,
                        method.getMethod().getGenericReturnType()));
            }
        }
        return host.getClassName(retval);
    }

    String getMethodReturnDeclarationType(GenericMethod method, VariableReference retval) {
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

        return host.getTypeName(method.getMethod().getReturnType());
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
            String argName = host.getVariableName(argRef);
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
    Type rawIfOutsideOfBounds(Type type) {
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
        Class<?> constructedClass = nonGenericConstructedClass(receiver);
        if (constructedClass != null) {
            receiverClass = constructedClass;
        }
        if (receiverClass.getTypeParameters().length > 0) {
            return host.getClassName(receiver).equals(host.getClassName(receiverClass))
                    || isReadThroughRawReflectiveCast(receiver)
                    // A constructor's variable is declared with the constructor's owner type,
                    // which is raw when an argument is outside its bounds.
                    || (!(receiver instanceof FieldReference) && !(receiver instanceof ArrayIndex)
                    && declaredVariableType(host.test() != null ? host.test() : receiver.getTestCase(),
                            host.normalizeVariableReference(receiver)) instanceof Class<?>);
        }
        // Outer.Inner without the outer's type arguments is a raw type as well.
        if (isInnerClassOfGenericClass(receiverClass)
                && !(receiver instanceof FieldReference) && !(receiver instanceof ArrayIndex)
                && declaredVariableType(host.test() != null ? host.test() : receiver.getTestCase(),
                        host.normalizeVariableReference(receiver)) instanceof Class<?>) {
            return true;
        }
        if (declaringClass.getTypeParameters().length == 0
                || !declaringClass.isAssignableFrom(receiverClass)) {
            return false;
        }
        return !(GenericUtils.getExactSuperType(receiverClass, declaringClass)
                instanceof ParameterizedType);
    }

    /**
     * The class a constructor's variable is declared with when that is a class without type
     * parameters other than the variable's own class: a variable the search types as
     * {@code Hashtable<String, String[]>} is declared as the {@code ProviderList} it is
     * constructed from, and javac sees that class's members, not the variable's.
     */
    private Class<?> nonGenericConstructedClass(VariableReference receiver) {
        if (receiver instanceof FieldReference || receiver instanceof ArrayIndex) {
            return null;
        }
        TestCase testCase = host.test() != null ? host.test() : receiver.getTestCase();
        VariableReference normalized = host.normalizeVariableReference(receiver);
        int position = testCase != null ? testCase.positionOfReturnValue(normalized) : -1;
        if (position < 0) {
            return null;
        }
        Statement definition = testCase.getStatement(position);
        if (!(definition instanceof ConstructorStatement) || definition.getReturnValue() != normalized) {
            return null;
        }
        GenericConstructor constructor = ((ConstructorStatement) definition).getConstructor();
        Class<?> declaringClass = constructor.getConstructor().getDeclaringClass();
        if (!(constructor.getOwnerType() instanceof Class<?>)
                || declaringClass.getTypeParameters().length > 0
                || (declaringClass.isMemberClass() && !Modifier.isStatic(declaringClass.getModifiers()))
                || declaringClass == receiver.getVariableClass()
                || !isDeclaredTypeNameableInGeneratedTest(declaringClass)) {
            return null;
        }
        return declaringClass;
    }

    /**
     * A field whose declared type uses the declaring class's type variables reads as its erasure
     * through a receiver that erases it ({@code pair.key} is a {@code Comparable} for a raw
     * {@code FlexiblePair}); a variable or parameter of the resolved type then needs a cast back.
     */
    boolean isFieldReadErasedByRawReceiver(Field field, VariableReference source,
                                           Class<?> targetClass) {
        if (field == null || source == null || targetClass == null
                || Modifier.isStatic(field.getModifiers())
                || !host.canUseDirectFieldAccess(field, source)
                || !dependsOnGenericTypeInformation(field.getGenericType())
                || !receiverErasesMembersOf(source, field.getDeclaringClass())) {
            return false;
        }
        return !targetClass.isAnonymousClass()
                && !ClassUtils.isAssignable(field.getType(), targetClass, true);
    }

    /** {@link #isFieldReadErasedByRawReceiver} for a field reference used as a value. */
    boolean isFieldReferenceErasedByRawReceiver(VariableReference var, Class<?> targetClass) {
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
                && !host.canUseDirectFieldAccess(((FieldReference) callee).getField().getField(),
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
    String getReferenceAssignmentCast(VariableReference retval, VariableReference value) {
        String target = host.getClassName(retval);
        if (parameterizedCastIsInconvertible(retval.getType(), value)) {
            Class<?> rawTarget = (Class<?>) ((ParameterizedType) retval.getType()).getRawType();
            return "(" + target + ")(" + host.getClassName(rawTarget) + ") ";
        }
        return "(" + target + ") ";
    }

    boolean parameterizedCastIsInconvertible(Type targetType, VariableReference value) {
        if (!(targetType instanceof ParameterizedType) || value == null) {
            return false;
        }
        return parameterizedCastIsInconvertible(targetType, value.getType(), value.getVariableClass());
    }

    /** See {@link #parameterizedCastIsInconvertible(Type, VariableReference)}. */
    boolean parameterizedCastIsInconvertible(Type targetType, Type valueType, Class<?> valueClass) {
        if (!(targetType instanceof ParameterizedType)) {
            return false;
        }
        ParameterizedType parameterizedTarget = (ParameterizedType) targetType;
        if (!(parameterizedTarget.getRawType() instanceof Class<?>)) {
            return false;
        }
        Class<?> rawTarget = (Class<?>) parameterizedTarget.getRawType();
        if (valueClass == null || !rawTarget.isAssignableFrom(valueClass)) {
            return false;
        }
        Type valueAsTarget;
        try {
            valueAsTarget = GenericUtils.getExactSuperType(valueType, rawTarget);
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
}
