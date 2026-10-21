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
package org.evosuite.intellij.actions;

import com.intellij.psi.*;
import com.intellij.psi.util.*;

public final class MethodRules {
    private MethodRules() {}

    public static String whyNotTestable(PsiMethod method) {
        PsiClass type = method.getContainingClass();
        if (type == null || type.getContainingClass() != null)
            return "Select a method of a top-level class.";
        if (type.isInterface() || type.isAnnotationType())
            return "Interfaces and annotations cannot be tested.";
        if (method.hasModifierProperty(PsiModifier.PRIVATE)
                || method.hasModifierProperty(PsiModifier.ABSTRACT))
            return "Private and abstract methods cannot be called directly by tests.";
        if (method.isConstructor()
                && (type.isEnum() || type.hasModifierProperty(PsiModifier.ABSTRACT)))
            return "This constructor cannot be called directly by tests.";
        return null;
    }

    public static String descriptor(PsiMethod method) {
        StringBuilder out =
                new StringBuilder(method.isConstructor() ? "<init>" : method.getName()).append('(');
        for (PsiParameter param : method.getParameterList().getParameters())
            out.append(type(TypeConversionUtil.erasure(param.getType())));
        return out.append(')')
                .append(
                        method.isConstructor()
                                ? "V"
                                : type(TypeConversionUtil.erasure(method.getReturnType())))
                .toString();
    }

    private static String type(PsiType type) {
        if (type == null || PsiTypes.voidType().equals(type)) return "V";
        if (type instanceof PsiArrayType array) return "[" + type(array.getComponentType());
        String primitive =
                switch (type.getCanonicalText()) {
                    case "boolean" -> "Z";
                    case "byte" -> "B";
                    case "char" -> "C";
                    case "short" -> "S";
                    case "int" -> "I";
                    case "long" -> "J";
                    case "float" -> "F";
                    case "double" -> "D";
                    default -> null;
                };
        if (primitive != null) return primitive;
        if (type instanceof PsiClassType ref) {
            PsiClass resolved = ref.resolve();
            if (resolved != null)
                return "L" + ClassUtil.getJVMClassName(resolved).replace('.', '/') + ";";
        }
        throw new IllegalArgumentException(
                "Cannot resolve method parameter type "
                        + type.getPresentableText()
                        + ". Build the project first.");
    }
}
