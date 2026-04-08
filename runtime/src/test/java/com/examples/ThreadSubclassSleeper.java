package com.examples;

public class ThreadSubclassSleeper extends Thread {

    @Override
    public void run() {
        try {
            sleep(100L);
        } catch (InterruptedException ignored) {
            // ignored
        }
    }
}
