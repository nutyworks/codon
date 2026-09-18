package works.nuty.codon.core.support;

import works.nuty.codon.core.port.ExecutionController;

import java.util.function.BooleanSupplier;

/**
 * Test double for {@link ExecutionController} that does not block: it records that the engine
 * tried to park (and the value of the resume condition at that moment) and returns immediately,
 * leaving the engine's {@code paused} flag set so tests can inspect it.
 */
public final class ImmediateExecutionController implements ExecutionController {
    public int parkCount = 0;
    public boolean resumedAtPark;
    public ParkResult result = ParkResult.RESUMED;
    public RuntimeException failure;

    @Override
    public ParkResult parkUntil(BooleanSupplier resumed) {
        parkCount++;
        resumedAtPark = resumed.getAsBoolean();
        if (failure != null) {
            throw failure;
        }
        return result;
    }
}
