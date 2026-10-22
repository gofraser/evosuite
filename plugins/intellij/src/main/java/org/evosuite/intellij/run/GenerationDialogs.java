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
package org.evosuite.intellij.run;

/** Keep multi-target questions readable on smaller displays. Messages does not wrap plain text. */
final class GenerationDialogs {
    private GenerationDialogs() {}

    static String message(String text) {
        StringBuilder result = new StringBuilder();
        for (String line : text.split("\\n", -1)) {
            if (!result.isEmpty()) result.append('\n');
            while (line.length() > 88) {
                int split = line.lastIndexOf(' ', 88);
                if (split < 1) split = 88;
                result.append(line, 0, split).append('\n');
                line = line.substring(split).stripLeading();
            }
            result.append(line);
        }
        return result.toString();
    }
}
