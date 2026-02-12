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
package org.evosuite.runtime.vfs;

import org.evosuite.runtime.RuntimeSettings;

import java.io.File;

/**
 * Parent class for both files and folders.
 *
 * @author arcuri
 */
public abstract class FSObject {

    private volatile boolean readPermission;

    private volatile boolean writePermission;

    private volatile boolean executePermission;

    /**
     * Normalized path uniquely identifying this file on the VFS.
     */
    protected volatile String path;

    /**
     * The direct parent folder.
     */
    protected final VFolder parent;

    /**
     * Even if file is removed from file system, some threads could still have
     * references to it. So, long/expensive operations could be stopped here if
     * file is deleted
     */
    protected volatile boolean deleted;

    protected volatile long lastModified;

    /**
     * Creates a new FSObject with the given path and parent folder.
     *
     * @param path   the path of the object
     * @param parent the parent folder
     */
    public FSObject(String path, VFolder parent) {
        readPermission = true;
        writePermission = true;
        executePermission = true;
        this.parent = parent;
        this.deleted = false;
        this.lastModified = getCurrentTimeMillis();

        if (isSpecialWindowsRoot(path)) {
            //this means we are in Windows, and we are handling C: root folder
            this.path = path;
        } else {
            this.path = normalizePath(path);
        }
    }

    protected long getCurrentTimeMillis() {
        if (RuntimeSettings.mockJVMNonDeterminism) {
            return org.evosuite.runtime.System.getCurrentTimeMillisForVFS();
        } else {
            return java.lang.System.currentTimeMillis();
        }
    }


    private boolean isSpecialWindowsRoot(String givenPath) {
        return parent != null && givenPath != null && parent.isRoot()
                && givenPath.endsWith(":") && !File.separator.equals("/");
    }

    /**
     * Renames this object to a new path.
     *
     * @param newPath the new path
     * @return true if the rename was successful
     */
    public boolean rename(String newPath) {

        if (!isWritePermission() || !parent.isWritePermission()) {
            return false;
        }

        path = newPath;

        return true;
    }

    /**
     * Deletes this object from its parent folder and marks it as deleted.
     *
     * @return true if the object was successfully deleted
     */
    public boolean delete() {
        parent.removeChild(getName());
        deleted = true;
        return deleted;
    }

    /**
     * Checks whether this object is a folder.
     *
     * @return true if this object is a folder
     */
    public boolean isFolder() {
        return this instanceof VFolder;
    }

    /**
     * Returns the name of this object.
     *
     * @return the name of the object
     */
    public String getName() {
        if (path == null) {
            return null;
        }

        if (isSpecialWindowsRoot(path)) {
            return path;
        } else {
            return new File(path).getName();
        }
    }

    /**
     * Normalizes the given raw path.
     *
     * @param rawPath the raw path to normalize
     * @return the normalized path
     */
    public String normalizePath(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        return new File(rawPath).getAbsolutePath();
    }

    public boolean isReadPermission() {
        return readPermission;
    }


    public void setReadPermission(boolean readPermission) {
        this.readPermission = readPermission;
    }


    public boolean isWritePermission() {
        return writePermission;
    }


    public void setWritePermission(boolean writePermission) {
        this.writePermission = writePermission;
    }


    public boolean isExecutePermission() {
        return executePermission;
    }


    public void setExecutePermission(boolean executePermission) {
        this.executePermission = executePermission;
    }

    public String getPath() {
        return path == null ? "" : path;
    }

    /**
     * Checks whether this file or folder has been deleted.
     *
     * <p>Once a file/folder is deleted, it shouldn't be accessible any more from the VFS.
     * But in case some thread holds a reference to this instance, we need to mark
     * that it is supposed to be deleted.
     *
     * @return {@code true} if this object has been deleted, {@code false} otherwise
     */
    public boolean isDeleted() {
        return deleted;
    }

    @Override
    public String toString() {
        return getPath();
    }

    /**
     * Returns the time that this object was last modified.
     *
     * @return the last modified time in milliseconds.
     */
    public long getLastModified() {
        return lastModified;
    }

    /**
     * Sets the last modified time of this object.
     *
     * @param lastModified the last modified time in milliseconds
     * @return true if the last modified time was successfully set
     */
    public boolean setLastModified(long lastModified) {
        //TODO check all of its callers, and  if should simulate time

        if (!this.isWritePermission()) {
            return false;
        }
        this.lastModified = lastModified;
        return true;
    }

    public VFolder getParent() {
        return parent;
    }
}
