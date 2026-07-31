/* Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors. */
package org.evosuite.assertion;

/** Stable, source-neutral kinds understood by canonical assertion renderers. */
public enum TemplateAssertionKind {
    EQUALS, NOT_EQUALS, TRUE, FALSE, NULL, NOT_NULL, SAME, NOT_SAME,
    CONTAINS, NOT_CONTAINS, SIZE_EQUALS, MAP_CONTAINS_KEY, IS_EMPTY,
    GREATER, LESS, GREATER_EQUALS, LESS_EQUALS
}
