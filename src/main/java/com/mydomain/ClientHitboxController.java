package com.mydomain;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod.EventBusSubscriber(modid = DamageToArmorMod.MOD_ID, value = Dist.CLIENT)
public class ClientHitboxController {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final KeyMapping TOGGLE_HITBOX_KEY = new KeyMapping(
            "key.damagetoarmor.toggle_hitbox",
            GLFW.GLFW_KEY_Z,
            "key.categories.damagetoarmor"
    );
    private static final AtomicBoolean CAMERA_OFFSET_WARNING_LOGGED = new AtomicBoolean(false);

    private static Method cameraSetPositionVec3Method;
    private static Method cameraSetPositionXYZMethod;
    private static boolean cameraSetPositionResolved;

    // 第三阶段本地延迟（PlayerSizeHandler.MODE_THREE == 2）
    private static long thirdModeLastMovementTick = -1L;
    private static final int CAMERA_OFFSET_DELAY_TICKS = 10;
    private static final int MODE_THIRD = 2;    // 与 PlayerSizeHandler 一致

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // 按键切换模式
        if (TOGGLE_HITBOX_KEY.consumeClick()) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                int currentMode = PlayerSizeHandler.getClientMode(minecraft.player.getUUID());
                int nextMode = PlayerSizeHandler.getNextMode(currentMode);
                PlayerSizeHandler.setClientMode(minecraft.player.getUUID(), nextMode);
                minecraft.player.refreshDimensions();
                ModNetwork.CHANNEL.sendToServer(new SetHitboxModePacket(nextMode));
                minecraft.player.displayClientMessage(Component.literal(
                        "Hitbox mode: " + PlayerSizeHandler.getModeDisplayName(nextMode)
                ), true);
            }
        }

        // 安全重置：玩家消失时重置延迟计时
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            thirdModeLastMovementTick = -1L;
        }
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) return;

        Camera camera = event.getCamera();
        if (camera.getEntity() != player || camera.isDetached()) return;

        int mode = PlayerSizeHandler.getClientMode(player.getUUID());
        if (mode <= 0 || !isCameraOffsetActive(player, mode)) return;

        double offset = HitboxConfig.getMovingCameraForwardOffset();
        if (offset <= 0.0D) return;

        Vector3f look = camera.getLookVector();
        Vec3 basePos = camera.getPosition();   // 直接使用游戏计算的相机位置（包含正确的眼睛高度）

        Vec3 newPos = basePos.add(
                look.x() * offset,
                look.y() * offset,
                look.z() * offset
        );

        applyCameraPosition(camera, newPos);
    }

    private static boolean isCameraOffsetActive(LocalPlayer player, int mode) {
        if (mode == MODE_THIRD) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return false;
            long gameTime = mc.level.getGameTime();

            boolean hasInput = hasMovementInput(player);
            if (hasInput) {
                thirdModeLastMovementTick = gameTime;
                return true;
            }

            // 停止输入后，10 tick 内继续保持偏移
            return thirdModeLastMovementTick >= 0 &&
                    (gameTime - thirdModeLastMovementTick) < CAMERA_OFFSET_DELAY_TICKS;
        } else {
            // 非第三阶段：有输入就偏移，无输入立即停止
            return hasMovementInput(player);
        }
    }

    private static boolean hasMovementInput(LocalPlayer player) {
        Input input = player.input;
        if (input == null) return false;
        boolean hasHorizontalInput = Math.abs(input.forwardImpulse) > 1.0E-3F
                || Math.abs(input.leftImpulse) > 1.0E-3F;
        boolean hasFlightVerticalInput = player.getAbilities().flying
                && (input.jumping || input.shiftKeyDown);
        return hasHorizontalInput || hasFlightVerticalInput;
    }

    // ------ 反射设置相机位置（保持不变）------
    private static void applyCameraPosition(Camera camera, Vec3 position) {
        Method vec3Method = resolveCameraSetPositionMethod(true);
        if (vec3Method != null) {
            try {
                vec3Method.invoke(camera, position);
                return;
            } catch (ReflectiveOperationException exception) {
                logCameraOffsetWarning("Failed to invoke Camera#setPosition(Vec3); camera offset disabled.", exception);
                cameraSetPositionVec3Method = null;
            }
        }

        Method xyzMethod = resolveCameraSetPositionMethod(false);
        if (xyzMethod != null) {
            try {
                xyzMethod.invoke(camera, position.x, position.y, position.z);
                return;
            } catch (ReflectiveOperationException exception) {
                logCameraOffsetWarning("Failed to invoke Camera#setPosition(double, double, double); camera offset disabled.", exception);
                cameraSetPositionXYZMethod = null;
            }
        }
    }

    private static Method resolveCameraSetPositionMethod(boolean preferVec3) {
        if (!cameraSetPositionResolved) {
            resolveCameraSetPositionMethods();
        }
        return preferVec3 ? cameraSetPositionVec3Method : cameraSetPositionXYZMethod;
    }

    private static synchronized void resolveCameraSetPositionMethods() {
        if (cameraSetPositionResolved) return;

        for (Method method : Camera.class.getDeclaredMethods()) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 1 && parameterTypes[0] == Vec3.class) {
                method.setAccessible(true);
                cameraSetPositionVec3Method = method;
            } else if (parameterTypes.length == 3
                    && parameterTypes[0] == double.class
                    && parameterTypes[1] == double.class
                    && parameterTypes[2] == double.class) {
                method.setAccessible(true);
                cameraSetPositionXYZMethod = method;
            }
        }

        cameraSetPositionResolved = true;
        if (cameraSetPositionVec3Method == null && cameraSetPositionXYZMethod == null) {
            logCameraOffsetWarning("Unable to resolve a compatible Camera#setPosition method; camera offset disabled.", null);
        }
    }

    private static void logCameraOffsetWarning(String message, Exception exception) {
        if (CAMERA_OFFSET_WARNING_LOGGED.compareAndSet(false, true)) {
            if (exception == null) {
                LOGGER.warn(message);
            } else {
                LOGGER.warn(message, exception);
            }
        }
    }

    @Mod.EventBusSubscriber(modid = DamageToArmorMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModBusEvents {
        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(TOGGLE_HITBOX_KEY);
        }
    }
}