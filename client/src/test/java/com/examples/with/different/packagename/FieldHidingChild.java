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
 * Stands in for ANTLR3's grammar-generated {@code arrayElement_return}, which declares a
 * package-private {@code CommonTree tree} field that hides the public {@code Object tree}
 * field of its superclass {@link FieldHidingParent} (stood in for by {@code tree}/{@code f}
 * here). Per JLS 8.3, {@code obj.f} resolved through a {@code FieldHidingChild}-typed
 * reference means this narrower field, not the inherited one - so a test cluster that still
 * offered the inherited {@code Object f} as assignable here would generate code that cannot
 * compile, just as {@code arrayElement_return0.tree = (Object) null;} cannot.
 */
public class FieldHidingChild extends FieldHidingParent {

    String f;
}
