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

import org.evosuite.PackageInfo;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Instruments classes to call the tracer each time a new line of the source
 * code is passed.
 *
 * @author Gordon Fraser
 */
public class LineNumberMethodAdapter extends AbstractEvoMethodAdapter {

    @SuppressWarnings("unused")
    private static final Logger logger = LoggerFactory.getLogger(LineNumberMethodAdapter.class);

    private final String fullMethodName;

    int currentLine = 0;

    public LineNumberMethodAdapter(MethodVisitor mv, int access, String className, String methodName,
                                   String desc) {
        super(mv, access, className, methodName, desc);
        fullMethodName = methodName + desc;
    }


    private void addLineNumberInstrumentation(int line) {
        LinePool.addLine(className, fullMethodName, line);
        this.visitLdcInsn(className);
        this.visitLdcInsn(fullMethodName);
        this.visitLdcInsn(line);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                PackageInfo.getNameWithSlash(ExecutionTracer.class),
                "passedLine", "(Ljava/lang/String;Ljava/lang/String;I)V", false);
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        super.visitLineNumber(line, start);
        currentLine = line;

        if (shouldSkip()) {
            return;
        }

        // Lines before the super()/this() call of a constructor are traced in place. The
        // tracer call is static and stack-neutral, so it is legal on an uninitialised this,
        // and deferring it until after super() would lose every line when the arguments of
        // that call (or the super constructor itself) throw.
        addLineNumberInstrumentation(line);
    }

    @Override
    protected int getExtraStackSlots() {
        return 3;
    }
}
