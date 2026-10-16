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
 * A public nested enum and a package-private nested enum, side by side with
 * {@link PrivateNestedEnumFixture}'s private one. Both of these, unlike the
 * private case, can legitimately be named as {@code Type.CONSTANT} in a generated
 * test -- the public one unconditionally, the package-private one only when the
 * generated test sits in this fixture's own package.
 */
public class NestedEnumAccessibilityFixture {

    public enum PublicStatus {
        ACTIVE, INACTIVE
    }

    enum PackagePrivateStatus {
        ACTIVE, INACTIVE
    }

    private PublicStatus publicStatus = PublicStatus.ACTIVE;
    private PackagePrivateStatus packagePrivateStatus = PackagePrivateStatus.ACTIVE;

    public Object publicStatus() {
        return publicStatus;
    }

    public Object packagePrivateStatus() {
        return packagePrivateStatus;
    }

    public static Object activePublicStatus() {
        return PublicStatus.ACTIVE;
    }

    public static Object activePackagePrivateStatus() {
        return PackagePrivateStatus.ACTIVE;
    }
}
