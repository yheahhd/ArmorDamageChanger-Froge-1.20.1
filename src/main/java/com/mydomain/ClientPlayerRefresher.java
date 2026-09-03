package com.mydomain;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

@OnlyIn(Dist.CLIENT)
public class ClientPlayerRefresher {
    public static void refresh(UUID playerId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        for (Player player : minecraft.level.players()) {
            if (player.getUUID().equals(playerId)) {
                player.refreshDimensions();
                return;
            }
        }
    }
}
