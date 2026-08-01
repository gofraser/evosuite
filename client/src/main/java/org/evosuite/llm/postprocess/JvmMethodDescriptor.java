/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 */
package org.evosuite.llm.postprocess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.objectweb.asm.Type;

/**
 * Parsed JVM method descriptor used by callable-policy matching.
 *
 * <p>The descriptor text remains available for compatibility, while callers
 * use the parsed parameter and return descriptors instead of repeatedly
 * splitting the same signature.</p>
 */
final class JvmMethodDescriptor {

    private final String descriptor;
    private final List<ExprType> parameterTypes;
    private final ExprType returnType;
    private final boolean valid;

    private JvmMethodDescriptor(String descriptor, Type[] parameterTypes,
                               Type returnType, boolean valid) {
        this.descriptor = descriptor;
        List<ExprType> types = new ArrayList<>();
        for (Type parameterType : parameterTypes) {
            types.add(typeOf(parameterType));
        }
        this.parameterTypes = Collections.unmodifiableList(types);
        this.returnType = typeOf(returnType);
        this.valid = valid;
    }

    static JvmMethodDescriptor parse(String descriptor) {
        if (descriptor == null) {
            return null;
        }
        try {
            Type methodType = Type.getMethodType(descriptor);
            return new JvmMethodDescriptor(descriptor, methodType.getArgumentTypes(),
                    methodType.getReturnType(), true);
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            return invalid(descriptor);
        }
    }

    private static JvmMethodDescriptor invalid(String descriptor) {
        return new JvmMethodDescriptor(descriptor, new Type[0], null, false);
    }

    String descriptor() {
        return descriptor;
    }

    List<ExprType> parameterTypes() {
        return parameterTypes;
    }

    ExprType returnType() {
        return returnType;
    }

    private static ExprType typeOf(Type type) {
        if (type == null) {
            return ExprType.unknown();
        }
        switch (type.getSort()) {
            case Type.BOOLEAN: return ExprType.primitive("boolean");
            case Type.BYTE: return ExprType.primitive("byte");
            case Type.CHAR: return ExprType.primitive("char");
            case Type.SHORT: return ExprType.primitive("short");
            case Type.INT: return ExprType.primitive("int");
            case Type.LONG: return ExprType.primitive("long");
            case Type.FLOAT: return ExprType.primitive("float");
            case Type.DOUBLE: return ExprType.primitive("double");
            case Type.ARRAY:
                ExprType component = typeOf(type.getElementType());
                return component.isKnown()
                        ? ExprType.array(component.typeName, type.getDimensions())
                        : ExprType.unknown();
            case Type.OBJECT: return ExprType.reference(type.getClassName());
            default: return ExprType.unknown();
        }
    }

    int argumentCount() {
        return valid ? parameterTypes.size() : 0;
    }

    boolean isValid() {
        return valid;
    }
}
