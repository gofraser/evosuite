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

import java.lang.reflect.Method;

/**
 * Provides stable placeholder members for best-effort master-side deserialization.
 */
final class SerializationFallbackMember {

    private SerializationFallbackMember() {
    }

    public void unresolvedMemberPlaceholder() {
        // no-op placeholder used only for metadata fallback
    }

    static Method getFallbackMethod() {
        try {
            return SerializationFallbackMember.class.getDeclaredMethod("unresolvedMemberPlaceholder");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing placeholder method", e);
        }
    }
}
