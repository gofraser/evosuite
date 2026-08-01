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

    private JvmMethodDescriptor(String descriptor, List<String> parameterDescriptors,
                               String returnDescriptor, boolean valid) {
        this.descriptor = descriptor;
        List<ExprType> types = new ArrayList<>();
        for (String parameterDescriptor : parameterDescriptors) {
            types.add(typeOf(parameterDescriptor));
        }
        this.parameterTypes = Collections.unmodifiableList(types);
        this.returnType = typeOf(returnDescriptor);
        this.valid = valid;
    }

    static JvmMethodDescriptor parse(String descriptor) {
        if (descriptor == null) {
            return null;
        }
        if (!descriptor.startsWith("(")) {
            return invalid(descriptor);
        }
        int close = descriptor.indexOf(')');
        if (close < 0) {
            return invalid(descriptor);
        }
        List<String> parameters = new ArrayList<>();
        int index = 1;
        while (index < close) {
            int next = nextTypeIndex(descriptor, index, false);
            if (next <= index || next > close) {
                return invalid(descriptor);
            }
            parameters.add(descriptor.substring(index, next));
            index = next;
        }
        if (close == descriptor.length() - 1) {
            return invalid(descriptor);
        }
        String returnDescriptor = descriptor.substring(close + 1);
        if (nextTypeIndex(returnDescriptor, 0, true) != returnDescriptor.length()) {
            return invalid(descriptor);
        }
        return new JvmMethodDescriptor(descriptor, parameters, returnDescriptor, true);
    }

    private static JvmMethodDescriptor invalid(String descriptor) {
        return new JvmMethodDescriptor(descriptor, new ArrayList<String>(), null, false);
    }

    private static int nextTypeIndex(String descriptor, int start, boolean allowVoid) {
        if (descriptor == null || start >= descriptor.length()) {
            return -1;
        }
        int index = start;
        while (index < descriptor.length() && descriptor.charAt(index) == '[') {
            index++;
        }
        if (index >= descriptor.length()) {
            return -1;
        }
        char kind = descriptor.charAt(index);
        if ("ZBCSIJFD".indexOf(kind) >= 0) {
            return index + 1;
        }
        if (kind == 'V') {
            return allowVoid && index == start ? index + 1 : -1;
        }
        if (kind == 'L') {
            int semicolon = descriptor.indexOf(';', index);
            if (semicolon <= index + 1) {
                return -1;
            }
            return semicolon + 1;
        }
        return -1;
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

    private static ExprType typeOf(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return ExprType.unknown();
        }
        int arrays = 0;
        while (arrays < descriptor.length() && descriptor.charAt(arrays) == '[') {
            arrays++;
        }
        if (arrays > 0) {
            ExprType component = typeOf(descriptor.substring(arrays));
            return component.isKnown() ? ExprType.array(component.typeName, arrays) : ExprType.unknown();
        }
        switch (descriptor.charAt(0)) {
            case 'Z': return ExprType.primitive("boolean");
            case 'B': return ExprType.primitive("byte");
            case 'C': return ExprType.primitive("char");
            case 'S': return ExprType.primitive("short");
            case 'I': return ExprType.primitive("int");
            case 'J': return ExprType.primitive("long");
            case 'F': return ExprType.primitive("float");
            case 'D': return ExprType.primitive("double");
            case 'L':
                return descriptor.endsWith(";")
                        ? ExprType.reference(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'))
                        : ExprType.unknown();
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
