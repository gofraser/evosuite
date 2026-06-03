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
package org.evosuite.ga.diversity;

/**
 * Mutable counters for species-protection decisions within a generation.
 */
public final class SpeciesProtectionStats {

    private int quotaProtectedCount;
    private int newbornProtectedCount;
    private int incubatorProtectedCount;
    private int sharingAdjustedCount;

    public int getQuotaProtectedCount() {
        return quotaProtectedCount;
    }

    public int getNewbornProtectedCount() {
        return newbornProtectedCount;
    }

    public int getSharingAdjustedCount() {
        return sharingAdjustedCount;
    }

    public int getIncubatorProtectedCount() {
        return incubatorProtectedCount;
    }

    public void incrementQuotaProtected() {
        quotaProtectedCount++;
    }

    public void incrementNewbornProtected() {
        newbornProtectedCount++;
    }

    public void incrementSharingAdjusted() {
        sharingAdjustedCount++;
    }

    public void incrementIncubatorProtected() {
        incubatorProtectedCount++;
    }

    public void clear() {
        quotaProtectedCount = 0;
        newbornProtectedCount = 0;
        incubatorProtectedCount = 0;
        sharingAdjustedCount = 0;
    }
}
