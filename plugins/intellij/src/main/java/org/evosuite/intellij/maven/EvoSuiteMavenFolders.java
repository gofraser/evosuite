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
package org.evosuite.intellij.maven;

import org.evosuite.ide.PomSettings;
import org.jetbrains.idea.maven.importing.MavenWorkspaceConfigurator;

import java.util.stream.Stream;

public final class EvoSuiteMavenFolders implements MavenWorkspaceConfigurator {
    @Override
    public Stream<AdditionalFolder> getAdditionalFolders(FoldersContext context) {
        var model = context.getMavenProject();
        var plugin = model.findPlugin(PomSettings.GROUP_ID, PomSettings.ARTIFACT_ID);
        return MavenSupport.setupExecutions(plugin).stream()
                .map(execution -> new AdditionalFolder(
                        MavenSupport.setupFolder(model, plugin, execution).toString(), FolderType.TEST_SOURCE));
    }
}
