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

class MockWindowsFileSystemView extends MockFileSystemView {

    private static final String newFolderString =
            UIManager.getString("FileChooser.win32.newFolder");
    private static final String newFolderNextString  =
            UIManager.getString("FileChooser.win32.newFolder.subsequent");

    public Boolean isTraversable(File f) {
        return isFileSystemRoot(f) || isComputerNode(f) || f.isDirectory();
    }

    public File getChild(File parent, String fileName) {
        if (fileName.startsWith("\\")
                && !fileName.startsWith("\\\\")
                && isFileSystem(parent)) {

            //Path is relative to the root of parent's drive
            String path = parent.getAbsolutePath();
            if (path.length() >= 2
                    && path.charAt(1) == ':'
                    && Character.isLetter(path.charAt(0))) {

                return createFileObject(path.substring(0, 2) + fileName);
            }
        }
        return super.getChild(parent, fileName);
    }

    public String getSystemTypeDescription(File f) {
        return super.getSystemTypeDescription(f);
        /*
        if (f == null) {
            return null;
        }

        try {
            return getShellFolder(f).getFolderType();
        } catch (FileNotFoundException e) {
            return null;
        }
        */
    }

    /**
     * Returns the home directory on the drive the code exists on.
     * @return the home directory on the drive the code exists on.
     */
    public File getHomeDirectory() {
        File executionPath = createFileObject(System.getProperty("user.dir"));
        File[] roots = getRoots();

        for (File root : roots) {
            if (root.toPath().getRoot().equals(executionPath.toPath().getRoot())) {
                return root;
            }
        }

        return roots[0];
    }

    /**
     * Creates a new folder with a default folder name.
     */
    public File createNewFolder(File containingDir) throws IOException {
        if (containingDir == null) {
            throw new IOException("Containing directory is null:");
        }
        // Using NT's default folder name
        File newFolder = createFileObject(containingDir, newFolderString);
        int i = 2;
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

    public boolean isDrive(File dir) {
        return isFileSystemRoot(dir);
    }

    public boolean isFloppyDrive(final File dir) {
        /*
        String path = AccessController.doPrivileged(new PrivilegedAction<String>() {
            public String run() {
                return dir.getAbsolutePath();
            }
        });
         */
        String path = dir.getAbsolutePath();
        return path != null && (path.equals("A:\\") || path.equals("B:\\"));
    }

    /**
     * Returns a File object constructed from the given path string.
     */
    public File createFileObject(String path) {
        // Check for missing backslash after drive letter such as "C:" or "C:filename"
        if (path.length() >= 2 && path.charAt(1) == ':' && Character.isLetter(path.charAt(0))) {
            if (path.length() == 2) {
                path += "\\";
            } else if (path.charAt(2) != '\\') {
                path = path.substring(0, 2) + "\\" + path.substring(2);
            }
        }
        return super.createFileObject(path);
    }

    protected File createFileSystemRoot(File f) {
        // Problem: Removable drives on Windows return false on f.exists()
        // Workaround: Override exists() to always return true.
        return new MockFileSystemRoot(f) {
            private static final long serialVersionUID = 1L;
            public boolean exists() {
                return true;
            }
        };
    }

}
