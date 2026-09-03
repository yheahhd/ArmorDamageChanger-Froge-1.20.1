package com.mydomain;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod(DamageToArmorMod.MOD_ID)
public class DamageToArmorMod {
    public static final String MOD_ID = "damagetoarmor";

    public DamageToArmorMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, HitboxConfig.SPEC);
        ModNetwork.init();
        MinecraftForge.EVENT_BUS.register(new ArmorDamageHandler());
        MinecraftForge.EVENT_BUS.register(new PlayerSizeHandler());
    }
}
