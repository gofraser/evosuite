package com.examples.with.different.packagename;

import java.awt.peer.ComponentPeer;

/** Fixture whose accessor returns a type from a JDK package that java.desktop does not export. */
public class UnexportedPeerFixture {

    public ComponentPeer getPeer() {
        return null;
    }
}
