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
package org.evosuite.llm.response;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairHintResolverTest {

    @Test
    void classCastAndNullReceiverSignalsTriggerSpecificHints() {
        RepairHintResolver resolver = new RepairHintResolver();
        Map<String, Integer> shown = new LinkedHashMap<>();

        String error = "Execution error: java.lang.ClassCastException - class java.lang.String cannot be cast to class wheel.enhance.WheelClassLoader";
        Throwable npe = new NullPointerException(
                "Cannot invoke \"javax.servlet.http.HttpSession.getAttribute(String)\" because \"session\" is null");

        RepairHintResolver.Resolution resolution = resolver.resolve(
                error,
                Collections.<Throwable>singletonList(npe),
                Collections.<String>emptySet(),
                1,
                shown,
                10,
                0,
                false);

        List<RepairHintRule> hints = resolution.getHints();
        assertTrue(containsHint(hints, "wrong-type setup/stubbing"));
        assertTrue(containsHint(hints, "Receiver-null NPE"));
        assertTrue(containsSignal(resolution.getSignals(), RepairFailureSignal.Type.CLASS_CAST));
        assertTrue(containsSignal(resolution.getSignals(), RepairFailureSignal.Type.NULL_RECEIVER_NPE));
    }

    @Test
    void staticInitSignalsTriggerPoisonedClassHint() {
        RepairHintResolver resolver = new RepairHintResolver();
        Map<String, Integer> shown = new LinkedHashMap<>();

        NoClassDefFoundError aftershock = new NoClassDefFoundError(
                "Could not initialize class src.CurrentPlayers");

        RepairHintResolver.Resolution resolution = resolver.resolve(
                "Execution error: java.lang.NoClassDefFoundError - Could not initialize class src.CurrentPlayers",
                Arrays.<Throwable>asList(aftershock),
                Collections.singleton("src.CurrentPlayers"),
                2,
                shown,
                10,
                0,
                false);

        assertTrue(containsHint(resolution.getHints(), "Static initialization failed"));
        assertTrue(containsSignal(resolution.getSignals(), RepairFailureSignal.Type.STATIC_INIT_AFTERSHOCK));
    }

    @Test
    void cooldownSuppressesRepeatedHintsOnAdjacentAttempts() {
        RepairHintResolver resolver = new RepairHintResolver();
        Map<String, Integer> shown = new LinkedHashMap<>();
        String error = "Execution error: java.lang.ClassCastException - class java.lang.String cannot be cast to class x.Y";

        RepairHintResolver.Resolution first = resolver.resolve(
                error,
                Collections.<Throwable>emptyList(),
                Collections.<String>emptySet(),
                3,
                shown,
                10,
                1,
                false);
        assertFalse(first.getHints().isEmpty());
        for (RepairHintRule hint : first.getHints()) {
            shown.put(hint.getId(), 3);
        }

        RepairHintResolver.Resolution second = resolver.resolve(
                error,
                Collections.<Throwable>emptyList(),
                Collections.<String>emptySet(),
                4,
                shown,
                10,
                1,
                false);
        assertTrue(second.getHints().isEmpty());
    }

    private boolean containsHint(List<RepairHintRule> hints, String needle) {
        if (hints == null || hints.isEmpty()) {
            return false;
        }
        for (RepairHintRule hint : hints) {
            if (hint != null && hint.getText().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSignal(List<RepairFailureSignal> signals, RepairFailureSignal.Type type) {
        if (signals == null || signals.isEmpty()) {
            return false;
        }
        for (RepairFailureSignal signal : signals) {
            if (signal != null && signal.getType() == type) {
                return true;
            }
        }
        return false;
    }
}
