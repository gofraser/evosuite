package com.examples.with.different.packagename;

import java.util.ArrayList;
import java.util.List;

/** Fixture whose accessor is declared to return Object but yields a generic Collection at runtime. */
public class ObjectTypedCollectionSource {

    public Object items() {
        List<Integer> list = new ArrayList<Integer>();
        list.add(7);
        return list;
    }

    public List<Integer> typedItems() {
        return new ArrayList<Integer>();
    }
}
