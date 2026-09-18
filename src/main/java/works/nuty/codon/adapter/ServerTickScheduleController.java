package works.nuty.codon.adapter;

/** Resets Minecraft's wall-clock tick deadline after a debugger-induced pause. */
public interface ServerTickScheduleController {
    void codon$resetTickSchedule();
}
