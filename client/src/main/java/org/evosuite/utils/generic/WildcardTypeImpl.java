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
package org.evosuite.utils.generic;

import com.googlecode.gentyref.GenericTypeReflector;

import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class WildcardTypeImpl implements WildcardType {

    private Type[] upperBounds;
    private Type[] lowerBounds;

    /**
     * Constructor.
     *
     * @param upperBounds the upper bounds
     * @param lowerBounds the lower bounds
     */
    public WildcardTypeImpl(Type[] upperBounds, Type[] lowerBounds) {
        if (upperBounds.length == 0) {
            throw new IllegalArgumentException(
                    "There must be at least one upper bound. For an unbound wildcard, the upper bound must be Object");
        }
        this.upperBounds = flattenUpperBounds(upperBounds);
        this.lowerBounds = flattenLowerBounds(lowerBounds);
    }

    /**
     * A wildcard bound can never itself be a wildcard ("? extends ?" is not
     * Java), but type-variable substitution produces exactly that when a
     * variable inside a bound is replaced by a wildcard: List<? extends E>
     * with E := ? becomes List<? extends ?>. gentyref's erase() then throws
     * "not supported: WildcardTypeImpl" when the test is rendered, which
     * aborts the whole search. Collapse the nesting instead:
     * {@code ? extends (? extends X)} is {@code ? extends X}, and
     * {@code ? extends (? super X)} or {@code ? extends ?} is just {@code ?}.
     */
    private static Type[] flattenUpperBounds(Type[] bounds) {
        List<Type> flat = new ArrayList<>(bounds.length);
        for (Type bound : bounds) {
            if (bound instanceof WildcardType) {
                WildcardType nested = (WildcardType) bound;
                if (nested.getLowerBounds().length == 0) {
                    flat.addAll(Arrays.asList(flattenUpperBounds(nested.getUpperBounds())));
                } else {
                    flat.add(Object.class);
                }
            } else {
                flat.add(bound);
            }
        }
        if (flat.size() > 1) {
            // "? extends Object & X" is just "? extends X"
            flat.removeIf(t -> t == Object.class);
            if (flat.isEmpty()) {
                flat.add(Object.class);
            }
        }
        return flat.toArray(new Type[0]);
    }

    /**
     * Counterpart of {@link #flattenUpperBounds(Type[])} for lower bounds:
     * {@code ? super (? super X)} is {@code ? super X}, while
     * {@code ? super (? extends X)} or {@code ? super ?} constrains nothing
     * and degrades to the unbounded wildcard.
     */
    private static Type[] flattenLowerBounds(Type[] bounds) {
        List<Type> flat = new ArrayList<>(bounds.length);
        for (Type bound : bounds) {
            if (bound instanceof WildcardType) {
                WildcardType nested = (WildcardType) bound;
                if (nested.getLowerBounds().length > 0) {
                    flat.addAll(Arrays.asList(flattenLowerBounds(nested.getLowerBounds())));
                }
            } else {
                flat.add(bound);
            }
        }
        return flat.toArray(new Type[0]);
    }

    @Override
    public Type[] getUpperBounds() {
        return upperBounds.clone();
    }

    @Override
    public Type[] getLowerBounds() {
        return lowerBounds.clone();
    }

    public void setUpperBounds(Type[] bounds) {
        this.upperBounds = flattenUpperBounds(bounds);
    }

    public void setLowerBounds(Type[] bounds) {
        this.lowerBounds = flattenLowerBounds(bounds);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof WildcardType)) {
            return false;
        }
        WildcardType other = (WildcardType) obj;
        return Arrays.equals(lowerBounds, other.getLowerBounds())
                && Arrays.equals(upperBounds, other.getUpperBounds());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(lowerBounds) ^ Arrays.hashCode(upperBounds);
    }

    @Override
    public String toString() {
        if (lowerBounds.length > 0) {
            return "? super " + stringifyTypeVariable(lowerBounds[0]);
        } else if (upperBounds[0] == Object.class) {
            return "?";
        } else {
            return "? extends " + stringifyTypeVariable(upperBounds[0]);
        }
    }


    private static String stringifyTypeVariable(Type upperBound) {
        if (upperBound instanceof TypeVariable<?>) {
            return "(" + upperBound.getTypeName() + " extends "
                    + Arrays.toString(((TypeVariable<?>) upperBound).getBounds()) + ")";
        }

        return GenericTypeReflector.getTypeName(upperBound);

    }
}
