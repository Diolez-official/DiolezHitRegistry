package diolez.hitregistry.util;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;

/**
 * DamageEstimator — best-effort client-side combat damage approximation.
 *
 * This is intentionally imprecise. The goal is NOT to compute exact damage
 * (that requires server-side armour toughness, enchant stacks, and damage
 * modifiers that aren't all visible client-side). The goal is to produce
 * a plausible visual estimate for health bar prediction.
 *
 * Why imprecision is acceptable:
 *   - The visual health bar will be corrected by the server's authoritative
 *     value once the EntityStatus/HealthSync packet arrives (~1 RTT).
 *   - A small over/under-estimate produces a tiny snap on correction,
 *     which is preferable to showing zero feedback (ghost hit feel).
 *
 * The estimate is deliberately conservative (slight under-estimation) to
 * avoid the jarring case where we show the enemy at 0HP but the server
 * says they're still alive.
 */
public final class DamageEstimator {

    private DamageEstimator() {}

    /**
     * Estimate the damage the attacker will deal to the target.
     *
     * Factors included (client-visible):
     *   - Base weapon damage from ItemStack attributes
     *   - Strength / Weakness effect multipliers
     *   - Sharpness enchant bonus (additive)
     *   - Charge-up swing multiplier (attack strength cooldown)
     *   - Armour value (visible via EntityAttribute)
     *
     * Factors NOT included (server-only):
     *   - Armour toughness reduction formula
     *   - Protection / Blast Protection enchants on target
     *   - Server-side damage caps and post-processing hooks
     *   - AntiKB, invincibility frame exact timing
     */
    public static float estimate(PlayerEntity attacker, LivingEntity target) {

        // ── Step 1: Raw weapon damage ─────────────────────────────────────────

        ItemStack weapon = attacker.getMainHandStack();
        float baseDmg = getWeaponBaseDamage(attacker, weapon);

        // ── Step 2: Attack charge multiplier ─────────────────────────────────
        // At full charge (1.0), damage is full. At 0.0, only 20% damage.
        // We square the multiplier to match vanilla's cooldown curve.
        float chargeMultiplier = attacker.getAttackCooldownProgress(0.5f);
        chargeMultiplier = 0.2f + chargeMultiplier * chargeMultiplier * 0.8f;
        float rawDmg = baseDmg * chargeMultiplier;

        // ── Step 3: Strength / Weakness effects ───────────────────────────────
        if (attacker.hasStatusEffect(StatusEffects.STRENGTH)) {
            int amplifier = attacker.getStatusEffect(StatusEffects.STRENGTH).getAmplifier();
            rawDmg += 3f * (amplifier + 1); // Vanilla: +3 per level
        }
        if (attacker.hasStatusEffect(StatusEffects.WEAKNESS)) {
            rawDmg = Math.max(0f, rawDmg - 4f); // Vanilla: -4
        }

        // ── Step 4: Critical hit bonus ────────────────────────────────────────
        // Critical requires: falling, not on ladder, not in water, full charge
        boolean isCrit = attacker.fallDistance > 0
                && !attacker.isOnGround()
                && !attacker.isClimbing()
                && chargeMultiplier >= 0.9f;
        if (isCrit) {
            rawDmg *= 1.5f; // Vanilla crit multiplier
        }

        // ── Step 5: Armour mitigation (approximate) ────────────────────────────
        // Visible armour value, no toughness. Under-estimates mitigation
        // on diamond/netherite, which keeps our prediction conservative.
        double armour = target.getAttributeValue(EntityAttributes.GENERIC_ARMOR);
        // Vanilla formula simplified: reduction ≈ min(armour*0.04, 0.8)
        float armourFactor = (float) Math.min(armour * 0.04, 0.80);
        float afterArmour = rawDmg * (1f - armourFactor);

        // ── Step 6: Conservative fudge factor ─────────────────────────────────
        // Reduce by 10% to account for server-side modifiers we can't see.
        // This ensures we rarely over-predict (the jarring case).
        float estimate = afterArmour * 0.90f;

        return Math.max(0f, estimate);
    }

    /**
     * Extract the flat attack damage from a weapon ItemStack.
     * Falls back to 1.0 (bare hand) if no attribute found.
     */
    private static float getWeaponBaseDamage(PlayerEntity player, ItemStack weapon) {
        // Use player's computed attack damage attribute (includes weapon bonus)
        return (float) player.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE);
    }
}
