package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import org.bukkit.Bukkit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

public class AutoCheckQueueManager {

    private final CheckHacksPlugin plugin;
    private final Deque<QueuedAutoCheck> queue = new ArrayDeque<>();
    private final Set<String> queuedKeys = ConcurrentHashMap.newKeySet();
    private final Set<UUID> runningTargets = ConcurrentHashMap.newKeySet();
    private int runningChecks;
    private boolean drainScheduled;

    public AutoCheckQueueManager(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isQueued(String key) {
        return queuedKeys.contains(key);
    }

    public void enqueue(String key, UUID targetUUID, String description, BooleanSupplier starter) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> enqueue(key, targetUUID, description, starter));
            return;
        }
        if (!queuedKeys.add(key)) return;

        queue.addLast(new QueuedAutoCheck(key, targetUUID, description, starter));
        plugin.getLogger().info("[CheckHacks] Queued automatic " + description
                + " (" + queue.size() + " waiting, " + runningChecks + " running).");
        scheduleDrain(0L);
    }

    public void remove(String key) {
        if (!queuedKeys.remove(key)) return;
        queue.removeIf(check -> check.key().equals(key));
    }

    public void releaseSlot(UUID targetUUID) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> releaseSlot(targetUUID));
            return;
        }
        runningTargets.remove(targetUUID);
        if (runningChecks > 0) runningChecks--;
        scheduleDrain(plugin.getConfigManager().getAutoCheckQueueStartIntervalTicks());
    }

    public void cleanup() {
        queue.clear();
        queuedKeys.clear();
        runningTargets.clear();
        runningChecks = 0;
        drainScheduled = false;
    }

    private void scheduleDrain(long delayTicks) {
        if (drainScheduled || queue.isEmpty()) return;
        drainScheduled = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            drainScheduled = false;
            drain();
        }, Math.max(0L, delayTicks));
    }

    private void drain() {
        if (runningChecks >= plugin.getConfigManager().getAutoCheckQueueMaxConcurrent()) return;

        QueuedAutoCheck next = pollNext();
        if (next == null) {
            scheduleDrain(plugin.getConfigManager().getAutoCheckQueueStartIntervalTicks());
            return;
        }

        runningChecks++;
        runningTargets.add(next.targetUUID());
        boolean started = false;
        try {
            started = next.starter().getAsBoolean();
        } catch (Exception e) {
            plugin.getLogger().warning("[CheckHacks] Failed to start queued automatic "
                    + next.description() + ": " + e.getMessage());
        }

        if (!started) {
            runningTargets.remove(next.targetUUID());
            if (runningChecks > 0) runningChecks--;
        }

        if (!queue.isEmpty()
                && runningChecks < plugin.getConfigManager().getAutoCheckQueueMaxConcurrent()) {
            scheduleDrain(started ? plugin.getConfigManager().getAutoCheckQueueStartIntervalTicks() : 0L);
        }
    }

    private QueuedAutoCheck pollNext() {
        int attempts = queue.size();
        for (int i = 0; i < attempts; i++) {
            QueuedAutoCheck next = queue.removeFirst();
            if (runningTargets.contains(next.targetUUID())) {
                queue.addLast(next);
                continue;
            }
            queuedKeys.remove(next.key());
            return next;
        }
        return null;
    }

    private record QueuedAutoCheck(String key, UUID targetUUID,
                                   String description, BooleanSupplier starter) {}
}
