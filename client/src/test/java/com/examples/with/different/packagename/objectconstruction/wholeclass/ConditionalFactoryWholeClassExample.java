package com.examples.with.different.packagename.objectconstruction.wholeclass;

import java.util.Iterator;

/** A private-constructor fixture whose generic factory needs two concrete input elements. */
public final class ConditionalFactoryWholeClassExample
        implements ConditionalFactoryProduct {

    public abstract static class Element {
    }

    public static final class ConcreteElement extends Element {
        public ConcreteElement() {
        }
    }

    private final int elementCount;

    private ConditionalFactoryWholeClassExample(int elementCount) {
        this.elementCount = elementCount;
    }

    @SuppressWarnings("unchecked")
    public static <T extends ConditionalFactoryProduct> T coalesce(Iterable<Element> elements) {
        Iterator<Element> iterator = elements.iterator();
        if (!iterator.hasNext()) {
            return null;
        }
        iterator.next();
        if (!iterator.hasNext()) {
            return null;
        }
        iterator.next();
        return (T) new ConditionalFactoryWholeClassExample(2);
    }

    public boolean entry() {
        return elementCount == 2;
    }
}
