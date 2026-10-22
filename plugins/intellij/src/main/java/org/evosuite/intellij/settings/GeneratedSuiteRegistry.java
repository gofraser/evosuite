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
package org.evosuite.intellij.settings;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.*;

@Service(Service.Level.PROJECT)
@State(name = "EvoSuiteGeneratedSuites", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class GeneratedSuiteRegistry
        implements PersistentStateComponent<GeneratedSuiteRegistry.State> {
    public static final class State {
        public Map<String, String> runtimeFreeSuites = new TreeMap<>();
    }

    private State state = new State();

    public static GeneratedSuiteRegistry getInstance(Project project) {
        return project.getService(GeneratedSuiteRegistry.class);
    }

    public synchronized State getState() {
        State copy = new State();
        copy.runtimeFreeSuites.putAll(state.runtimeFreeSuites);
        return copy;
    }

    public synchronized void loadState(@NotNull State value) {
        state = value;
    }

    public synchronized boolean owns(Path path) {
        return state.runtimeFreeSuites.containsKey(path.toAbsolutePath().normalize().toString());
    }

    private static final java.util.regex.Pattern MARKER =
            java.util.regex.Pattern.compile(
                    "(?m)^// EvoSuite IntelliJ generated suite: ([a-f0-9-]{36})$");

    public synchronized String decorate(Path path, String source) {
        String id =
                state.runtimeFreeSuites.getOrDefault(
                        path.toAbsolutePath().normalize().toString(), UUID.randomUUID().toString());
        return "// EvoSuite IntelliJ generated suite: " + id + "\n" + source;
    }

    /** Mark Reviewed is an explicit acceptance of copied runtime-free comparison content. */
    public synchronized void recordAccepted(Path path, String generated) {
        try {
            var expected = MARKER.matcher(generated);
            var actual = MARKER.matcher(currentSource(path));
            if (expected.find() && actual.find() && expected.group(1).equals(actual.group(1)))
                state.runtimeFreeSuites.put(
                        path.toAbsolutePath().normalize().toString(), actual.group(1));
        } catch (java.io.IOException ignored) {
        }
    }

    private static String currentSource(Path path) throws java.io.IOException {
        var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(path);
        var document =
                file == null
                        ? null
                        : com.intellij
                                .openapi
                                .fileEditor
                                .FileDocumentManager
                                .getInstance()
                                .getCachedDocument(file);
        return document == null ? java.nio.file.Files.readString(path) : document.getText();
    }

    public synchronized boolean owns(Path path, String source) {
        var marker = MARKER.matcher(source);
        return marker.find()
                && marker.group(1)
                        .equals(
                                state.runtimeFreeSuites.get(
                                        path.toAbsolutePath().normalize().toString()));
    }

    public synchronized void record(Path path, boolean runtimeFree) {
        String key = path.toAbsolutePath().normalize().toString();
        if (runtimeFree) {
            try {
                var marker = MARKER.matcher(currentSource(path));
                if (marker.find()) {
                    state.runtimeFreeSuites.put(key, marker.group(1));
                    return;
                }
            } catch (java.io.IOException ignored) {
            }
        }
        state.runtimeFreeSuites.remove(key);
    }
}
