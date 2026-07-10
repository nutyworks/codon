package works.nuty.bastion.adapter;

/** Resets Minecraft's wall-clock tick deadline after a debugger-induced pause. */
public interface ServerTickScheduleController {
    void bastion$resetTickSchedule();
}
