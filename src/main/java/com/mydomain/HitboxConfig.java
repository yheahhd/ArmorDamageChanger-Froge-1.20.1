package com.mydomain;

import net.minecraftforge.common.ForgeConfigSpec;

public class HitboxConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.DoubleValue MODE_TWO_HEIGHT;
    public static final ForgeConfigSpec.DoubleValue MODE_TWO_WIDTH;
    public static final ForgeConfigSpec.DoubleValue MODE_THREE_HEIGHT;
    public static final ForgeConfigSpec.DoubleValue MODE_THREE_WIDTH;
    public static final ForgeConfigSpec.DoubleValue MOVING_WIDTH_BONUS;
    public static final ForgeConfigSpec.DoubleValue MOVING_CAMERA_FORWARD_OFFSET;
    public static final ForgeConfigSpec SPEC;

    static {
        BUILDER.push("hitbox_modes");

        MODE_TWO_HEIGHT = BUILDER
                .comment("Mode 2 player hitbox height.")
                .defineInRange("mode2Height", 3.0D, 0.1D, 20.0D);
        MODE_TWO_WIDTH = BUILDER
                .comment("Mode 2 player hitbox width.")
                .defineInRange("mode2Width", 1.3D, 0.1D, 20.0D);
        MODE_THREE_HEIGHT = BUILDER
                .comment("Mode 3 player hitbox height.")
                .defineInRange("mode3Height", 4.1D, 0.1D, 20.0D);
        MODE_THREE_WIDTH = BUILDER
                .comment("Mode 3 player hitbox width.")
                .defineInRange("mode3Width", 1.4D, 0.1D, 20.0D);
        MOVING_WIDTH_BONUS = BUILDER
                .comment("Additional width added while moving in mode 2 or 3.")
                .defineInRange("movingWidthBonus", 0.5D, 0.0D, 20.0D);
        MOVING_CAMERA_FORWARD_OFFSET = BUILDER
                .comment("Camera forward offset applied while moving in mode 2 or 3.")
                .defineInRange("movingCameraForwardOffset", 0.45D, 0.0D, 20.0D);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    public static float getHeightForMode(int mode) {
        return switch (mode) {
            case 1 -> MODE_TWO_HEIGHT.get().floatValue();
            case 2 -> MODE_THREE_HEIGHT.get().floatValue();
            default -> 0.0F;
        };
    }

    public static float getWidthForMode(int mode, boolean moving) {
        float width = switch (mode) {
            case 1 -> MODE_TWO_WIDTH.get().floatValue();
            case 2 -> MODE_THREE_WIDTH.get().floatValue();
            default -> 0.0F;
        };
        if (mode > 0 && moving) {
            width += MOVING_WIDTH_BONUS.get().floatValue();
        }
        return width;
    }

    public static float getEyeHeightForMode(float originalEyeHeight, float originalHeight, float targetHeight) {
        float adjustedEyeHeight = originalEyeHeight + (targetHeight - originalHeight);
        return Math.max(0.1F, Math.min(targetHeight - 0.1F, adjustedEyeHeight));
    }

    public static double getMovingCameraForwardOffset() {
        return MOVING_CAMERA_FORWARD_OFFSET.get();
    }
}
