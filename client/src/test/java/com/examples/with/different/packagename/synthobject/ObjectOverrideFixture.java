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
package com.examples.with.different.packagename.synthobject;

/** Public overrides of Object.finalize() and Object.clone() that null out a field. */
public class ObjectOverrideFixture implements Cloneable {

    private Object data = new Object();

    public boolean target() {
        return data == null;
    }

    @Override
    public void finalize() throws Throwable {
        data = null;
        super.finalize();
    }

    @Override
    public Object clone() {
        ObjectOverrideFixture copy = new ObjectOverrideFixture();
        copy.data = null;
        data = null;
        return copy;
    }
}
