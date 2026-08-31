package io.github.raidevent;

import java.util.Comparator;
import java.util.Random;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * レイドモブのスポーンと味付け。
 *
 * <p>方針:
 * <ul>
 *   <li>装備のドロップ率は全部位 0。レイド自体が装備の無料配布になると報酬クレートの
 *       価値が壊れるため。ここが生命線。</li>
 *   <li>{@code setRemoveWhenFarAway(true)}。API スポーンのモブは既定でデスポーンしない
 *       ことがあるが、「逃げられたらデスポーン→キャンセル」の仕様はデスポーンが
 *       生きていないと成立しない。</li>
 *   <li>アンデッドは昼でも燃えないようにする。燃えると昼レイドが自壊する。</li>
 * </ul>
 */
final class MobSpawner {

    /** E2E やコマンドからの掃除で使う共通タグ。 */
    static final String TAG = "raidevent";

    private MobSpawner() {
    }

    /** 1体スポーンして味付けまで済ませる。Mob でない型が来たら null (設定ミス)。 */
    static Mob spawn(Plugin plugin, Location location, TierTable.MobEntry entry,
            TierTable.Tier tier, Random random) {
        Entity entity = location.getWorld().spawnEntity(
                location, entry.type(), CreatureSpawnEvent.SpawnReason.CUSTOM);
        if (!(entity instanceof Mob mob)) {
            entity.remove();
            return null;
        }

        mob.addScoreboardTag(TAG);
        mob.setPersistent(false);
        mob.setRemoveWhenFarAway(true);

        boolean baby = false;
        if (mob instanceof Zombie zombie) {
            if (entry.baby()) {
                zombie.setBaby();
                baby = true;
            } else {
                zombie.setAdult();
            }
            zombie.setShouldBurnInDay(false);
        }
        if (mob instanceof AbstractSkeleton skeleton) {
            skeleton.setShouldBurnInDay(false);
        }

        equip(mob, entry, plugin);
        strengthen(plugin, mob, tier, baby);
        target(mob);
        return mob;
    }

    private static void equip(Mob mob, TierTable.MobEntry entry, Plugin plugin) {
        EntityEquipment equipment = mob.getEquipment();
        if (equipment == null) {
            return;
        }
        if (entry.helmet() != null) {
            equipment.setHelmet(new ItemStack(entry.helmet()));
        }
        if (entry.chestplate() != null) {
            equipment.setChestplate(new ItemStack(entry.chestplate()));
        }
        if (entry.weapon() != null) {
            ItemStack weapon = new ItemStack(entry.weapon());
            entry.weaponEnchants().forEach((name, level) -> {
                var enchantment = ItemBuilder.enchantment(name);
                if (enchantment == null) {
                    plugin.getSLF4JLogger().warn("知らないエンチャント {} を飛ばした (モブ武器)", name);
                } else {
                    weapon.addUnsafeEnchantment(enchantment, level);
                }
            });
            equipment.setItemInMainHand(weapon);
        }
        // 報酬経済を守る生命線。モブの装備は絶対に落とさせない。
        equipment.setHelmetDropChance(0f);
        equipment.setChestplateDropChance(0f);
        equipment.setLeggingsDropChance(0f);
        equipment.setBootsDropChance(0f);
        equipment.setItemInMainHandDropChance(0f);
        equipment.setItemInOffHandDropChance(0f);
    }

    /** ティアの倍率を transient な attribute 修正で乗せる。子ゾンビは元から速いので speed は乗せない。 */
    private static void strengthen(Plugin plugin, Mob mob, TierTable.Tier tier, boolean baby) {
        scale(mob, Attribute.MAX_HEALTH, new NamespacedKey(plugin, "tier-health"), tier.health());
        AttributeInstance health = mob.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            mob.setHealth(health.getValue());
        }
        scale(mob, Attribute.ATTACK_DAMAGE, new NamespacedKey(plugin, "tier-damage"), tier.damage());
        if (!baby) {
            scale(mob, Attribute.MOVEMENT_SPEED, new NamespacedKey(plugin, "tier-speed"), tier.speed());
        }
    }

    private static void scale(Mob mob, Attribute attribute, NamespacedKey key, double multiplier) {
        if (multiplier == 1.0) {
            return;
        }
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance == null) {
            // ウィッチに攻撃力が無いなど、型によっては持っていない。それで構わない
            return;
        }
        instance.removeModifier(key);
        instance.addTransientModifier(new AttributeModifier(
                key, multiplier - 1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }

    /**
     * 最寄りのプレイヤーを最初のターゲットにする。
     *
     * <p>クモは昼だと中立化するし、湧いた直後にうろうろされるとレイド感が無い。
     * 明示的に仕向けておく。
     */
    private static void target(Mob mob) {
        mob.getWorld().getNearbyPlayers(mob.getLocation(), 48).stream()
                .filter(MobSpawner::isSurvivalLike)
                .min(Comparator.comparingDouble(
                        player -> player.getLocation().distanceSquared(mob.getLocation())))
                .ifPresent(mob::setTarget);
    }

    /** レイドの対象になる遊び方をしているか。クリエイティブと観戦は巻き込まない。 */
    static boolean isSurvivalLike(Player player) {
        return switch (player.getGameMode()) {
            case SURVIVAL, ADVENTURE -> true;
            default -> false;
        };
    }
}
