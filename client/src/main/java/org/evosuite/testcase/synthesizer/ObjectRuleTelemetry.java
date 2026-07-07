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
package org.evosuite.testcase.synthesizer;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.testcase.TestChromosome;

/**
 * Per-run EvoObj template survival telemetry. Reset from
 * {@link ObjectConstructionContext#reset()} so shared-JVM system tests do not leak state.
 */
public class ObjectRuleTelemetry {

    private static final ObjectRuleTelemetry instance = new ObjectRuleTelemetry();

    private static class TemplateLifetime {
        private final int injectionIteration;
        private int lastSeenIteration;

        private TemplateLifetime(int injectionIteration) {
            this.injectionIteration = injectionIteration;
            this.lastSeenIteration = injectionIteration;
        }
    }

    private final Map<Integer, TemplateLifetime> lifetimes = new LinkedHashMap<>();

    private ObjectRuleTelemetry() {
    }

    public static ObjectRuleTelemetry getInstance() {
        return instance;
    }

    public void reset() {
        lifetimes.clear();
    }

    public void recordInjection(int templateId, int iteration) {
        if (templateId <= 0) {
            return;
        }
        lifetimes.put(templateId, new TemplateLifetime(iteration));
    }

    public void recordPopulation(Collection<? extends TestChromosome> population, int iteration) {
        if (population == null) {
            return;
        }
        for (TestChromosome chromosome : population) {
            if (chromosome == null) {
                continue;
            }
            int id = chromosome.getObjectRuleTemplateId();
            TemplateLifetime lifetime = lifetimes.get(id);
            if (lifetime != null) {
                lifetime.lastSeenIteration = Math.max(lifetime.lastSeenIteration, iteration);
            }
        }
    }

    public double meanSurvival() {
        if (lifetimes.isEmpty()) {
            return 0.0;
        }
        int total = 0;
        for (TemplateLifetime lifetime : lifetimes.values()) {
            total += lifetime.lastSeenIteration - lifetime.injectionIteration;
        }
        return (double) total / lifetimes.size();
    }

    public double survivalAtLeastFive() {
        if (lifetimes.isEmpty()) {
            return 0.0;
        }
        int retained = 0;
        for (TemplateLifetime lifetime : lifetimes.values()) {
            if (lifetime.lastSeenIteration - lifetime.injectionIteration >= 5) {
                retained++;
            }
        }
        return (double) retained / lifetimes.size();
    }
}
