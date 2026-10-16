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
package org.evosuite.runtime.classhandling;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.evosuite.runtime.agent.InstrumentingAgent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

public class ClassStateSupportRetransformationFailureTest {
    public static class UnmodifiableFixture {
    }

    @Test
    public void failedRetransformationStillLogsError() throws Exception {
        assertFailureLogged(new UnmodifiableClassException("fixture cannot be retransformed"));
    }

    @Test
    public void unsupportedRetransformationStillLogsError() throws Exception {
        assertFailureLogged(new UnsupportedOperationException("fixture rejects retransformation"));
    }

    private void assertFailureLogged(Exception failure) throws Exception {
        Field field = InstrumentingAgent.class.getDeclaredField("instrumentation");
        field.setAccessible(true);
        Object previous = field.get(null);
        boolean previouslyActive = InstrumentingAgent.getTransformer().isActive();
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Instrumentation.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("retransformClasses")) {
                        throw failure;
                    }
                    return null;
                });
        Logger logger = (Logger) LoggerFactory.getLogger(ClassStateSupport.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            field.set(null, instrumentation);
            ClassStateSupport.retransformIfNeeded(Collections.singletonList(UnmodifiableFixture.class));
            assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                    && event.getFormattedMessage().contains("re-instrument")), appender.list.toString());
            assertFalse(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("does not have InstrumentedClass")));
            assertEquals(previouslyActive, InstrumentingAgent.getTransformer().isActive());
        } finally {
            field.set(null, previous);
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
