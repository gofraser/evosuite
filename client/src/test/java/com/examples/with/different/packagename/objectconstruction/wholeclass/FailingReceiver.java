package com.examples.with.different.packagename.objectconstruction.wholeclass;

public final class FailingReceiver extends ReceiverFallbackBase {

    public FailingReceiver(String[] preferredDependency) {
        throw new IllegalStateException("receiver cannot initialize");
    }
}
