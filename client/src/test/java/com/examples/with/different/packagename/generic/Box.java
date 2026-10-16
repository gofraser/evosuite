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
package com.examples.with.different.packagename.generic;

import java.util.List;

/**
 * Mirrors {@code org.apache.commons.collections4.list.SetUniqueList} (Defects4J
 * Collections-21f): a generic class ({@code Box<E>}) with a static factory method that
 * declares its own method-level type variable, also named {@code E}, whose return type
 * is the owning (generic) class parameterized by that method type variable. Since the
 * factory is static, the callee/owner is used raw (there is no instance to carry a
 * binding for the class's own {@code E}), which must not cause the method's own
 * (separately bound) {@code E} to be erased from the printed declaration too.
 */
public class Box<E> {

    private final E value;

    private Box(E value) {
        this.value = value;
    }

    public static <E> Box<E> of(List<E> list) {
        return new Box<>(list.isEmpty() ? null : list.get(0));
    }

    public E get(int index) {
        return value;
    }
}
