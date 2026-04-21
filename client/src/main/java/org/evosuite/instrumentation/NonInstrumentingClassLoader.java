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

import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.instrumentation.LoopCounterClassAdapter;
import org.evosuite.runtime.util.ComputeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

import java.io.IOException;
import java.io.InputStream;

public class NonInstrumentingClassLoader extends InstrumentingClassLoader {

    public NonInstrumentingClassLoader() {
        super();
    }

    /*
    public NonInstrumentingClassLoader(ClassLoader parent) {
        super(parent);
        setClassAssertionStatus(Properties.TARGET_CLASS, true);
        classLoader = parent; //NonInstrumentingClassLoader.class.getClassLoader();

    }
    */

    @Override
    protected byte[] getTransformedBytes(String className, InputStream is) throws IOException {

        ClassReader reader = new ClassReader(is);
        int readFlags = ClassReader.SKIP_FRAMES;

        /*
         *  To use COMPUTE_FRAMES we need to remove JSR commands.
         *  Therefore, we have a JSRInlinerAdapter in NonTargetClassAdapter
         *  as well as CFGAdapter.
         */
        int asmFlags = ClassWriter.COMPUTE_FRAMES;
        ClassWriter writer = new ComputeClassWriter(asmFlags);

        ClassVisitor cv = writer;
        // Bound SUT loops during JUnit check too — without this, a runaway loop
        // in the SUT terminates at generation time (where InstrumentingClassLoader
        // adds LoopCounterClassAdapter) but hangs at JUnit check, producing a
        // spurious "unstable test" verdict for tests that were perfectly stable
        // during search. JUnitAnalyzer already silently swallows the resulting
        // TooManyResourcesException (see the failure-loop in runTests).
        //
        // LoopCounterClassAdapter MUST sit inside NonTargetClassAdapter (closer to
        // the writer). Its AnalyzerAdapter cannot handle JSR/RET, so classes with
        // pre-Java-6 subroutines (e.g. xerces) need the JSRInlinerAdapter inside
        // NonTargetClassAdapter to run *first* and inline them away before the
        // bytecode reaches the loop counter.
        if (RuntimeSettings.maxNumberOfIterationsPerLoop >= 0) {
            cv = new LoopCounterClassAdapter(cv);
        }
        cv = new NonTargetClassAdapter(cv, className);
        reader.accept(cv, readFlags);
        return writer.toByteArray();
    }
}
