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

/**
 * Representation of a virtual file.
 *
 * @author arcuri
 */
public class VFile extends FSObject {

    /**
     * The actual data contained in file as a list of bytes.
     */
    private final List<Byte> data;

    /**
     * Creates a new VFile with the given path and parent folder.
     *
     * @param path   the path of the file
     * @param parent the parent folder
     */
    public VFile(String path, VFolder parent) {
        super(path, parent);

        //TODO might need a better type of data structure supporting multi-threading
        data = new ArrayList<>(1024);
    }

    /**
     * Erases all data contained in this file.
     */
    public void eraseData() {
        data.clear();
    }

    /**
     * Returns the size of the data in this file.
     *
     * @return the number of bytes in the file
     */
    public synchronized int getDataSize() {
        return data.size();
    }


    /**
     * Sets the length of the file data.
     *
     * @param newLength the new length in bytes
     */
    public synchronized void setLength(int newLength) {

        /*
         * Note: this implementation is not particularly efficient...
         * but setLength is rarely called
         */

        while (newLength > data.size()) {
            data.add((byte) 0);
        }

        if (newLength == 0) {
            data.clear();
            return;
        }

        while (data.size() > newLength) {
            data.remove(data.size() - 1);
        }
    }

    /**
     * Reads the byte at the specified position.
     *
     * @param position the position in the file to read from
     * @return a converted unsigned int [0,255] representation of the [-128,127] byte at
     *     {@code position}, or -1 if position is at or past the end of the file
     * @throws IllegalArgumentException if position is negative
     */
    public synchronized int read(int position) throws IllegalArgumentException {
        if (position < 0) {
            throw new IllegalArgumentException("Position in the file cannot be negative");
        }

        if (position >= data.size()) {
            return -1; //this represent the end of the stream
        }

        return data.get(position) & 0xFF;
    }

    /**
     * Writes bytes to the end of the file.
     *
     * @param b   the byte array to write
     * @param off the start offset in the data
     * @param len the number of bytes to write
     * @return the number of bytes actually written
     */
    public synchronized int writeBytes(byte[] b, int off, int len) {
        return writeBytes(data.size(), b, off, len);
    }


    /**
     * Writes bytes to the file at the specified position.
     *
     * @param position the position in the file to write to
     * @param b the byte array to write
     * @param off the start offset in the data
     * @param len the number of bytes to write
     * @return the number of bytes actually written
     * @throws IllegalArgumentException if position is negative
     */
    public synchronized int writeBytes(int position, byte[] b, int off, int len) throws IllegalArgumentException {

        if (position < 0) {
            throw new IllegalArgumentException("Position in the file cannot be negative");
        }

        if (deleted || !isWritePermission()) {
            return 0;
        }

        if (position >= data.size()) {
            setLength(position);
        }

        int written = 0;
        for (int i = off; i < b.length & (i - off) < len; i++) {
            if (position < data.size()) {
                data.set(position, (b[i]));
            } else {
                data.add(b[i]);
            }
            position++;
            written++;
        }

        setLastModified(getCurrentTimeMillis());

        return written;
    }


    @Override
    public synchronized boolean delete() {
        eraseData();
        return super.delete();
    }
}
