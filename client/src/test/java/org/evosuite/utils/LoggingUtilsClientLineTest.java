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
package org.evosuite.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.evosuite.ClientProcess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LoggingUtilsClientLineTest {

    private Logger evoLogger;
    private boolean additive;
    private Level level;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureEvoLogger() {
        evoLogger = (Logger) LoggerFactory.getLogger("evo_logger");
        additive = evoLogger.isAdditive();
        level = evoLogger.getLevel();
        evoLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        evoLogger.addAppender(appender);
        evoLogger.setAdditive(false);
    }

    @AfterEach
    void restoreEvoLogger() {
        evoLogger.detachAppender(appender);
        appender.stop();
        evoLogger.setAdditive(additive);
        evoLogger.setLevel(level);
    }

    @Test
    void infoLinesStartWithStarAndClientIdentifier() {
        LoggingUtils.clientInfo("Search finished after {}s", 5);

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.INFO, event.getLevel());
        assertEquals("* " + ClientProcess.getPrettyPrintIdentifier() + "Search finished after 5s",
                event.getFormattedMessage());
    }

    @Test
    void warnAndErrorKeepTheirLevel() {
        LoggingUtils.clientWarn("careful");
        LoggingUtils.clientError("broken {}", "badly");

        assertEquals(Level.WARN, appender.list.get(0).getLevel());
        assertEquals("* " + ClientProcess.getPrettyPrintIdentifier() + "careful",
                appender.list.get(0).getFormattedMessage());
        assertEquals(Level.ERROR, appender.list.get(1).getLevel());
        assertEquals("* " + ClientProcess.getPrettyPrintIdentifier() + "broken badly",
                appender.list.get(1).getFormattedMessage());
    }
}
