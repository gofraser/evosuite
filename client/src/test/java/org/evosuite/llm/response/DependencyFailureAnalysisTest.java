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
package org.evosuite.llm.response;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyFailureAnalysisTest {

    private static final String CUT = "com.example.cut.MyClass";
    private static final String CUT_PKG = "com.example.cut";

    @Test
    void classifiesDepConstructorFailure() {
        Throwable thrown = throwableWithStack(new NullPointerException("oops"),
                frame("com.dep.Other", "<init>", "Other.java", 42),
                frame(CUT, "useDep", "MyClass.java", 17));

        DependencyFailureAnalysis analysis = DependencyFailureAnalysis.analyze(thrown, CUT, CUT_PKG);

        assertEquals(DependencyFailureAnalysis.Kind.DEP_MEMBER_FAILURE, analysis.getKind());
        DependencyFailureAnalysis.Frame frame = analysis.getFrame();
        assertNotNull(frame);
        assertEquals("com.dep.Other", frame.getClassName());
        assertTrue(frame.isConstructor());
        assertEquals(42, frame.getLineNumber());
    }

    @Test
    void classifiesDepMethodFailure() {
        Throwable thrown = throwableWithStack(new IllegalStateException("bad"),
                frame("com.dep.Helper", "doWork", "Helper.java", 10),
                frame(CUT, "trigger", "MyClass.java", 25));

        DependencyFailureAnalysis analysis = DependencyFailureAnalysis.analyze(thrown, CUT, CUT_PKG);

        assertEquals(DependencyFailureAnalysis.Kind.DEP_MEMBER_FAILURE, analysis.getKind());
        assertEquals("doWork", analysis.getFrame().getMethodName());
    }

    @Test
    void skipsJdkAndFrameworkFramesAboveDepFrame() {
        Throwable thrown = throwableWithStack(new RuntimeException("inside dep"),
                frame("java.util.HashMap", "put", "HashMap.java", 1),
                frame("org.junit.Assert", "assertEquals", "Assert.java", 2),
                frame("com.dep.Real", "process", "Real.java", 99),
                frame(CUT, "x", "MyClass.java", 5));

        DependencyFailureAnalysis analysis = DependencyFailureAnalysis.analyze(thrown, CUT, CUT_PKG);

        assertEquals(DependencyFailureAnalysis.Kind.DEP_MEMBER_FAILURE, analysis.getKind());
        assertEquals("com.dep.Real", analysis.getFrame().getClassName());
    }

    @Test
    void unwrapsInvocationTargetException() {
        RuntimeException root = throwableWithStack(new RuntimeException("root"),
                frame("com.dep.Inner", "boom", "Inner.java", 3));
        Throwable wrapped = new InvocationTargetException(root);

        DependencyFailureAnalysis analysis = DependencyFailureAnalysis.analyze(wrapped, CUT, CUT_PKG);

        assertEquals(DependencyFailureAnalysis.Kind.DEP_MEMBER_FAILURE, analysis.getKind());
        assertEquals("com.dep.Inner", analysis.getFrame().getClassName());
    }

    @Test
    void classifiesClinitFailure() {
        ExceptionInInitializerError eiie = new ExceptionInInitializerError(new RuntimeException("blew up"));
        eiie.setStackTrace(new StackTraceElement[]{
                frame("com.dep.Initialized", "<clinit>", "Initialized.java", 5)
        });

        DependencyFailureAnalysis analysis = DependencyFailureAnalysis.analyze(eiie, CUT, CUT_PKG);

        assertEquals(DependencyFailureAnalysis.Kind.CLASS_INIT_FAILURE, analysis.getKind());
        assertEquals("com.dep.Initialized", analysis.getPoisonedClass());
        assertTrue(analysis.getFrame().isStaticInitializer());
    }

    @Test
    void classifiesNoClassDefFoundAftershock() {
        NoClassDefFoundError ncdfe = new NoClassDefFoundError(
                "Could not initialize class com.dep.Initialized");

        DependencyFailureAnalysis analysis = DependencyFailureAnalysis.analyze(ncdfe, CUT, CUT_PKG);

        assertEquals(DependencyFailureAnalysis.Kind.CLASS_INIT_AFTERSHOCK, analysis.getKind());
        assertEquals("com.dep.Initialized", analysis.getPoisonedClass());
        assertNull(analysis.getFrame());
        assertTrue(analysis.isClassInitFailure());
    }

    @Test
    void cutOwnConstructorIsNotClassifiedAsDepFailure() {
        // Stack starts inside the CUT itself — there is no non-CUT, non-framework
        // frame "above" it, so we should report NONE (CUT source is already
        // available to the LLM via system prompt).
        Throwable thrown = throwableWithStack(new IllegalArgumentException("bad arg"),
                frame(CUT, "<init>", "MyClass.java", 7));

        DependencyFailureAnalysis analysis = DependencyFailureAnalysis.analyze(thrown, CUT, CUT_PKG);

        assertEquals(DependencyFailureAnalysis.Kind.NONE, analysis.getKind());
    }

    @Test
    void emptyTargetClassFallsBackToPackagePrefix() {
        // When TARGET_CLASS is missing but the package is known, classes inside
        // the target package still count as CUT.
        Throwable thrown = throwableWithStack(new IllegalArgumentException(),
                frame("com.example.cut.HelperInSamePackage", "x", "HelperInSamePackage.java", 1));

        DependencyFailureAnalysis analysis = DependencyFailureAnalysis.analyze(thrown, "", CUT_PKG);

        assertEquals(DependencyFailureAnalysis.Kind.NONE, analysis.getKind());
    }

    @Test
    void clinitFailureForCutItselfIsNotReported() {
        ExceptionInInitializerError eiie = new ExceptionInInitializerError(new RuntimeException());
        eiie.setStackTrace(new StackTraceElement[]{
                frame(CUT, "<clinit>", "MyClass.java", 9)
        });

        DependencyFailureAnalysis analysis = DependencyFailureAnalysis.analyze(eiie, CUT, CUT_PKG);

        assertEquals(DependencyFailureAnalysis.Kind.NONE, analysis.getKind());
    }

    @Test
    void nullThrowableIsNone() {
        DependencyFailureAnalysis analysis = DependencyFailureAnalysis.analyze(null, CUT, CUT_PKG);
        assertEquals(DependencyFailureAnalysis.Kind.NONE, analysis.getKind());
        assertNull(analysis.getFrame());
        assertNull(analysis.getPoisonedClass());
    }

    private static StackTraceElement frame(String declaringClass,
                                           String methodName,
                                           String fileName,
                                           int lineNumber) {
        return new StackTraceElement(declaringClass, methodName, fileName, lineNumber);
    }

    private static <T extends Throwable> T throwableWithStack(T throwable, StackTraceElement... frames) {
        throwable.setStackTrace(frames);
        return throwable;
    }
}
