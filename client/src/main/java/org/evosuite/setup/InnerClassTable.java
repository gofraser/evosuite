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
package org.evosuite.setup;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The member-class entries of the {@code InnerClasses} table in a class file, which is where javac
 * reads the source access flags of a member class.
 */
final class InnerClassTable {
    private static final Logger logger = LoggerFactory.getLogger(InnerClassTable.class);

    /** One member-class entry: the internal name of the class, of its outer class, and its access flags. */
    static final class Entry {
        final String name;
        final String outerName;
        final int access;

        Entry(String name, String outerName, int access) {
            this.name = name;
            this.outerName = outerName;
            this.access = access;
        }
    }

    private InnerClassTable() {
    }

    /**
     * Reads the member-class entries of a class file, in table order.
     *
     * @param loader   the class loader to read the class file from; the system class loader if null
     * @param resource the class file, e.g. {@code org/example/Outer.class}
     * @return the entries, or an empty list if the class file is missing or unreadable
     */
    static List<Entry> read(ClassLoader loader, String resource) {
        List<Entry> entries = new ArrayList<>();
        try (InputStream input = loader == null
                ? ClassLoader.getSystemResourceAsStream(resource)
                : loader.getResourceAsStream(resource)) {
            if (input != null) {
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInnerClass(String name, String outerName, String innerName,
                                                int access) {
                        if (outerName != null) {
                            entries.add(new Entry(name, outerName, access));
                        }
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        } catch (IOException | RuntimeException e) {
            logger.debug("Cannot read the InnerClasses table of {}", resource, e);
        }
        return entries;
    }
}
