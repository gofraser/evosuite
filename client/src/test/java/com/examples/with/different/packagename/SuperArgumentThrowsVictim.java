package com.examples.with.different.packagename;

import java.util.ArrayList;

/**
 * Fixture for line tracing: the constructor's only statement is the {@code super(...)} call, and
 * evaluating its argument throws for a null string.
 */
public class SuperArgumentThrowsVictim extends ArrayList<String> {

    public SuperArgumentThrowsVictim(String s) {
        super(s.length());
    }
}
