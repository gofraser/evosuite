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
package org.evosuite.eclipse.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.MessageConsole;
import org.junit.jupiter.api.Test;

public class EvoSuiteConsoleTest {

    /** A day of runs must not fill the IDE's memory with EvoSuite's output. */
    @Test
    public void keepsOnlyTheEndOfTheOutput() throws Exception {
        EvoSuiteConsole console = EvoSuiteConsole.get();
        MessageConsole shown = (MessageConsole) Arrays.stream(ConsolePlugin.getDefault().getConsoleManager()
                .getConsoles()).filter(c -> "EvoSuite".equals(c.getName())).findFirst().orElseThrow();
        String line = "x".repeat(99);
        for (int i = 0; i < 3000; i++) {
            console.println(line);
        }
        // The console takes the output in the background.
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(20);
        while (shown.getDocument().getLength() < EvoSuiteConsole.LOW_WATER_MARK
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        Thread.sleep(500);
        int length = shown.getDocument().getLength();
        assertTrue(length >= EvoSuiteConsole.LOW_WATER_MARK - 100 && length <= EvoSuiteConsole.HIGH_WATER_MARK + 100,
                "the console holds " + length + " characters");
    }
}
