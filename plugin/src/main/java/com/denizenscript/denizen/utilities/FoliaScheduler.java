package com.denizenscript.denizen.utilities;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class FoliaScheduler {

    private static final AtomicInteger NEXT_TASK_ID = new AtomicInteger(1);
    private static final Map<Integer, DenizenBukkitTask> TASKS = new ConcurrentHashMap<>();

    public static void runOnPrimaryRegion(Plugin plugin, Runnable runnable) {
        if (!plugin.isEnabled()) {
            return;
        }
        World spawn = Bukkit.getWorld("spawn");
        if (spawn == null && !Bukkit.getWorlds().isEmpty()) {
            spawn = Bukkit.getWorlds().get(0);
        }
        if (spawn != null) {
            if (Bukkit.isOwnedByCurrentRegion(spawn, 0, 0)) {
                runnable.run();
            }
            else {
                Bukkit.getRegionScheduler().execute(plugin, spawn, 0, 0, runnable);
            }
        }
        else {
            runnable.run();
        }
    }

    public static BukkitTask runTask(Plugin plugin, Runnable runnable) {
        int id = NEXT_TASK_ID.getAndIncrement();
        DenizenBukkitTask wrapper = new DenizenBukkitTask(id, plugin, true);
        TASKS.put(id, wrapper);
        ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler().run(plugin, t -> {
            TASKS.remove(id);
            if (!wrapper.isCancelled()) {
                runOnPrimaryRegion(plugin, runnable);
            }
        });
        wrapper.setHandle(scheduled);
        return wrapper;
    }

    public static BukkitTask runTaskLater(Plugin plugin, Runnable runnable, long delayTicks) {
        int id = NEXT_TASK_ID.getAndIncrement();
        DenizenBukkitTask wrapper = new DenizenBukkitTask(id, plugin, true);
        TASKS.put(id, wrapper);
        long delay = Math.max(1L, delayTicks);
        ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> {
            TASKS.remove(id);
            if (!wrapper.isCancelled()) {
                runOnPrimaryRegion(plugin, runnable);
            }
        }, delay);
        wrapper.setHandle(scheduled);
        return wrapper;
    }

    public static BukkitTask runTaskTimer(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        int id = NEXT_TASK_ID.getAndIncrement();
        DenizenBukkitTask wrapper = new DenizenBukkitTask(id, plugin, true);
        TASKS.put(id, wrapper);
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> {
            if (wrapper.isCancelled()) {
                t.cancel();
                TASKS.remove(id);
                return;
            }
            runOnPrimaryRegion(plugin, () -> {
                if (!wrapper.isCancelled()) {
                    runnable.run();
                }
            });
        }, delay, period);
        wrapper.setHandle(scheduled);
        return wrapper;
    }

    public static BukkitTask runTaskAsynchronously(Plugin plugin, Runnable runnable) {
        int id = NEXT_TASK_ID.getAndIncrement();
        DenizenBukkitTask wrapper = new DenizenBukkitTask(id, plugin, false);
        TASKS.put(id, wrapper);
        ScheduledTask scheduled = Bukkit.getAsyncScheduler().runNow(plugin, t -> {
            TASKS.remove(id);
            if (!wrapper.isCancelled()) {
                runnable.run();
            }
        });
        wrapper.setHandle(scheduled);
        return wrapper;
    }

    public static BukkitTask runTaskLaterAsynchronously(Plugin plugin, Runnable runnable, long delayTicks) {
        int id = NEXT_TASK_ID.getAndIncrement();
        DenizenBukkitTask wrapper = new DenizenBukkitTask(id, plugin, false);
        TASKS.put(id, wrapper);
        long delayMs = Math.max(1L, delayTicks) * 50L;
        ScheduledTask scheduled = Bukkit.getAsyncScheduler().runDelayed(plugin, t -> {
            TASKS.remove(id);
            if (!wrapper.isCancelled()) {
                runnable.run();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        wrapper.setHandle(scheduled);
        return wrapper;
    }

    public static BukkitTask runTaskTimerAsynchronously(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        int id = NEXT_TASK_ID.getAndIncrement();
        DenizenBukkitTask wrapper = new DenizenBukkitTask(id, plugin, false);
        TASKS.put(id, wrapper);
        long delayMs = Math.max(1L, delayTicks) * 50L;
        long periodMs = Math.max(1L, periodTicks) * 50L;
        ScheduledTask scheduled = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> {
            if (wrapper.isCancelled()) {
                t.cancel();
                TASKS.remove(id);
                return;
            }
            runnable.run();
        }, delayMs, periodMs, TimeUnit.MILLISECONDS);
        wrapper.setHandle(scheduled);
        return wrapper;
    }

    public static int scheduleSyncDelayedTask(Plugin plugin, Runnable runnable, long delay) {
        return runTaskLater(plugin, runnable, delay).getTaskId();
    }

    public static int scheduleSyncDelayedTask(Plugin plugin, Runnable runnable) {
        return runTask(plugin, runnable).getTaskId();
    }

    public static int scheduleSyncRepeatingTask(Plugin plugin, Runnable runnable, long delay, long period) {
        return runTaskTimer(plugin, runnable, delay, period).getTaskId();
    }

    public static void cancelTask(int taskId) {
        DenizenBukkitTask task = TASKS.remove(taskId);
        if (task != null) {
            task.cancel();
        }
    }

    public static void cancelTasks(Plugin plugin) {
        TASKS.values().removeIf(task -> {
            if (task.getOwner().equals(plugin)) {
                task.cancel();
                return true;
            }
            return false;
        });
        try {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
        }
        catch (Throwable ignored) {
        }
    }

    public static <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (Bukkit.isPrimaryThread()) {
            try {
                future.complete(task.call());
            }
            catch (Throwable t) {
                future.completeExceptionally(t);
            }
            return future;
        }
        runOnPrimaryRegion(plugin, () -> {
            try {
                future.complete(task.call());
            }
            catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public static class DenizenBukkitTask implements BukkitTask {
        private final int taskId;
        private final Plugin owner;
        private final boolean sync;
        private volatile boolean cancelled;
        private volatile ScheduledTask handle;

        public DenizenBukkitTask(int taskId, Plugin owner, boolean sync) {
            this.taskId = taskId;
            this.owner = owner;
            this.sync = sync;
        }

        public void setHandle(ScheduledTask handle) {
            this.handle = handle;
            if (cancelled && handle != null) {
                handle.cancel();
            }
        }

        @Override
        public int getTaskId() {
            return taskId;
        }

        @Override
        public Plugin getOwner() {
            return owner;
        }

        @Override
        public boolean isSync() {
            return sync;
        }

        @Override
        public boolean isCancelled() {
            return cancelled || (handle != null && handle.isCancelled());
        }

        @Override
        public void cancel() {
            cancelled = true;
            TASKS.remove(taskId);
            if (handle != null) {
                handle.cancel();
            }
        }
    }
}
