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

import java.util.HashMap;

/**
 * Minimal stand-in for {@code com.fasterxml.jackson.databind.deser.BasicDeserializerFactory},
 * which declares {@code static final HashMap<...> _collectionFallbacks}. The field is
 * package-private, not private, and non-primitive: visibility alone would permit a direct
 * write from a generated test in this package, so only finality must forbid it.
 */
public class StaticFinalFieldHolder {

    static final HashMap<String, Object> collectionFallbacks = new HashMap<>();

    /**
     * Same shape as {@link #collectionFallbacks} but not final, so a direct write to
     * it is legal Java - used to confirm the final-field guard rejects only final
     * targets, not every field target.
     */
    static HashMap<String, Object> nonFinalCollectionFallbacks = new HashMap<>();

    static {
        collectionFallbacks.put("a", Integer.class);
        collectionFallbacks.put("b", Long.class);
    }

    private StaticFinalFieldHolder() {
    }
}
