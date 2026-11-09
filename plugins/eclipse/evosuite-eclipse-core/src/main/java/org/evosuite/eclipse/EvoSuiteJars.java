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

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.URIUtil;
import org.osgi.framework.Bundle;

/**
 * The jars shipped in the engine bundle: the runnable EvoSuite jar, and the standalone runtime
 * that generated tests depend on.
 */
public final class EvoSuiteJars {

    public static final String ENGINE_BUNDLE = "org.evosuite.plugins.eclipse.deps";
    public static final String EVOSUITE_JAR = "lib/evosuite.jar";
    public static final String RUNTIME_JAR = "lib/evosuite-standalone-runtime.jar";

    private EvoSuiteJars() {
    }

    public static File evosuite() throws CoreException {
        return locate(EVOSUITE_JAR);
    }

    public static File runtime() throws CoreException {
        return locate(RUNTIME_JAR);
    }

    public static String version() throws CoreException {
        try (var jar = new java.util.jar.JarFile(runtime())) {
            var entry = jar.getJarEntry("META-INF/maven/org.evosuite/evosuite-standalone-runtime/pom.properties");
            if (entry != null) {
                var properties = new java.util.Properties();
                try (var stream = jar.getInputStream(entry)) {
                    properties.load(stream);
                }
                String version = properties.getProperty("version");
                if (version != null && !version.isBlank()) return version;
            }
        } catch (IOException e) {
            throw new CoreException(Activator.error("Cannot read the bundled EvoSuite version.", e));
        }
        throw new CoreException(Activator.error("The bundled EvoSuite runtime has no version metadata."));
    }

    private static File locate(String path) throws CoreException {
        Bundle bundle = Platform.getBundle(ENGINE_BUNDLE);
        if (bundle == null) {
            throw new CoreException(Activator.error("The EvoSuite engine bundle " + ENGINE_BUNDLE
                    + " is not installed. Reinstall the EvoSuite feature."));
        }
        URL entry = bundle.getEntry(path);
        if (entry == null) {
            throw new CoreException(Activator.error("The EvoSuite engine bundle does not contain " + path
                    + ". Reinstall the EvoSuite feature."));
        }
        try {
            // The bundle is installed as a directory, so this is the jar itself, not a copy.
            return URIUtil.toFile(URIUtil.toURI(FileLocator.toFileURL(entry)));
        } catch (IOException | URISyntaxException e) {
            throw new CoreException(Activator.error("Cannot access " + path + " in " + ENGINE_BUNDLE, e));
        }
    }
}
