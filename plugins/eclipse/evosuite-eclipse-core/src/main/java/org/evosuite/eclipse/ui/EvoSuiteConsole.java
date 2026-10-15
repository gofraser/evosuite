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

import java.util.Arrays;

import org.eclipse.jface.resource.ResourceLocator;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;
import org.evosuite.eclipse.Activator;

/** The "EvoSuite" console, which shows each run's command line and EvoSuite's own output. */
public final class EvoSuiteConsole {

    private static final String NAME = "EvoSuite";

    private static EvoSuiteConsole instance;

    private final MessageConsole console;
    private final MessageConsoleStream out;

    private EvoSuiteConsole(MessageConsole console) {
        this.console = console;
        this.out = console.newMessageStream();
    }

    /** The console, created on first use and again if the user has removed it. */
    public static synchronized EvoSuiteConsole get() {
        IConsoleManager manager = ConsolePlugin.getDefault().getConsoleManager();
        if (instance == null || !Arrays.asList(manager.getConsoles()).contains(instance.console)) {
            MessageConsole console = new MessageConsole(NAME,
                    ResourceLocator.imageDescriptorFromBundle(Activator.PLUGIN_ID, "icons/evosuite.gif").orElse(null));
            manager.addConsoles(new IConsole[]{console});
            instance = new EvoSuiteConsole(console);
        }
        return instance;
    }

    public void show() {
        ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
    }

    public void println(String line) {
        out.println(line);
    }
}
