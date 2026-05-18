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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic, error-triggered hint selection engine for LLM repair prompts.
 */
public final class RepairHintResolver {

    private static final Pattern NPE_CANNOT_INVOKE_PATTERN = Pattern.compile(
            "Cannot invoke\\s+\"([^\"]+)\"\\s+because\\s+\"([^\"]+)\"\\s+is null");
    private static final Pattern CLASS_CAST_PATTERN = Pattern.compile(
            "class\\s+([^\\s]+)\\s+cannot be cast to class\\s+([^\\s]+)");
    private static final Pattern COULD_NOT_INITIALIZE_PATTERN = Pattern.compile(
            "Could not initialize class\\s+([A-Za-z_][A-Za-z0-9_$.]+)");

    static final class Resolution {
        private final List<RepairFailureSignal> signals;
        private final List<RepairHintRule> hints;

        Resolution(List<RepairFailureSignal> signals, List<RepairHintRule> hints) {
            this.signals = signals == null ? Collections.<RepairFailureSignal>emptyList() : signals;
            this.hints = hints == null ? Collections.<RepairHintRule>emptyList() : hints;
        }

        List<RepairFailureSignal> getSignals() {
            return signals;
        }

        List<RepairHintRule> getHints() {
            return hints;
        }

        boolean isEmpty() {
            return hints.isEmpty();
        }
    }

    private final List<RepairHintRule> rules;

    public RepairHintResolver() {
        List<RepairHintRule> configured = new ArrayList<>();

        // Always-on, minimal defaults.
        configured.add(new RepairHintRule(
                "always.type.safe.stubs",
                "Avoid blanket stubs on heterogeneous key/value APIs. Use key-specific, type-correct stubbing.",
                10,
                0,
                true,
                new RepairHintRule.Trigger() {
                    @Override
                    public boolean matches(RepairHintRule.Context context) {
                        return true;
                    }
                }));
        configured.add(new RepairHintRule(
                "always.preconditions",
                "Satisfy constructor and lifecycle preconditions before invoking behavior under test.",
                11,
                0,
                true,
                new RepairHintRule.Trigger() {
                    @Override
                    public boolean matches(RepairHintRule.Context context) {
                        return true;
                    }
                }));
        configured.add(new RepairHintRule(
                "always.no.wrong.placeholder",
                "If uncertain, prefer null plus explicit expectation handling over wrong-type placeholder objects.",
                12,
                0,
                true,
                new RepairHintRule.Trigger() {
                    @Override
                    public boolean matches(RepairHintRule.Context context) {
                        return true;
                    }
                }));

        // Error-triggered hints.
        configured.add(new RepairHintRule(
                "trigger.static.init",
                "Static initialization failed. Do not touch the poisoned class path in this run; rewrite the test to avoid loading it.",
                20,
                1,
                false,
                new RepairHintRule.Trigger() {
                    @Override
                    public boolean matches(RepairHintRule.Context context) {
                        return context != null && (context.hasSignal(RepairFailureSignal.Type.STATIC_INIT_FAILURE)
                                || context.hasSignal(RepairFailureSignal.Type.STATIC_INIT_AFTERSHOCK)
                                || !context.getPoisonedClasses().isEmpty());
                    }
                }));
        configured.add(new RepairHintRule(
                "trigger.class.cast",
                "ClassCastException indicates wrong-type setup/stubbing. Return the exact runtime type expected at that lookup/call site.",
                30,
                1,
                false,
                new RepairHintRule.Trigger() {
                    @Override
                    public boolean matches(RepairHintRule.Context context) {
                        return context != null && context.hasSignal(RepairFailureSignal.Type.CLASS_CAST);
                    }
                }));
        configured.add(new RepairHintRule(
                "trigger.receiver.null.npe",
                "Receiver-null NPE indicates missing collaborator initialization. Initialize/stub upstream dependencies so the receiver is non-null before dereference.",
                31,
                1,
                false,
                new RepairHintRule.Trigger() {
                    @Override
                    public boolean matches(RepairHintRule.Context context) {
                        return context != null && context.hasSignal(RepairFailureSignal.Type.NULL_RECEIVER_NPE);
                    }
                }));
        configured.add(new RepairHintRule(
                "trigger.dependency.missing",
                "NoClassDefFoundError/ClassNotFound signals unavailable dependencies; avoid those paths and prefer pure SUT/JDK flows.",
                32,
                1,
                false,
                new RepairHintRule.Trigger() {
                    @Override
                    public boolean matches(RepairHintRule.Context context) {
                        if (context == null) {
                            return false;
                        }
                        String error = context.getErrorText();
                        return error.contains("NoClassDefFoundError")
                                || error.contains("ClassNotFoundException");
                    }
                }));

        Collections.sort(configured, new Comparator<RepairHintRule>() {
            @Override
            public int compare(RepairHintRule a, RepairHintRule b) {
                return Integer.compare(a.getPriority(), b.getPriority());
            }
        });
        this.rules = Collections.unmodifiableList(configured);
    }

    Resolution resolve(String errorText,
                       List<Throwable> throwables,
                       Set<String> poisonedClasses,
                       int repairAttempt,
                       Map<String, Integer> lastShownAttemptByRuleId,
                       int maxHints,
                       int cooldownAttempts,
                       boolean includeAlwaysOn) {
        List<RepairFailureSignal> signals = extractSignals(errorText, throwables, poisonedClasses);
        RepairHintRule.Context context = new RepairHintRule.Context(
                errorText, signals, poisonedClasses == null ? Collections.<String>emptySet() : poisonedClasses);

        if (maxHints <= 0) {
            return new Resolution(signals, Collections.<RepairHintRule>emptyList());
        }

        List<RepairHintRule> selected = new ArrayList<>();
        for (RepairHintRule rule : rules) {
            if (selected.size() >= maxHints) {
                break;
            }
            boolean eligible = (rule.isAlwaysOn() && includeAlwaysOn) || rule.matches(context);
            if (!eligible) {
                continue;
            }

            int effectiveCooldown = Math.max(cooldownAttempts, rule.getCooldownAttempts());
            Integer lastAttempt = lastShownAttemptByRuleId == null
                    ? null
                    : lastShownAttemptByRuleId.get(rule.getId());
            if (lastAttempt != null && repairAttempt - lastAttempt <= effectiveCooldown) {
                continue;
            }
            selected.add(rule);
        }
        return new Resolution(signals, selected);
    }

    private List<RepairFailureSignal> extractSignals(String errorText,
                                                     List<Throwable> throwables,
                                                     Set<String> poisonedClasses) {
        LinkedHashMap<String, RepairFailureSignal> dedup = new LinkedHashMap<>();

        addSignalsFromErrorText(errorText, dedup);
        if (throwables != null) {
            for (Throwable throwable : throwables) {
                addSignalsFromThrowable(throwable, dedup);
            }
        }
        if (poisonedClasses != null) {
            for (String poisoned : poisonedClasses) {
                if (poisoned == null || poisoned.trim().isEmpty()) {
                    continue;
                }
                RepairFailureSignal signal = RepairFailureSignal.staticInitAftershock(poisoned.trim());
                dedup.put(signal.getKey(), signal);
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private void addSignalsFromErrorText(String errorText,
                                         Map<String, RepairFailureSignal> out) {
        if (errorText == null || errorText.trim().isEmpty()) {
            return;
        }

        if (errorText.contains("NullPointerException")) {
            Matcher npeMatcher = NPE_CANNOT_INVOKE_PATTERN.matcher(errorText);
            if (npeMatcher.find()) {
                RepairFailureSignal signal = RepairFailureSignal.nullReceiverNpe(
                        npeMatcher.group(1), npeMatcher.group(2));
                out.put(signal.getKey(), signal);
            } else {
                out.put("exception:NullPointerException",
                        RepairFailureSignal.exceptionType("NullPointerException", ""));
            }
        }
        if (errorText.contains("ClassCastException")) {
            Matcher castMatcher = CLASS_CAST_PATTERN.matcher(errorText);
            if (castMatcher.find()) {
                RepairFailureSignal signal = RepairFailureSignal.classCast(
                        castMatcher.group(1), castMatcher.group(2));
                out.put(signal.getKey(), signal);
            } else {
                out.put("exception:ClassCastException",
                        RepairFailureSignal.exceptionType("ClassCastException", ""));
            }
        }
        if (errorText.contains("ExceptionInInitializerError")) {
            RepairFailureSignal signal = RepairFailureSignal.staticInitFailure("<unknown>");
            out.put(signal.getKey(), signal);
        }
        Matcher initAftershockMatcher = COULD_NOT_INITIALIZE_PATTERN.matcher(errorText);
        while (initAftershockMatcher.find()) {
            RepairFailureSignal signal = RepairFailureSignal.staticInitAftershock(
                    initAftershockMatcher.group(1));
            out.put(signal.getKey(), signal);
        }
    }

    private void addSignalsFromThrowable(Throwable throwable,
                                         Map<String, RepairFailureSignal> out) {
        if (throwable == null) {
            return;
        }
        Throwable root = unwrap(throwable);
        String exceptionName = root.getClass().getSimpleName();
        String message = root.getMessage() == null ? "" : root.getMessage();
        RepairFailureSignal base = RepairFailureSignal.exceptionType(exceptionName, message);
        out.put(base.getKey(), base);

        if (root instanceof NullPointerException) {
            Matcher npeMatcher = NPE_CANNOT_INVOKE_PATTERN.matcher(message);
            if (npeMatcher.find()) {
                RepairFailureSignal signal = RepairFailureSignal.nullReceiverNpe(
                        npeMatcher.group(1), npeMatcher.group(2));
                out.put(signal.getKey(), signal);
            }
        } else if (root instanceof ClassCastException) {
            Matcher castMatcher = CLASS_CAST_PATTERN.matcher(message);
            if (castMatcher.find()) {
                RepairFailureSignal signal = RepairFailureSignal.classCast(
                        castMatcher.group(1), castMatcher.group(2));
                out.put(signal.getKey(), signal);
            }
        } else if (root instanceof ExceptionInInitializerError) {
            String clinitClass = findClinitClass(root);
            RepairFailureSignal signal = RepairFailureSignal.staticInitFailure(
                    clinitClass == null ? "<unknown>" : clinitClass);
            out.put(signal.getKey(), signal);
        } else if (root instanceof NoClassDefFoundError) {
            Matcher initMatcher = COULD_NOT_INITIALIZE_PATTERN.matcher(message == null ? "" : message);
            if (initMatcher.find()) {
                RepairFailureSignal signal = RepairFailureSignal.staticInitAftershock(
                        initMatcher.group(1));
                out.put(signal.getKey(), signal);
            }
        }
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 12) {
            if ((current instanceof java.lang.reflect.InvocationTargetException
                    || current instanceof java.util.concurrent.ExecutionException
                    || current.getClass().getName().endsWith("CodeUnderTestException")
                    || current.getClass().getName().endsWith("UndeclaredThrowableException"))
                    && current.getCause() != null) {
                current = current.getCause();
                depth++;
                continue;
            }
            return current;
        }
        return throwable;
    }

    private String findClinitClass(Throwable throwable) {
        if (throwable == null || throwable.getStackTrace() == null) {
            return null;
        }
        for (StackTraceElement frame : throwable.getStackTrace()) {
            if (frame != null && "<clinit>".equals(frame.getMethodName())) {
                return frame.getClassName();
            }
        }
        return null;
    }
}
