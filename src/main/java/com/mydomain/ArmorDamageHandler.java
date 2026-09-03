package com.mydomain;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public class ArmorDamageHandler {
    private static final String CUSTOM_UNBREAKABLE_TAG = "custom_unbreakable";
    private static final String SKIP_DAMAGE_TAG = "skip_damage_to_armor";

    private static final List<EquipmentSlot> ARMOR_SLOTS = Arrays.asList(
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    );

    private static final Map<EquipmentSlot, Double> SLOT_RATIO = new HashMap<>();

    static {
        SLOT_RATIO.put(EquipmentSlot.HEAD, 0.1);
        SLOT_RATIO.put(EquipmentSlot.CHEST, 0.6);
        SLOT_RATIO.put(EquipmentSlot.LEGS, 0.2);
        SLOT_RATIO.put(EquipmentSlot.FEET, 0.1);
    }

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (player.level().isClientSide) {
            return;
        }

        if (player.getPersistentData().getBoolean(SKIP_DAMAGE_TAG)) {
            player.getPersistentData().remove(SKIP_DAMAGE_TAG);
            return;
        }

        float originalDamage = event.getAmount();
        if (originalDamage <= 0) {
            return;
        }

        Map<EquipmentSlot, ItemStack> armorMap = new HashMap<>();
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            armorMap.put(slot, stack);

            if (!stack.isEmpty()) {
                CompoundTag tag = stack.getTag();
                if (tag != null && tag.contains("Unbreakable") && tag.getBoolean("Unbreakable")) {
                    tag.remove("Unbreakable");
                    tag.putBoolean(CUSTOM_UNBREAKABLE_TAG, true);
                }
            }
        }

        Map<EquipmentSlot, Float> slotDamage = new HashMap<>();
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            slotDamage.put(slot, (float) (originalDamage * SLOT_RATIO.get(slot)));
        }

        Map<EquipmentSlot, Float> absorbedDamage = new HashMap<>();
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            absorbedDamage.put(slot, 0f);
        }

        Queue<TransferRequest> transferQueue = new LinkedList<>();

        for (EquipmentSlot slot : ARMOR_SLOTS) {
            float damageToAbsorb = slotDamage.get(slot);
            if (damageToAbsorb <= 0) {
                continue;
            }

            ItemStack stack = armorMap.get(slot);
            if (stack.isEmpty()) {
                continue;
            }

            int durabilityToDeduct = (int) (damageToAbsorb * 10);
            if (durabilityToDeduct <= 0) {
                continue;
            }

            int actualDeduction = calculateActualDeduction(durabilityToDeduct, stack, player);
            int maxDamage = stack.getMaxDamage();
            int currentDamage = stack.getDamageValue();
            int currentDurability = maxDamage - currentDamage;
            boolean isCustomUnbreakable = hasCustomUnbreakableTag(stack);

            if (isCustomUnbreakable) {
                int available = currentDurability - 1;
                if (available <= 0) {
                    transferQueue.add(new TransferRequest(slot, damageToAbsorb));
                    continue;
                }

                if (actualDeduction > available) {
                    stack.setDamageValue(currentDamage + available);
                    float absorbed = available / 10f;
                    absorbedDamage.put(slot, absorbed);

                    float remaining = damageToAbsorb - absorbed;
                    if (remaining > 0) {
                        transferQueue.add(new TransferRequest(slot, remaining));
                    }
                } else {
                    stack.setDamageValue(currentDamage + actualDeduction);
                    absorbedDamage.put(slot, actualDeduction / 10f);
                }
                continue;
            }

            if (actualDeduction > currentDurability) {
                stack.setDamageValue(maxDamage);
                float absorbed = currentDurability / 10f;
                absorbedDamage.put(slot, absorbed);
            } else {
                stack.setDamageValue(currentDamage + actualDeduction);
                absorbedDamage.put(slot, actualDeduction / 10f);
            }
        }

        float remainingForPlayer = 0f;
        while (!transferQueue.isEmpty()) {
            TransferRequest request = transferQueue.poll();
            float remaining = request.damage;
            Set<EquipmentSlot> attempted = new HashSet<>();
            attempted.add(request.slot);

            boolean resolved = false;
            while (!resolved && remaining > 0.001f) {
                boolean found = false;

                for (EquipmentSlot slot : ARMOR_SLOTS) {
                    if (attempted.contains(slot)) {
                        continue;
                    }

                    ItemStack stack = armorMap.get(slot);
                    if (stack.isEmpty()) {
                        attempted.add(slot);
                        continue;
                    }

                    int maxDamage = stack.getMaxDamage();
                    int currentDamage = stack.getDamageValue();
                    int currentDurability = maxDamage - currentDamage;
                    boolean isCustomUnbreakable = hasCustomUnbreakableTag(stack);

                    int availableDurability = isCustomUnbreakable
                            ? Math.max(0, currentDurability - 1)
                            : currentDurability;
                    if (availableDurability <= 0) {
                        attempted.add(slot);
                        continue;
                    }

                    float maxAbsorb = availableDurability / 10f;
                    float toAbsorb = Math.min(remaining, maxAbsorb);
                    if (toAbsorb <= 0) {
                        continue;
                    }

                    int durabilityForThis = (int) (toAbsorb * 10);
                    int actualDeduction = calculateActualDeduction(durabilityForThis, stack, player);
                    int availableNow = isCustomUnbreakable
                            ? Math.max(0, (maxDamage - stack.getDamageValue()) - 1)
                            : maxDamage - stack.getDamageValue();

                    actualDeduction = Math.min(actualDeduction, availableNow);
                    if (actualDeduction <= 0) {
                        attempted.add(slot);
                        continue;
                    }

                    stack.setDamageValue(stack.getDamageValue() + actualDeduction);
                    float absorbedNow = actualDeduction / 10f;
                    absorbedDamage.put(slot, absorbedDamage.get(slot) + absorbedNow);
                    remaining -= absorbedNow;
                    found = true;

                    if (isCustomUnbreakable && maxDamage - stack.getDamageValue() <= 1) {
                        attempted.add(slot);
                    }
                    if (remaining <= 0.001f) {
                        resolved = true;
                        break;
                    }
                }

                if (!found) {
                    remainingForPlayer += remaining;
                    resolved = true;
                }
            }
        }

        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (armorMap.get(slot).isEmpty()) {
                remainingForPlayer += slotDamage.get(slot);
            }
        }

        event.setCanceled(true);

        float finalDamage = remainingForPlayer;
        player.getPersistentData().putBoolean(SKIP_DAMAGE_TAG, true);
        if (finalDamage > 0) {
            player.hurt(player.damageSources().generic(), finalDamage);
        } else {
            player.hurt(player.damageSources().generic(), 0.01f);
            player.heal(0.01f);
        }
    }

    private int calculateActualDeduction(int durabilityToDeduct, ItemStack stack, Player player) {
        int unbreakingLevel = stack.getEnchantmentLevel(Enchantments.UNBREAKING);
        if (unbreakingLevel == 0) {
            return durabilityToDeduct;
        }

        RandomSource random = player.getRandom();
        int baseConsumed = 0;
        for (int i = 0; i < durabilityToDeduct; i++) {
            if (random.nextInt(unbreakingLevel + 1) == 0) {
                baseConsumed++;
            }
        }

        int notConsumed = durabilityToDeduct - baseConsumed;
        return baseConsumed + (int) (notConsumed * 0.4);
    }

    private record TransferRequest(EquipmentSlot slot, float damage) {
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (event.player.level().isClientSide) {
            return;
        }

        Player player = event.player;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (hasCustomUnbreakableTag(stack)) {
                int currentDamage = stack.getDamageValue();
                if (currentDamage > 0) {
                    stack.setDamageValue(Math.max(0, currentDamage - 1));
                }
            }
        }
    }

    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (hasCustomUnbreakableTag(stack)) {
            event.getToolTip().add(Component.translatable("item.unbreakable").withStyle(ChatFormatting.BLUE));
        }
    }

    private boolean hasCustomUnbreakableTag(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(CUSTOM_UNBREAKABLE_TAG);
    }
}
