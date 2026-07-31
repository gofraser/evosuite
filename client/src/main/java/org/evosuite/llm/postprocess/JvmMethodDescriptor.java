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
    private final List<String> parameterDescriptors;
    private final String returnDescriptor;
    private final boolean valid;

    private JvmMethodDescriptor(String descriptor, List<String> parameterDescriptors,
                               String returnDescriptor, boolean valid) {
        this.descriptor = descriptor;
        this.parameterDescriptors = Collections.unmodifiableList(parameterDescriptors);
        this.returnDescriptor = returnDescriptor;
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

    List<String> parameterDescriptors() {
        return parameterDescriptors;
    }

    String returnDescriptor() {
        return returnDescriptor;
    }

    int argumentCount() {
        return valid ? parameterDescriptors.size() : 0;
    }

    boolean isValid() {
        return valid;
    }
}
