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
package org.evosuite.eclipse.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

public class ProgressParserTest {

    private final List<String> lines = new ArrayList<>();
    private final List<String> progress = new ArrayList<>();
    private final ProgressParser parser = new ProgressParser(new ProgressParser.Listener() {
        @Override
        public void line(String line) {
            lines.add(line);
        }

        @Override
        public void progress(int searchPercent, int coveragePercent) {
            progress.add(searchPercent + "/" + coveragePercent);
        }
    });

    /** What ConsoleProgressBar prints for one frame. */
    private static String frame(int percent, int coverage) {
        return "\r[Progress:" + "=".repeat(percent * 30 / 100) + ">" + " ".repeat(30 - percent * 30 / 100)
                + percent + "%] [Cov:" + "=".repeat(coverage * 35 / 100) + ">" + coverage + "%]";
    }

    @Test
    public void passesLinesThrough() {
        parser.accept("* Going to generate test cases for class: demo.Calculator\n* Starting client\n");
        assertEquals(List.of("* Going to generate test cases for class: demo.Calculator", "* Starting client"), lines);
    }

    @Test
    public void turnsBarFramesIntoProgress() {
        parser.accept("* Starting evolution\n" + frame(0, 0) + frame(17, 23) + frame(100, 87) + "\n* Search finished\n");
        assertEquals(List.of("0/0", "17/23", "100/87"), progress);
        assertEquals(List.of("* Starting evolution", "* Search finished"), lines);
    }

    @Test
    public void handlesChunksSplitAnywhere() {
        String output = "* a\n" + frame(42, 50) + "\n* b\n";
        for (int i = 0; i < output.length(); i++) {
            parser.accept(output.substring(i, i + 1));
        }
        parser.close();
        assertEquals(List.of("42/50"), progress);
        assertEquals(List.of("* a", "* b"), lines);
    }

    @Test
    public void keepsEmptyLinesButNotRedraws() {
        parser.accept("a\n\nb\r\n\rc");
        parser.close();
        assertEquals(List.of("a", "", "b", "c"), lines);
    }

    @Test
    public void keepsEmptyLinesInCrlfOutput() {
        parser.accept("x\r\n\r");
        parser.accept("\ny\r\n");
        parser.close();
        assertEquals(List.of("x", "", "y"), lines);
    }

    @Test
    public void keepsTextPrintedOnTheSameLineAsABar() {
        parser.accept(frame(100, 90) + " done\n");
        assertEquals(List.of("100/90"), progress);
        assertEquals(List.of("done"), lines);
    }
}
