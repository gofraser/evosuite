package com.examples.with.different.packagename.objectconstruction.tuning;

/** An input whose branch state is exposed only through a query with no synthesis recipe. */
public class ObserverOnlyInput {

    public boolean target(ObserverNameSource source) {
        return source != null && "expected".equals(source.getName());
    }
}
