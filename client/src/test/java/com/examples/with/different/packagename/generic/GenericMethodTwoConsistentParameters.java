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
import java.util.Set;

/**
 * Method analogue of {@link GenericConstructorTwoConsistentParameters}: a non-static,
 * non-generic (no method-level type parameters of its own) method whose two parameters
 * both mention the class type variable E, so calling it correctly requires the owner's
 * E to be bound to a single type before the parameters are resolved -- exactly the
 * {@code TestMutator.changeCall} method branch that binds a non-static generic method's
 * owner type variables from its callee.
 */
public class GenericMethodTwoConsistentParameters<E> {

    public boolean merge(List<E> list, Set<E> set) {
        return list.size() == set.size();
    }
}
