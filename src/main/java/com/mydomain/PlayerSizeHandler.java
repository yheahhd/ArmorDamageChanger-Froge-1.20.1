package com.mydomain;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerSizeHandler {
    private static final int MODE_THREE = 2;
    // Change this value to adjust how long mode 3 keeps the moving hitbox/camera after stopping.
    public static final int MODE_THREE_STOP_DELAY_TICKS = 10;
    private static final String LEGACY_HITBOX_MODE_TAG = "hitbox_mode";
    private static final String LEGACY_HITBOX_MOVING_TAG = "hitbox_moving";
    private static final Map<UUID, Integer> CLIENT_MODES = new HashMap<>();
    private static final Map<UUID, Integer> SERVER_MODES = new HashMap<>();
    private static final Map<UUID, Boolean> CLIENT_MOVING_STATES = new HashMap<>();
    private static final Map<UUID, Boolean> SERVER_MOVING_STATES = new HashMap<>();
    private static final Map<UUID, Integer> CLIENT_STOP_DELAY_TICKS = new HashMap<>();
    private static final Map<UUID, Integer> SERVER_STOP_DELAY_TICKS = new HashMap<>();

    public static int getNextMode(int currentMode) {
        return switch (clampMode(currentMode)) {
            case 0 -> 1;
            case 1 -> 2;
            default -> 0;
        };
    }

    public static String getModeDisplayName(int mode) {
        return switch (clampMode(mode)) {
            case 1 -> "2/3";
            case 2 -> "3/3";
            default -> "1/3";
        };
    }

    public static int getClientMode(UUID playerId) {
        return CLIENT_MODES.getOrDefault(playerId, 0);
    }

    public static void setClientMode(UUID playerId, int mode) {
        int clampedMode = clampMode(mode);
        if (clampedMode == 0) {
            CLIENT_MODES.remove(playerId);
            CLIENT_MOVING_STATES.remove(playerId);
        } else {
            CLIENT_MODES.put(playerId, clampedMode);
        }
        if (clampedMode != MODE_THREE) {
            CLIENT_STOP_DELAY_TICKS.remove(playerId);
        }
    }

    public static int getMode(Player player) {
        if (player.level().isClientSide) {
            return getClientMode(player.getUUID());
        }
        return SERVER_MODES.getOrDefault(player.getUUID(), 0);
    }

    public static void setServerMode(ServerPlayer player, int mode) {
        int clampedMode = clampMode(mode);
        UUID playerId = player.getUUID();
        if (clampedMode == 0) {
            SERVER_MODES.remove(playerId);
            SERVER_MOVING_STATES.remove(playerId);
        } else {
            SERVER_MODES.put(playerId, clampedMode);
        }
        if (clampedMode != MODE_THREE) {
            SERVER_STOP_DELAY_TICKS.remove(playerId);
        }
        player.refreshDimensions();
        syncMode(player);
    }

    public static boolean isModeThreeStopDelayActive(Player player) {
        return getMode(player) == MODE_THREE && getStopDelayTicks(player) > 0;
    }

    @SuppressWarnings("removal")
    @SubscribeEvent
    public void onEntitySize(EntityEvent.Size event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getPose() != Pose.STANDING && event.getPose() != Pose.CROUCHING) {
            return;
        }

        int mode = getMode(player);
        if (mode == 0) {
            return;
        }

        boolean moving = isExpandedForMovement(player, mode);
        EntityDimensions currentSize = event.getNewSize();
        float targetWidth = HitboxConfig.getWidthForMode(mode, moving);
        float targetHeight = HitboxConfig.getHeightForMode(mode);
        float targetEyeHeight = HitboxConfig.getEyeHeightForMode(
                event.getNewEyeHeight(),
                currentSize.height,
                targetHeight
        );

        event.setNewSize(EntityDimensions.scalable(targetWidth, targetHeight));
        event.setNewEyeHeight(targetEyeHeight);
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Player player = event.player;
        int mode = getMode(player);
        boolean moving = isMoving(player);
        boolean previousMoving = getPreviousMovingState(player);
        boolean shouldRefresh = false;

        if (moving != previousMoving) {
            setPreviousMovingState(player, moving);
            if (mode > 0) {
                if (!moving && previousMoving && mode == MODE_THREE) {
                    setStopDelayTicks(player, MODE_THREE_STOP_DELAY_TICKS);
                } else {
                    clearStopDelayTicks(player);
                    shouldRefresh = true;
                }
            } else {
                clearStopDelayTicks(player);
            }
        } else if (mode == MODE_THREE && !moving && getStopDelayTicks(player) > 0) {
            shouldRefresh = decrementStopDelayTicks(player) == 0;
        } else if (mode != MODE_THREE || moving) {
            clearStopDelayTicks(player);
        }

        if (shouldRefresh && mode > 0) {
            player.refreshDimensions();
        }
    }

    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        clearLegacyPersistentData(event.getOriginal());
        clearLegacyPersistentData(event.getEntity());
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            resetServerMode(serverPlayer);
            syncMode(serverPlayer);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            clearServerState(serverPlayer.getUUID());
            clearLegacyPersistentData(serverPlayer);
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            syncMode(serverPlayer);
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            syncMode(serverPlayer);
        }
    }

    @SubscribeEvent
    public void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer trackingPlayer)) {
            return;
        }
        if (!(event.getTarget() instanceof ServerPlayer targetPlayer)) {
            return;
        }

        ModNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> trackingPlayer),
                new SyncHitboxModePacket(targetPlayer.getUUID(), getMode(targetPlayer))
        );
    }

    private static int clampMode(int mode) {
        return Math.max(0, Math.min(2, mode));
    }

    private static boolean isMoving(Player player) {
        double deltaX = player.getX() - player.xo;
        double deltaZ = player.getZ() - player.zo;
        return (deltaX * deltaX) + (deltaZ * deltaZ) > 1.0E-6D;
    }

    private static boolean isExpandedForMovement(Player player, int mode) {
        if (isMoving(player)) {
            return true;
        }
        return isModeThreeStopDelayActive(player);
    }

    private static boolean getPreviousMovingState(Player player) {
        if (player.level().isClientSide) {
            return CLIENT_MOVING_STATES.getOrDefault(player.getUUID(), false);
        }
        return SERVER_MOVING_STATES.getOrDefault(player.getUUID(), false);
    }

    private static void setPreviousMovingState(Player player, boolean moving) {
        if (player.level().isClientSide) {
            if (moving) {
                CLIENT_MOVING_STATES.put(player.getUUID(), true);
            } else {
                CLIENT_MOVING_STATES.remove(player.getUUID());
            }
            return;
        }

        UUID playerId = player.getUUID();
        if (moving) {
            SERVER_MOVING_STATES.put(playerId, true);
        } else {
            SERVER_MOVING_STATES.remove(playerId);
        }
    }

    private static int getStopDelayTicks(Player player) {
        return getStopDelayTicks(player.level().isClientSide, player.getUUID());
    }

    private static int getStopDelayTicks(boolean clientSide, UUID playerId) {
        return (clientSide ? CLIENT_STOP_DELAY_TICKS : SERVER_STOP_DELAY_TICKS).getOrDefault(playerId, 0);
    }

    private static void setStopDelayTicks(Player player, int ticks) {
        Map<UUID, Integer> ticksByPlayer = player.level().isClientSide ? CLIENT_STOP_DELAY_TICKS : SERVER_STOP_DELAY_TICKS;
        ticksByPlayer.put(player.getUUID(), ticks);
    }

    private static int decrementStopDelayTicks(Player player) {
        boolean clientSide = player.level().isClientSide;
        UUID playerId = player.getUUID();
        int remaining = Math.max(0, getStopDelayTicks(clientSide, playerId) - 1);
        if (remaining == 0) {
            clearStopDelayTicks(player);
        } else {
            Map<UUID, Integer> ticksByPlayer = clientSide ? CLIENT_STOP_DELAY_TICKS : SERVER_STOP_DELAY_TICKS;
            ticksByPlayer.put(playerId, remaining);
        }
        return remaining;
    }

    private static void clearStopDelayTicks(Player player) {
        (player.level().isClientSide ? CLIENT_STOP_DELAY_TICKS : SERVER_STOP_DELAY_TICKS).remove(player.getUUID());
    }

    private static void resetServerMode(ServerPlayer player) {
        UUID playerId = player.getUUID();
        clearServerState(playerId);
        clearLegacyPersistentData(player);
    }

    private static void clearServerState(UUID playerId) {
        SERVER_MODES.remove(playerId);
        SERVER_MOVING_STATES.remove(playerId);
        SERVER_STOP_DELAY_TICKS.remove(playerId);
    }

    private static void clearLegacyPersistentData(Player player) {
        player.getPersistentData().remove(LEGACY_HITBOX_MODE_TAG);
        player.getPersistentData().remove(LEGACY_HITBOX_MOVING_TAG);
    }

    private static void syncMode(ServerPlayer player) {
        player.refreshDimensions();
        ModNetwork.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                new SyncHitboxModePacket(player.getUUID(), getMode(player))
        );
    }
}
