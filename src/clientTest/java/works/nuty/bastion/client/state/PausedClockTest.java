package works.nuty.bastion.client.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PausedClockTest {
    @Test
    void preservesElapsedAndRemainingTimeAcrossPauseResumeAndRepeatedPause() {
        PausedClock clock = new PausedClock();
        long created = clock.timeMillis(1_000);
        clock.setPaused(true, 1_200);
        assertEquals(200, clock.timeMillis(8_000) - created);
        clock.setPaused(true, 9_000);
        assertEquals(200, clock.timeMillis(10_000) - created);
        clock.setPaused(false, 10_000);
        assertEquals(250, clock.timeMillis(10_050) - created);
        clock.setPaused(true, 10_100);
        clock.setPaused(false, 30_000);
        assertEquals(310, clock.timeMillis(30_010) - created);
    }

    @Test
    void eventsCreatedDuringPauseStartAgingAtResume() {
        PausedClock clock = new PausedClock();
        clock.setPaused(true, 500);
        long created = clock.timeMillis(2_000);
        assertEquals(0, clock.timeMillis(20_000) - created);
        clock.setPaused(false, 20_000);
        assertEquals(10, clock.timeMillis(20_010) - created);
        clock.setPaused(false, 20_010);
        assertEquals(20, clock.timeMillis(20_020) - created);
    }
}
