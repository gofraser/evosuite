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
package org.evosuite.eclipse.handlers;

import java.util.Map;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;

/**
 * Names methods as EvoSuite's {@code target_method_list} expects them: the name, or
 * {@code <init>} for a constructor, followed by the JVM descriptor, as in
 * {@code sign(I)Ljava/lang/String;}. The descriptor is that of the compiled method, so type
 * parameters are erased to their first bound, varargs are arrays and nested classes take their
 * binary names ({@code Outer$Inner}).
 */
public final class MethodDescriptors {

    private static final Map<String, String> PRIMITIVES = Map.of("boolean", "Z", "byte", "B", "char", "C",
            "short", "S", "int", "I", "long", "J", "float", "F", "double", "D", "void", "V");

    private MethodDescriptors() {
    }

    /** The method's EvoSuite name, or {@code null} if its types cannot be resolved. */
    public static String of(IMethod method) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setProject(method.getJavaProject());
        IBinding[] bindings = parser.createBindings(new IJavaElement[]{method}, null);
        if (bindings.length == 1 && bindings[0] instanceof IMethodBinding binding && !binding.isRecovered()) {
            return of(binding);
        }
        return null;
    }

    static String of(IMethodBinding binding) {
        IMethodBinding declaration = binding.getMethodDeclaration();
        StringBuilder name = new StringBuilder(declaration.isConstructor() ? "<init>" : declaration.getName());
        name.append('(');
        for (ITypeBinding parameter : declaration.getParameterTypes()) {
            String descriptor = descriptor(parameter);
            if (descriptor == null) {
                return null;
            }
            name.append(descriptor);
        }
        String result = declaration.isConstructor() ? "V" : descriptor(declaration.getReturnType());
        return result == null ? null : name.append(')').append(result).toString();
    }

    private static String descriptor(ITypeBinding type) {
        if (type.isTypeVariable() || type.isCapture() || type.isWildcardType()) {
            // Erased to the first bound, or Object if there is none.
            ITypeBinding[] bounds = type.getTypeBounds();
            return bounds.length == 0 ? "Ljava/lang/Object;" : descriptor(bounds[0]);
        }
        if (type.isArray()) {
            String component = descriptor(type.getComponentType());
            return component == null ? null : "[" + component;
        }
        if (type.isPrimitive()) {
            return PRIMITIVES.get(type.getName());
        }
        String binaryName = type.getErasure().getBinaryName();
        return binaryName == null || type.isRecovered() ? null : "L" + binaryName.replace('.', '/') + ";";
    }
}
