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
package org.evosuite.runtime.testdata;

import org.evosuite.runtime.vfs.FSObject;
import org.evosuite.runtime.vfs.VFile;
import org.evosuite.runtime.vfs.VirtualFileSystem;

/**
 * This class is used create files as test data
 * in the test cases.
 *
 * <p>The methods in this class are the main ones that are going
 * to be used in the generated JUnit files to manipulate
 * the virtual file system.
 * Note: if SUT takes as input a {@code File}, then it can happen
 * that mock {@code java.io} objects manipulating the VFS will appear in the test
 * cases.
 *
 * @author arcuri
 */
public class FileSystemHandling {

    /**
     * Append a string to the given file.
     * If the file does not exist, it will be created.
     *
     * @param file  the file to append to
     * @param value the string to append
     * @return {@code true} if the string was appended, {@code false} otherwise
     */
    public static boolean appendStringToFile(EvoSuiteFile file, String value) {

        if (file == null || value == null) {
            return false;
        }

        return appendDataToFile(file, value.getBytes());
    }

    /**
     * Append a string to the given file, and then move cursor
     * to the next line.
     * If the file does not exist, it will be created.
     *
     * @param file the file to append to
     * @param line the line to append
     * @return {@code true} if the line was appended, {@code false} otherwise
     */
    public static boolean appendLineToFile(EvoSuiteFile file, String line) {

        if (file == null || line == null) {
            return false;
        }

        return appendStringToFile(file, line + "\n");
    }


    /**
     * Append a byte array to the given file.
     * If the file does not exist, it will be created.
     *
     * @param file the file to append to
     * @param data the data to append
     * @return {@code true} if the data was appended, {@code false} otherwise
     */
    public static boolean appendDataToFile(EvoSuiteFile file, byte[] data) {

        if (file == null || data == null) {
            return false;
        }

        FSObject target = VirtualFileSystem.getInstance().findFSObject(file.getPath());
        //can we write to it?
        if (target != null && (target.isFolder() || !target.isWritePermission())) {
            return false;
        }

        if (target == null) {
            //if it does not exist, let's create it
            boolean created = VirtualFileSystem.getInstance().createFile(file.getPath());
            if (!created) {
                return false;
            }
            target = VirtualFileSystem.getInstance().findFSObject(file.getPath());
            assert target != null;
        }

        VFile vf = (VFile) target;
        vf.writeBytes(data, 0, data.length);

        return true;
    }


    /**
     * Create a folder.
     *
     * @param file the folder to create
     * @return {@code true} if the folder was created, {@code false} otherwise
     */
    public static boolean createFolder(EvoSuiteFile file) {

        if (file == null) {
            return false;
        }

        return VirtualFileSystem.getInstance().createFolder(file.getPath());
    }

    /**
     * Set read/write/execute permissions to the given file.
     *
     * @param file         the file to set permissions for
     * @param isReadable   read permission
     * @param isWritable   write permission
     * @param isExecutable execute permission
     * @return {@code true} if permissions were set, {@code false} otherwise
     */
    public static boolean setPermissions(EvoSuiteFile file, boolean isReadable, boolean isWritable,
                                         boolean isExecutable) {
        if (file == null) {
            return false;
        }
        FSObject target = VirtualFileSystem.getInstance().findFSObject(file.getPath());
        if (target == null) {
            return false;
        }

        target.setExecutePermission(isReadable);
        target.setWritePermission(isWritable);
        target.setExecutePermission(isExecutable);
        return true;
    }

    /**
     * All operations on the given {@code file} will throw an IOException if that
     * appears in their method signature.
     *
     * @param file the file on which IOExceptions should be thrown
     * @return {@code true} if the configuration was successful, {@code false} otherwise
     */
    public static boolean shouldThrowIOException(EvoSuiteFile file) {
        if (file == null) {
            return false;
        }
        return VirtualFileSystem.getInstance().setShouldThrowIOException(file);
    }

    /**
     * All operations in the entire VFS will throw an IOException if that
     * appears in their method signature.
     *
     * @return {@code true} if the configuration was successful
     */
    public static boolean shouldAllThrowIOExceptions() {
        return VirtualFileSystem.getInstance().setShouldAllThrowIOExceptions();
    }
}
