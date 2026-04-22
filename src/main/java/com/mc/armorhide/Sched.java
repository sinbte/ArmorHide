package com.mc.armorhide;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/**
 * Folia-safe scheduler wrapper.
 *
 * Folia has no single main thread, so work must be dispatched to the right scheduler:
 *  - global*  : global logic with no specific region (reading settings, server-wide scans)
 *  - region*  : work bound to a coordinate/block/chunk
 *  - entity*  : work bound to an entity/player (give items, change state, teleport)
 *  - async*   : background work that does not touch world state (DB, Redis, HTTP)
 *
 * Note: Folia's minimum delay/period is 1 tick; async timings are real time (ms), 1 tick = 50ms.
 */
public final class Sched {

    private Sched() {}

    private static long ticks(long t) {
        return Math.max(1L, t);
    }

    // ===================== global =====================

    public static void global(Plugin plugin, Runnable r) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, r);
    }

    public static ScheduledTask globalLater(Plugin plugin, Runnable r, long delayTicks) {
        return Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> r.run(), ticks(delayTicks));
    }

    public static ScheduledTask globalTimer(Plugin plugin, Runnable r, long delayTicks, long periodTicks) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> r.run(), ticks(delayTicks), ticks(periodTicks));
    }

    // ===================== coordinate region =====================

    public static void region(Plugin plugin, Location loc, Runnable r) {
        Bukkit.getRegionScheduler().execute(plugin, loc, r);
    }

    public static ScheduledTask regionLater(Plugin plugin, Location loc, Runnable r, long delayTicks) {
        return Bukkit.getRegionScheduler().runDelayed(plugin, loc, t -> r.run(), ticks(delayTicks));
    }

    public static ScheduledTask regionTimer(Plugin plugin, Location loc, Runnable r, long delayTicks, long periodTicks) {
        return Bukkit.getRegionScheduler().runAtFixedRate(plugin, loc, t -> r.run(), ticks(delayTicks), ticks(periodTicks));
    }

    // ===================== entity =====================

    public static void entity(Plugin plugin, Entity e, Runnable r) {
        e.getScheduler().run(plugin, t -> r.run(), null);
    }

    public static ScheduledTask entityLater(Plugin plugin, Entity e, Runnable r, long delayTicks) {
        return e.getScheduler().runDelayed(plugin, t -> r.run(), null, ticks(delayTicks));
    }

    public static ScheduledTask entityTimer(Plugin plugin, Entity e, Runnable r, long delayTicks, long periodTicks) {
        return e.getScheduler().runAtFixedRate(plugin, t -> r.run(), null, ticks(delayTicks), ticks(periodTicks));
    }

    // ===================== background async =====================

    public static ScheduledTask async(Plugin plugin, Runnable r) {
        return Bukkit.getAsyncScheduler().runNow(plugin, t -> r.run());
    }

    public static ScheduledTask asyncLater(Plugin plugin, Runnable r, long delayTicks) {
        return Bukkit.getAsyncScheduler().runDelayed(plugin, t -> r.run(), ticks(delayTicks) * 50L, TimeUnit.MILLISECONDS);
    }

    public static ScheduledTask asyncTimer(Plugin plugin, Runnable r, long delayTicks, long periodTicks) {
        return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> r.run(),
                ticks(delayTicks) * 50L, ticks(periodTicks) * 50L, TimeUnit.MILLISECONDS);
    }
}
