/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package com.examples.with.different.packagename.objectconstruction.tuning;

import java.util.LinkedList;

/**
 * Models parsers such as SAXPath's XPathReader: construction leaves parser state absent, one
 * initializer directly installs it, while many parser operations only mutate it transitively.
 */
public class LazyParserStateExample {

    private LinkedList<Integer> tokens;
    private String input;

    void initialize(String text) {
        tokens = new LinkedList<>();
        input = text;
    }

    void consume() {
        tokens.add(input.length());
    }

    void parse(String text) {
        initialize(text);
        consume();
        tokens = null;
    }

    public int target() {
        consume();
        return tokens.size() > 1 ? 1 : 0;
    }
}
