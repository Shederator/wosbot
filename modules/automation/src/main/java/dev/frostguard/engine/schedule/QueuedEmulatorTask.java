package dev.frostguard.engine.schedule;

import dev.frostguard.api.domain.AccountDescriptor;

// Represents a worker thread waiting in the emulator device-slot queue.
// Entries are ordered by descending priority; ties are broken by arrival time.
public class QueuedEmulatorTask implements Comparable<QueuedEmulatorTask> {

    final Thread workerThread;
    final Long schedulingPriority;
    final Long arrivalTimestampNs;
    final Long associatedProfileId;
    final String targetDeviceIndex;

    public QueuedEmulatorTask(Thread workerThread, AccountDescriptor profile) {
        this.workerThread = workerThread;
        this.schedulingPriority = profile.getPriority();
        this.associatedProfileId = profile.getId();
        this.arrivalTimestampNs = System.nanoTime();
        this.targetDeviceIndex = profile.getEmulatorNumber();
    }

    // Higher priority = earlier service. Equal priorities use FIFO order.
    @Override
    public int compareTo(QueuedEmulatorTask other) {
        int byPriority = Long.compare(other.schedulingPriority, this.schedulingPriority);
        if (byPriority != 0) return byPriority;
        return Long.compare(this.arrivalTimestampNs, other.arrivalTimestampNs);
    }

    public Long getProfileId() {
        return associatedProfileId;
    }

    public Thread getThread() {
        return workerThread;
    }

    public String getEmulatorNumber() {
        return targetDeviceIndex;
    }
}
