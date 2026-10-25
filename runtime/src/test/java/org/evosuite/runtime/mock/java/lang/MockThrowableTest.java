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
package org.evosuite.runtime.mock.java.lang;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.java.io.MockIOException;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MockThrowableTest {

    @After
    public void tearDown() {
        MockFramework.disable();
    }

    @Test
    public void printStackTraceToWriterPrintsTheRealTraceOnlyWhenMockingIsOff() {
        MockFramework.disable();
        StringWriter out = new StringWriter();
        MockThrowable.replacement_printStackTrace(new IOException("boom"), new PrintWriter(out, true));
        assertTrue(out.toString().startsWith("java.io.IOException: boom"));
        assertFalse(out.toString().contains("<evosuite>"));
    }

    @Test
    public void printStackTraceToStreamPrintsTheRealTraceOnlyWhenMockingIsOff() {
        MockFramework.disable();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MockThrowable.replacement_printStackTrace(new IOException("boom"), new PrintStream(bytes, true));
        String text = bytes.toString();
        assertTrue(text.startsWith("java.io.IOException: boom"));
        assertFalse(text.contains("<evosuite>"));
    }

    @Test
    public void printStackTraceToWriterPrintsTheDefaultTraceOnlyWhenMockingIsOn() {
        MockFramework.enable();
        StringWriter out = new StringWriter();
        MockThrowable.replacement_printStackTrace(new IOException("boom"), new PrintWriter(out, true));
        String frame = "<evosuite>.<evosuite>(<evosuite>)\n";
        assertEquals(frame + frame + frame, out.toString());
    }

    @Test
    public void iOExceptionWithoutStackTraceCanBeConstructed() {
        MockFramework.enable();
        Throwable t = new MockIOException("msg") {
            @Override
            public synchronized Throwable fillInStackTrace() {
                return this;
            }
        };
        assertEquals("msg", t.getMessage());
    }

    @Test
    public void exceptionWithoutStackTraceCanBeConstructed() {
        MockFramework.enable();
        Throwable t = new MockException("msg") {
            @Override
            public synchronized Throwable fillInStackTrace() {
                return this;
            }
        };
        assertEquals("msg", t.getMessage());
    }

    @Test
    public void errorWithoutStackTraceCanBeConstructed() {
        MockFramework.enable();
        Throwable t = new MockError("msg") {
            @Override
            public synchronized Throwable fillInStackTrace() {
                return this;
            }
        };
        assertEquals("msg", t.getMessage());
    }

    @Test
    public void illegalArgumentExceptionWithoutStackTraceCanBeConstructed() {
        MockFramework.enable();
        Throwable t = new MockIllegalArgumentException("msg") {
            @Override
            public synchronized Throwable fillInStackTrace() {
                return this;
            }
        };
        assertEquals("msg", t.getMessage());
    }

    @Test
    public void illegalStateExceptionWithoutStackTraceCanBeConstructed() {
        MockFramework.enable();
        Throwable t = new MockIllegalStateException("msg") {
            @Override
            public synchronized Throwable fillInStackTrace() {
                return this;
            }
        };
        assertEquals("msg", t.getMessage());
    }

    @Test
    public void illegalAccessExceptionWithoutStackTraceCanBeConstructed() {
        MockFramework.enable();
        Throwable t = new MockIllegalAccessException("msg") {
            @Override
            public synchronized Throwable fillInStackTrace() {
                return this;
            }
        };
        assertEquals("msg", t.getMessage());
    }

    @Test
    public void arithmeticExceptionWithoutStackTraceCanBeConstructed() {
        MockFramework.enable();
        Throwable t = new MockArithmeticException("msg") {
            @Override
            public synchronized Throwable fillInStackTrace() {
                return this;
            }
        };
        assertEquals("msg", t.getMessage());
    }

    @Test
    public void nullPointerExceptionWithoutStackTraceCanBeConstructed() {
        MockFramework.enable();
        Throwable t = new MockNullPointerException("msg") {
            @Override
            public synchronized Throwable fillInStackTrace() {
                return this;
            }
        };
        assertEquals("msg", t.getMessage());
    }

    @Test
    public void arrayIndexOutOfBoundsExceptionWithoutStackTraceCanBeConstructed() {
        MockFramework.enable();
        Throwable t = new MockArrayIndexOutOfBoundsException("msg") {
            @Override
            public synchronized Throwable fillInStackTrace() {
                return this;
            }
        };
        assertEquals("msg", t.getMessage());
    }

    @Test
    public void toStringNamesTheClassOfTheMockedException() {
        MockFramework.enable();
        assertEquals("java.io.IOException: msg", new MockIOException("msg").toString());
        assertEquals("java.lang.IllegalStateException: msg", new MockIllegalStateException("msg").toString());
        assertEquals("java.lang.RuntimeException", new MockRuntimeException().toString());
    }

    @Test
    public void toStringKeepsTheNameOfAnApplicationSubclass() {
        MockFramework.enable();
        class AppException extends MockIOException {
            AppException(String message) {
                super(message);
            }
        }
        assertEquals(AppException.class.getName() + ": msg", new AppException("msg").toString());
    }

    @Test
    public void localizedMessageFollowsAnOverriddenGetMessage() {
        MockFramework.enable();
        Throwable t = new MockIllegalStateException("msg") {
            @Override
            public String getMessage() {
                return "overridden";
            }
        };
        assertEquals("overridden", t.getLocalizedMessage());
    }

    @Test
    public void secondInitCauseFailsWithTheMockedException() {
        MockFramework.enable();
        Throwable t = new MockIOException("msg");
        t.initCause(new RuntimeException("first"));
        try {
            t.initCause(new RuntimeException("second"));
            fail("expected an exception");
        } catch (MockIllegalStateException expected) {
            assertTrue(expected.getMessage().startsWith("Can't overwrite cause"));
        }
        try {
            new MockIOException("msg").initCause(null).initCause(null);
            fail("expected an exception");
        } catch (MockIllegalStateException expected) {
            // a cause set to null counts as set
        }
    }

    @Test
    public void selfCausationFailsWithTheMockedException() {
        MockFramework.enable();
        Throwable t = new MockRuntimeException("msg");
        try {
            t.initCause(t);
            fail("expected an exception");
        } catch (MockIllegalArgumentException expected) {
            assertEquals("Self-causation not permitted", expected.getMessage());
        }
    }

    @Test
    public void stackTraceWithoutRecordedFramesKeepsTheFixedTrace() {
        MockFramework.enable();
        Throwable t = new MockRuntimeException("msg") {
            @Override
            public synchronized Throwable fillInStackTrace() {
                return this;
            }
        };
        assertEquals(3, t.getStackTrace().length);
        assertEquals("<evosuite>", t.getStackTrace()[0].getClassName());
    }

    @Test
    public void stackTraceStartsAtTheCreationSite() {
        MockFramework.enable();
        StackTraceElement[] trace = new MockIOException("msg").getStackTrace();
        assertEquals(3, trace.length);
        assertEquals(MockThrowableTest.class.getName(), trace[0].getClassName());
        assertEquals("<evosuite>", trace[1].getClassName());
    }

    @Test
    public void setStackTraceRejectsNullElements() {
        MockFramework.enable();
        Throwable t = new MockError("msg");
        try {
            t.setStackTrace(new StackTraceElement[]{null});
            fail("expected an exception");
        } catch (MockNullPointerException expected) {
            assertEquals("stackTrace[0]", expected.getMessage());
        }
        StackTraceElement element = new StackTraceElement("A", "b", "A.java", 1);
        t.setStackTrace(new StackTraceElement[]{element});
        assertEquals(element, t.getStackTrace()[0]);
    }

    @Test
    public void printStackTraceWritesTheMockedTrace() {
        MockFramework.enable();
        Throwable t = new MockIOException("msg");
        java.io.StringWriter writer = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(writer));
        assertEquals(ThrowableText.render(t.getStackTrace()), writer.toString());
        assertEquals(3, writer.toString().split("\n").length);
    }
}
