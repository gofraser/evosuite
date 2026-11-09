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

import com.intellij.util.PathUtil;

import java.io.IOException;
import java.nio.file.*;

public final class EngineJars {
    public static final String VERSION = version();

    private static String version() {
        try (var stream = EngineJars.class.getResourceAsStream("/evosuite-engine.properties")) {
            if (stream == null) throw new IOException("Missing bundled engine version");
            var metadata = new java.util.Properties();
            metadata.load(stream);
            String version = metadata.getProperty("version", "").trim();
            if (version.isEmpty()) throw new IOException("Missing bundled engine version");
            return version;
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private EngineJars() {}

    public static Path engine() throws IOException {
        return file("evosuite.jar");
    }

    public static Path runtime() throws IOException {
        return file("evosuite-standalone-runtime.jar");
    }

    private static Path file(String name) throws IOException {
        // PluginManager's descriptor lookup APIs became internal in 2026.2. The plugin JAR
        // is installed under lib/, with the engine beside that directory, outside its loader.
        Path pluginJar = Path.of(PathUtil.getJarPathForClass(EngineJars.class));
        Path path = pluginJar.getParent().getParent().resolve("engine").resolve(name);
        if (!Files.isRegularFile(path))
            throw new IOException("Missing bundled " + path + ". Reinstall the EvoSuite plugin.");
        return path;
    }
}
