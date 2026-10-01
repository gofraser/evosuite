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
 * Implements two unrelated interfaces that each declare a same-named constant of a different,
 * incompatible type, and declares no {@code VALUE} field of its own. Per JLS 8.3/9.3, neither
 * {@link DiamondInterfaceA#VALUE} nor {@link DiamondInterfaceB#VALUE} hides the other (they are
 * siblings, not super/subtype), so both are inherited and an *unqualified* reference such as
 * {@code DiamondInterfaceImpl.VALUE} or {@code instance.VALUE} is a compile-time "reference to
 * VALUE is ambiguous" error; only the qualified forms {@code DiamondInterfaceA.VALUE} /
 * {@code DiamondInterfaceB.VALUE} compile. See {@code TestClusterUtilsFieldHidingTest} for how
 * {@code TestClusterUtils#getFields(Class)} handles this.
 */
public class DiamondInterfaceImpl implements DiamondInterfaceA, DiamondInterfaceB {
}
