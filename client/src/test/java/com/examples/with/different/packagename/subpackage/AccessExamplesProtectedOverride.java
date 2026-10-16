/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 */
package com.examples.with.different.packagename.subpackage;

import com.examples.with.different.packagename.AccessExamples;

/** A public override of a protected method declared in another package. */
public class AccessExamplesProtectedOverride extends AccessExamples {

    @Override
    public void protectedMethod() {
    }
}
