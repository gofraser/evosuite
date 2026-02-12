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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A virtual folder.
 *
 * @author arcuri
 */
public class VFolder extends FSObject {

    private final List<FSObject> children;

    /**
     * Creates a new VFolder with the given path and parent.
     *
     * @param path   the path of the folder
     * @param parent the parent folder
     */
    public VFolder(String path, VFolder parent) {
        super(path, parent);

        children = new CopyOnWriteArrayList<>();
    }

    @Override
    public boolean delete() {
        if (children.size() > 0) {
            return false;
        }

        return super.delete();
    }

    /**
     * Checks whether this folder is the root folder.
     *
     * @return true if this is the root folder
     */
    public boolean isRoot() {
        return parent == null && path == null;
    }

    /**
     * Adds a child object to this folder.
     *
     * @param child the child object to add
     */
    public void addChild(FSObject child) {
        children.add(child);
    }

    /**
     * Removes a child object from this folder by name.
     *
     * @param name the name of the child object to remove
     * @return true if the child was successfully removed
     * @throws IllegalArgumentException if the name is null or empty
     */
    public boolean removeChild(String name) throws IllegalArgumentException {

        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Empty name");
        }

        for (FSObject element : children) {
            if (name.equals(element.getName())) {
                return children.remove(element);
            }
        }

        return false;
    }

    /**
     * Checks whether this folder has a child with the given name.
     *
     * @param name the name of the child to check
     * @return true if a child with the given name exists, false otherwise
     */
    public boolean hasChild(String name) {
        return getChild(name) != null;
    }

    /**
     * Returns an array of the names of all children in this folder.
     *
     * @return an array of child names
     */
    public String[] getChildrenNames() {
        List<String> list = new ArrayList<>(children.size());
        for (final FSObject child : children) {
            list.add(child.getName());
        }
        return list.toArray(new String[0]);
    }

    /**
     * Returns the child object with the given name.
     *
     * @param name the name of the child to return
     * @return the child object, or null if not found
     * @throws IllegalArgumentException if the name is null or empty
     */
    public FSObject getChild(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Empty name");
        }

        for (final FSObject current : children) {
            if (name.equals(current.getName())) {
                return current;
            }
        }

        return null;
    }
}
