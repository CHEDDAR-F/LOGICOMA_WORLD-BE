package com.ai.repo.release;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** A server-local deployment gate, never controlled by an HTTP header. */
public final class ReleaseActivation {
    private ReleaseActivation() { }
    public static boolean isActive() {
        String marker = System.getProperty("logicoma.release.activation-file");
        return marker == null || marker.isBlank() || Files.isRegularFile(Path.of(marker));
    }
    public static boolean awaitActive(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!isActive()) {
            if (System.nanoTime() >= deadline) return false;
            Thread.sleep(25);
        }
        return true;
    }
}
