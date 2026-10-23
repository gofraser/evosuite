package com.examples;

import java.awt.Component;

public class CallsAppAdder {

    public Component call(AppAdder adder, Component component) {
        return adder.add(component);
    }
}
