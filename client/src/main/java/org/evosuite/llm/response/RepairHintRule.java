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

import java.util.List;
import java.util.Set;

final class RepairHintRule {

    interface Trigger {
        boolean matches(Context context);
    }

    static final class Context {
        private final String errorText;
        private final List<RepairFailureSignal> signals;
        private final Set<String> poisonedClasses;

        Context(String errorText,
                List<RepairFailureSignal> signals,
                Set<String> poisonedClasses) {
            this.errorText = errorText == null ? "" : errorText;
            this.signals = signals;
            this.poisonedClasses = poisonedClasses;
        }

        String getErrorText() {
            return errorText;
        }

        List<RepairFailureSignal> getSignals() {
            return signals;
        }

        Set<String> getPoisonedClasses() {
            return poisonedClasses;
        }

        boolean hasSignal(RepairFailureSignal.Type type) {
            if (type == null || signals == null) {
                return false;
            }
            for (RepairFailureSignal signal : signals) {
                if (signal != null && type == signal.getType()) {
                    return true;
                }
            }
            return false;
        }
    }

    private final String id;
    private final String text;
    private final int priority;
    private final int cooldownAttempts;
    private final boolean alwaysOn;
    private final Trigger trigger;

    RepairHintRule(String id,
                   String text,
                   int priority,
                   int cooldownAttempts,
                   boolean alwaysOn,
                   Trigger trigger) {
        this.id = id;
        this.text = text;
        this.priority = priority;
        this.cooldownAttempts = Math.max(0, cooldownAttempts);
        this.alwaysOn = alwaysOn;
        this.trigger = trigger;
    }

    String getId() {
        return id;
    }

    String getText() {
        return text;
    }

    int getPriority() {
        return priority;
    }

    int getCooldownAttempts() {
        return cooldownAttempts;
    }

    boolean isAlwaysOn() {
        return alwaysOn;
    }

    boolean matches(Context context) {
        return trigger != null && context != null && trigger.matches(context);
    }
}
