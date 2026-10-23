package com.examples;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;

public class CallsSocketConnect {

    public void call(Socket socket, SocketAddress address) throws IOException {
        socket.connect(address);
    }
}
