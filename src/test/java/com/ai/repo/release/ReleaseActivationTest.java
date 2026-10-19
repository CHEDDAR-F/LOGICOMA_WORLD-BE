package com.ai.repo.release;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.ai.repo.playground.service.PlaygroundExpiryScheduler;
import com.ai.repo.playground.service.PlaygroundService;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReleaseActivationTest {
    @TempDir Path temp;
    @AfterEach void clear() { System.clearProperty("logicoma.release.activation-file"); }
    @Test void normalRuntimeIsActiveAndTimeoutDoesNotActivate() throws Exception {
        assertTrue(ReleaseActivation.isActive());
        System.setProperty("logicoma.release.activation-file",temp.resolve("active").toString());
        assertFalse(ReleaseActivation.awaitActive(Duration.ofMillis(30)));
        assertFalse(ReleaseActivation.isActive());
    }
    @Test void passivePostWaitsAndExecutesExactlyOnceAfterLocalActivation() throws Exception {
        Path marker=temp.resolve("active");
        System.setProperty("logicoma.release.activation-file",marker.toString());
        ReleaseGateFilter filter=new ReleaseGateFilter(); AtomicInteger calls=new AtomicInteger();
        MockHttpServletRequest post=new MockHttpServletRequest("POST","/api/memories");
        post.addHeader("X-Release-Active","true");
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> pending=worker.submit(()-> { try {
                filter.doFilter(post,new MockHttpServletResponse(),(a,b)->calls.incrementAndGet());
            } catch(Exception e) {throw new RuntimeException(e);} });
            assertThrows(TimeoutException.class,()->pending.get(100,TimeUnit.MILLISECONDS));
            assertEquals(0,calls.get()); Files.createFile(marker);
            pending.get(2,TimeUnit.SECONDS); assertEquals(1,calls.get());
        } finally {worker.shutdownNow();}
    }
    @Test void healthWorksWhileScheduledBusinessWorkRemainsPassive() throws Exception {
        System.setProperty("logicoma.release.activation-file",temp.resolve("active").toString());
        AtomicInteger calls=new AtomicInteger();
        new ReleaseGateFilter().doFilter(new MockHttpServletRequest("GET","/actuator/health"),
                new MockHttpServletResponse(),(a,b)->calls.incrementAndGet());
        assertEquals(1,calls.get());
        PlaygroundService service=mock(PlaygroundService.class);
        new PlaygroundExpiryScheduler(service).expire(); verifyNoInteractions(service);
    }
}
