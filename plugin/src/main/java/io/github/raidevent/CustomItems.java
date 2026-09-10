package io.github.raidevent;

import java.util.List;
import java.util.Optional;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.FoodProperties;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * 高難易度の報酬に出す特別なアイテム。土台アイテム + PDC の印 + 名前で表す。
 *
 * <p>振る舞いは持ち主のプラグインが実装する (異次元チェストとスナイパーライフルは relics、
 * 復活剤は Modifier)。持ち主が居なければ名前の付いた飾りになる。印のキーと土台は
 * 持ち主側と一致させること。
 */
public final class CustomItems {

    public static final String DIMENSIONAL_CHEST = "dimensional_chest";
    public static final String SNIPER_RIFLE = "sniper_rifle";
    public static final String MODIFIER_REVIVAL = "modifier_revival";

    static final NamespacedKey RELICS_ITEM = new NamespacedKey("relics", "item");
    static final NamespacedKey RELICS_SHOTS = new NamespacedKey("relics", "shots");
    static final NamespacedKey MODIFIER_ITEM = new NamespacedKey("modifier", "item");

    /** スナイパーライフルが壊れるまでの発射数。 */
    public static final int SNIPER_SHOTS = 200;

    private CustomItems() {
    }

    /** 土台のアイテム。知らない id なら空。 */
    public static Optional<Material> baseOf(String id) {
        return switch (id) {
            case DIMENSIONAL_CHEST -> Optional.of(Material.ENDER_CHEST);
            case SNIPER_RIFLE -> Optional.of(Material.SPYGLASS);
            case MODIFIER_REVIVAL -> Optional.of(Material.GOLDEN_APPLE);
            default -> Optional.empty();
        };
    }

    /** 印付きのアイテムを作る。サーバーが要る。 */
    public static ItemStack create(String id, int amount) {
        return switch (id) {
            case DIMENSIONAL_CHEST -> {
                ItemStack item = ItemStack.of(Material.ENDER_CHEST, amount);
                name(item, "異次元チェスト", NamedTextColor.LIGHT_PURPLE);
                lore(item, "右クリックで自分だけの倉庫 (54 マス) を開く", "設置はできない。誰が持っても開くのは持ち主の倉庫");
                item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
                item.editPersistentDataContainer(pdc -> pdc.set(RELICS_ITEM, PersistentDataType.STRING, DIMENSIONAL_CHEST));
                yield item;
            }
            case SNIPER_RIFLE -> {
                ItemStack item = ItemStack.of(Material.SPYGLASS, 1);
                name(item, "スナイパーライフル", NamedTextColor.GOLD);
                lore(item, "覗いている間に左クリックで発射", "弾: アメジストの欠片 1 個", "残り " + SNIPER_SHOTS + " 発");
                item.editPersistentDataContainer(pdc -> {
                    pdc.set(RELICS_ITEM, PersistentDataType.STRING, SNIPER_RIFLE);
                    pdc.set(RELICS_SHOTS, PersistentDataType.INTEGER, 0);
                });
                yield item;
            }
            case MODIFIER_REVIVAL -> {
                ItemStack item = ItemStack.of(Material.GOLDEN_APPLE, amount);
                name(item, "モディファイア復活剤", NamedTextColor.AQUA);
                lore(item, "食べると、一度きりの効果 (冷笑・死神ルーレット) が", "使用済みなら新品に戻る。満腹でも食べられる");
                item.setData(DataComponentTypes.FOOD, FoodProperties.food()
                        .nutrition(4).saturation(9.6f).canAlwaysEat(true).build());
                item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
                item.editPersistentDataContainer(pdc -> pdc.set(MODIFIER_ITEM, PersistentDataType.STRING, "revival"));
                yield item;
            }
            default -> throw new IllegalArgumentException("知らない特別なアイテム: " + id);
        };
    }

    private static void name(ItemStack item, String text, NamedTextColor color) {
        item.setData(DataComponentTypes.CUSTOM_NAME,
                Component.text(text, color).decoration(TextDecoration.ITALIC, false));
    }

    private static void lore(ItemStack item, String... lines) {
        item.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(lines).stream()
                .map(line -> (Component) Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                .toList()));
    }
}
