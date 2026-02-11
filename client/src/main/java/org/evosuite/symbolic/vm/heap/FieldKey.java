/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.symbolic.vm.heap;

/**
 * Representation of an object field key.
 */
public final class FieldKey {

    private final String owner;
    private final String name;

    /**
     * Constructs a FieldKey.
     *
     * @param owner the owner of the field
     * @param name  the name of the field
     */
    public FieldKey(String owner, String name) {
        this.owner = owner;
        this.name = name;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return this.owner.hashCode() + this.name.hashCode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (o != null && o.getClass().equals(FieldKey.class)) {
            FieldKey that = (FieldKey) o;
            return this.owner.equals(that.owner) && this.name.equals(that.name);
        } else {
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return this.owner + "/" + this.name;
    }

}
