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
package org.evosuite.runtime.mock.javax.swing.filechooser;

import javax.swing.UIManager;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;

class MockUnixFileSystemView extends MockFileSystemView {

    private static final String newFolderString =
            UIManager.getString("FileChooser.other.newFolder");
    private static final String newFolderNextString  =
            UIManager.getString("FileChooser.other.newFolder.subsequent");

    public File createNewFolder(File containingDir) throws IOException {
        if (containingDir == null) {
            throw new IOException("Containing directory is null:");
        }

        File newFolder;
        // Unix - using OpenWindows' default folder name. Can't find one for Motif/CDE.
        newFolder = createFileObject(containingDir, newFolderString);
        int i = 1;
        while (newFolder.exists() && i < 100) {
            newFolder = createFileObject(containingDir, MessageFormat.format(
                    newFolderNextString, i));
            i++;
        }

        if (newFolder.exists()) {
            throw new IOException("Directory already exists:" + newFolder.getAbsolutePath());
        } else {
            newFolder.mkdirs();
        }

        return newFolder;
    }

    public boolean isFileSystemRoot(File dir) {
        return dir != null && dir.getAbsolutePath().equals("/");
    }

    public boolean isDrive(File dir) {
        return isFloppyDrive(dir);
    }

    public boolean isFloppyDrive(File dir) {
        // Could be looking at the path for Solaris, but wouldn't be reliable.
        // For example:
        // return (dir != null && dir.getAbsolutePath().toLowerCase().startsWith("/floppy"));
        return false;
    }

    public boolean isComputerNode(File dir) {
        if (dir != null) {
            String parent = dir.getParent();
            if (parent != null && parent.equals("/net")) {
                return true;
            }
        }
        return false;
    }
}
