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
package org.evosuite.statistics;

import org.evosuite.llm.search.DisruptionRecorder;
import org.evosuite.rmi.ClientServices;

/**
 * The defaults of the search's own timeline and counter columns: species, diversity, fronts,
 * goal counts and disruption. The search tracks them while it runs, but only some algorithms
 * and settings do, so they are published before the search to keep the columns filled in every
 * mode. The master keeps the last value it receives, so what the search tracks replaces them.
 */
public final class SearchRuntimeVariableDefaults {

    private SearchRuntimeVariableDefaults() {
    }

    /** Tracks every column at its empty value. */
    public static void publish() {
        ClientServices.track(RuntimeVariable.Species_Largest_Share_Timeline, 0.0);
        ClientServices.track(RuntimeVariable.Species_Count_Timeline, 0);
        ClientServices.track(RuntimeVariable.Species_Quota_Protected_Timeline, 0);
        ClientServices.track(RuntimeVariable.Species_Newborn_Protected_Timeline, 0);
        ClientServices.track(RuntimeVariable.Species_Incubator_Protected_Timeline, 0);
        ClientServices.track(RuntimeVariable.Species_Sharing_Adjusted_Timeline, 0);
        ClientServices.track(RuntimeVariable.DiversityTimeline, 0.0);
        ClientServices.track(RuntimeVariable.Covered_Goals_Timeline, 0);
        ClientServices.track(RuntimeVariable.Remaining_Goals_Timeline, 0);
        ClientServices.track(RuntimeVariable.Fronts_Count_Timeline, 0);
        DisruptionRecorder.trackNoEvents(ClientServices.getInstance().getClientNode());
    }
}
