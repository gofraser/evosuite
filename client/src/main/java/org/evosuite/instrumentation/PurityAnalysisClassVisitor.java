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
package org.evosuite.instrumentation;

import org.evosuite.assertion.CheapPurityAnalyzer;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * It launches a <code>PurityAnalysisMethodVisitor</code> on each method.
 * This class only reads the existing bytecode.
 *
 * @author Juan Galeotti
 */
public class PurityAnalysisClassVisitor extends ClassVisitor {

    private final CheapPurityAnalyzer purityAnalyzer;

    private final String className;

    /**
     * <p>
     * Constructor for StaticInitializationClassAdapter.
     * </p>
     *
     * @param visitor   a {@link org.objectweb.asm.ClassVisitor} object.
     * @param className a {@link java.lang.String} object.
     * @param purityAnalyzer a {@link CheapPurityAnalyzer} object.
     */
    public PurityAnalysisClassVisitor(ClassVisitor visitor, String className,
                                      CheapPurityAnalyzer purityAnalyzer) {
        super(Opcodes.ASM9, visitor);
        this.className = className;
        this.purityAnalyzer = purityAnalyzer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MethodVisitor visitMethod(int methodAccess, String name,
                                     String descriptor, String signature, String[] exceptions) {


        if (visitingInterface) {
            purityAnalyzer.addInterfaceMethod(className.replace('/', '.'),
                    name, descriptor);
        } else {
            purityAnalyzer.addMethod(className.replace('/', '.'), name,
                    descriptor);
            if ((methodAccess & Opcodes.ACC_ABSTRACT) != Opcodes.ACC_ABSTRACT) {
                purityAnalyzer.addMethodWithBody(className.replace('/', '.'), name,
                        descriptor);
            } else {
                // The declaration of this method is abstract. So
                // there is no method body for this method in this class
            }
        }

        MethodVisitor mv = super.visitMethod(methodAccess, name, descriptor,
                signature, exceptions);
        return new PurityAnalysisMethodVisitor(
                className, name, descriptor, mv, purityAnalyzer);
    }

    private boolean visitingInterface = false;

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        if ((access & Opcodes.ACC_INTERFACE) == Opcodes.ACC_INTERFACE) {
            visitingInterface = true;
        }
        super.visit(version, access, name, signature, superName, interfaces);
    }
}
