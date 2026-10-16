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
package com.examples.with.different.packagename;

/**
 * Mirrors {@code jigl.image.io.ImageInputStream.ImageFileType}: a private nested
 * enum whose constants are reachable (e.g. via a public accessor) but whose
 * declaring type can never be named from a generated test, not even as
 * {@code Type.CONSTANT} from the same package.
 */
public class PrivateNestedEnumFixture {

    private enum Status {
        ACTIVE, INACTIVE
    }

    private Status status = Status.ACTIVE;

    public Object status() {
        return status;
    }

    /**
     * Same private-enum value as {@link #status()}, but declared to return
     * {@link Enum} rather than {@link Object}: the nearest concrete, accessible
     * supertype a source-private enum constant can be exposed as.
     */
    public Enum<?> statusAsEnum() {
        return status;
    }

    public static Object activeStatus() {
        return Status.ACTIVE;
    }
}
