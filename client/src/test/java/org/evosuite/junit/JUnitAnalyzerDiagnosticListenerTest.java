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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.junit;

import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code JUnitAnalyzer.BoundedDiagnosticListener} used to store every diagnostic javac reported,
 * errors and warnings alike, in one bounded list. When a file failed to compile for a real
 * reason, the accompanying warnings and notes were then logged at ERROR level right next to the
 * actual errors, which made us count warning-only output as a compile failure while triaging
 * SF110 logs. These tests drive the private listener directly (it is a private nested class, so
 * reflection is the only way in) to check that errors and non-errors are kept apart, and that a
 * flood of warnings can no longer evict a real error from the bounded list.
 */
public class JUnitAnalyzerDiagnosticListenerTest {

    private static final class FakeDiagnostic implements Diagnostic<JavaFileObject> {
        private final Kind kind;
        private final String message;

        private FakeDiagnostic(Kind kind, String message) {
            this.kind = kind;
            this.message = message;
        }

        @Override
        public Kind getKind() {
            return kind;
        }

        @Override
        public JavaFileObject getSource() {
            return null;
        }

        @Override
        public long getPosition() {
            return NOPOS;
        }

        @Override
        public long getStartPosition() {
            return NOPOS;
        }

        @Override
        public long getEndPosition() {
            return NOPOS;
        }

        @Override
        public long getLineNumber() {
            return 1;
        }

        @Override
        public long getColumnNumber() {
            return 1;
        }

        @Override
        public String getCode() {
            return null;
        }

        @Override
        public String getMessage(Locale locale) {
            return message;
        }
    }

    @SuppressWarnings("unchecked")
    private static DiagnosticListener<JavaFileObject> newListener(int maxDiagnostics) throws Exception {
        Class<?> listenerClass = Class.forName("org.evosuite.junit.JUnitAnalyzer$BoundedDiagnosticListener");
        Constructor<?> ctor = listenerClass.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        return (DiagnosticListener<JavaFileObject>) ctor.newInstance(maxDiagnostics);
    }

    @SuppressWarnings("unchecked")
    private static List<Diagnostic<? extends JavaFileObject>> invokeListDiagnostics(
            DiagnosticListener<JavaFileObject> listener, String methodName) throws Exception {
        Method m = listener.getClass().getDeclaredMethod(methodName);
        m.setAccessible(true);
        return (List<Diagnostic<? extends JavaFileObject>>) m.invoke(listener);
    }

    private static boolean invokeBoolean(DiagnosticListener<JavaFileObject> listener, String methodName)
            throws Exception {
        Method m = listener.getClass().getDeclaredMethod(methodName);
        m.setAccessible(true);
        return (Boolean) m.invoke(listener);
    }

    @Test
    public void errorsAndWarningsAreKeptInSeparateLists() throws Exception {
        DiagnosticListener<JavaFileObject> listener = newListener(100);

        listener.report(new FakeDiagnostic(Diagnostic.Kind.ERROR, "incompatible types"));
        listener.report(new FakeDiagnostic(Diagnostic.Kind.WARNING,
                "non-varargs call of varargs method with inexact argument type for last parameter"));
        listener.report(new FakeDiagnostic(Diagnostic.Kind.MANDATORY_WARNING,
                "Recompile with -Xlint:unchecked for details."));
        listener.report(new FakeDiagnostic(Diagnostic.Kind.NOTE,
                "Some messages have been simplified; recompile with -Xdiags:verbose"));

        List<Diagnostic<? extends JavaFileObject>> errors = invokeListDiagnostics(listener, "getErrorDiagnostics");
        List<Diagnostic<? extends JavaFileObject>> others = invokeListDiagnostics(listener, "getOtherDiagnostics");

        assertEquals(1, errors.size(), "only the real error belongs in the error list");
        assertEquals(Diagnostic.Kind.ERROR, errors.get(0).getKind());

        assertEquals(3, others.size(), "warnings and notes are kept, but apart from the errors");
        assertTrue(others.stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR));
    }

    @Test
    public void floodOfWarningsDoesNotEvictAnError() throws Exception {
        // A small bound shared across both kinds used to mean enough warnings could push a real
        // error out of the list before it was ever logged. With separate per-kind bounds, the
        // error survives no matter how many warnings precede or follow it.
        DiagnosticListener<JavaFileObject> listener = newListener(2);

        for (int i = 0; i < 10; i++) {
            listener.report(new FakeDiagnostic(Diagnostic.Kind.WARNING, "warning " + i));
        }
        listener.report(new FakeDiagnostic(Diagnostic.Kind.ERROR, "the real error"));
        for (int i = 0; i < 10; i++) {
            listener.report(new FakeDiagnostic(Diagnostic.Kind.WARNING, "more warning " + i));
        }

        List<Diagnostic<? extends JavaFileObject>> errors = invokeListDiagnostics(listener, "getErrorDiagnostics");
        assertEquals(1, errors.size());
        assertEquals("the real error", errors.get(0).getMessage(null));

        assertFalse(invokeBoolean(listener, "wasErrorsTruncated"), "the one error never had to compete for room");
        assertTrue(invokeBoolean(listener, "wasOthersTruncated"), "20 warnings exceed the bound of 2");
    }

    @Test
    public void errorBoundIsAppliedIndependentlyOfWarningBound() throws Exception {
        DiagnosticListener<JavaFileObject> listener = newListener(2);

        for (int i = 0; i < 5; i++) {
            listener.report(new FakeDiagnostic(Diagnostic.Kind.ERROR, "error " + i));
        }

        List<Diagnostic<? extends JavaFileObject>> errors = invokeListDiagnostics(listener, "getErrorDiagnostics");
        assertEquals(2, errors.size());
        assertTrue(invokeBoolean(listener, "wasErrorsTruncated"));
        assertFalse(invokeBoolean(listener, "wasOthersTruncated"), "no warnings were reported at all");
    }

    @Test
    public void otherKindIsTreatedAsErrorLike() throws Exception {
        // Diagnostic.Kind.OTHER can carry a fatal condition javac has no dedicated kind for, so
        // it belongs with the errors rather than being filed away as a mere warning/note.
        DiagnosticListener<JavaFileObject> listener = newListener(100);

        listener.report(new FakeDiagnostic(Diagnostic.Kind.OTHER, "something fatal, kind-wise unclassified"));

        List<Diagnostic<? extends JavaFileObject>> errors = invokeListDiagnostics(listener, "getErrorDiagnostics");
        List<Diagnostic<? extends JavaFileObject>> others = invokeListDiagnostics(listener, "getOtherDiagnostics");
        assertEquals(1, errors.size());
        assertTrue(others.isEmpty());
    }
}
