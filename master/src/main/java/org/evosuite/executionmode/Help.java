/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.executionmode;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.help.HelpFormatter;

public class Help {

    public static final String NAME = "help";

    public static Option getOption() {
        return new Option(NAME, "print this message");
    }

    public static Object execute(Options options) {
        HelpFormatter formatter = HelpFormatter.builder().get();
        try {
            formatter.printHelp("EvoSuite", null, options, null, true);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to render help text", e);
        }
        return null;
    }
}
