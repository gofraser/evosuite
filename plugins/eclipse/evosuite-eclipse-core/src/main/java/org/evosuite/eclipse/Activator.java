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
package org.evosuite.eclipse;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * Plug-in life cycle. The plug-in has no state of its own beyond its preferences, which the
 * workbench manages; everything else is created per generation request.
 */
public class Activator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "org.evosuite.plugins.eclipse.core";

    private static Activator plugin;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        plugin = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return plugin;
    }

    public static void log(IStatus status) {
        Activator instance = getDefault();
        if (instance != null) {
            instance.getLog().log(status);
        }
    }

    public static void logError(String message, Throwable t) {
        log(error(message, t));
    }

    public static IStatus warning(String message) {
        return new Status(IStatus.WARNING, PLUGIN_ID, message);
    }

    public static IStatus error(String message) {
        return new Status(IStatus.ERROR, PLUGIN_ID, message);
    }

    public static IStatus error(String message, Throwable t) {
        return new Status(IStatus.ERROR, PLUGIN_ID, message, t);
    }
}
