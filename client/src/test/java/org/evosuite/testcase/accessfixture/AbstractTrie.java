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
package org.evosuite.testcase.accessfixture;

import java.util.HashMap;
import java.util.Map;

/**
 * A package-private generic base class whose public methods return its type variables, the
 * shape of commons-collections' {@code AbstractPatriciaTrie<K, V>}.
 */
abstract class AbstractTrie<K, V> {

    private final Map<K, V> entries = new HashMap<>();

    public V put(K key, V value) {
        return entries.put(key, value);
    }

    public V get(K key) {
        return entries.get(key);
    }
}
