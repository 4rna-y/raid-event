package io.github.raidevent;

import java.util.List;
import java.util.Optional;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.FoodProperties;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.kyori.adventure.key.Key;
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
 * <p>振る舞いは持ち主のプラグインが実装する (異次元チェスト・スナイパーライフル・爆裂弓は relics、
 * 復活剤とリセットチケットは Modifier、リコールスクロール 3 種は manchor)。持ち主が居なければ
 * 名前の付いた飾りになる。印のキーと土台は持ち主側と一致させること。
 *
 * <p>説明文に出す数字 (スナイパーの残弾・スクロールのチャージ秒と半径・チケットのチャージ秒) は
 * 持ち主の<b>既定の設定</b>の写し。向こうで設定を変えると説明が食い違い、
 * {@code /relics give} や {@code /manchor give} で出た物と重ならなくなる
 * (効くかどうかは印だけで決まるので、使えなくなるわけではない)。
 */
public final class CustomItems {

    public static final String DIMENSIONAL_CHEST = "dimensional_chest";
    public static final String SNIPER_RIFLE = "sniper_rifle";
    public static final String EXPLOSIVE_BOW = "explosive_bow";
    public static final String MODIFIER_REVIVAL = "modifier_revival";
    public static final String MODIFIER_RESET_TICKET = "modifier_reset_ticket";
    public static final String RECALL_SCROLL = "recall_scroll";
    public static final String SPATIAL_RECALL_SCROLL = "spatial_recall_scroll";
    public static final String EMERGENCY_RECALL_SCROLL = "emergency_recall_scroll";

    /** 作れる id の一覧。試験と設定の検証で使う。 */
    public static final List<String> IDS = List.of(
            DIMENSIONAL_CHEST, SNIPER_RIFLE, EXPLOSIVE_BOW,
            MODIFIER_REVIVAL, MODIFIER_RESET_TICKET,
            RECALL_SCROLL, SPATIAL_RECALL_SCROLL, EMERGENCY_RECALL_SCROLL);

    static final NamespacedKey RELICS_ITEM = new NamespacedKey("relics", "item");
    static final NamespacedKey RELICS_SHOTS = new NamespacedKey("relics", "shots");
    static final NamespacedKey MODIFIER_ITEM = new NamespacedKey("modifier", "item");
    static final NamespacedKey MANCHOR_ITEM = new NamespacedKey("manchor", "item");

    /** スナイパーライフルが壊れるまでの発射数。relics の {@code sniper.shots} の既定。 */
    public static final int SNIPER_SHOTS = 200;

    /** manchor の {@code recall.charge-seconds} の既定。 */
    static final float SCROLL_CHARGE_SECONDS = 3f;
    /** manchor の {@code recall.spatial-radius} の既定。 */
    static final int SPATIAL_RADIUS = 5;
    /** 空間リコールの consumable の長さ。離した瞬間が発動なので、完走しない値にしてある。 */
    static final float SPATIAL_HOLD_SECONDS = 60f;
    /** Modifier の {@code ResetTicket.CHARGE_SECONDS}。 */
    static final float TICKET_CHARGE_SECONDS = 3f;

    private CustomItems() {
    }

    /** 土台のアイテム。知らない id なら空。 */
    public static Optional<Material> baseOf(String id) {
        return switch (id) {
            case DIMENSIONAL_CHEST -> Optional.of(Material.ENDER_CHEST);
            case SNIPER_RIFLE -> Optional.of(Material.SPYGLASS);
            case EXPLOSIVE_BOW -> Optional.of(Material.BOW);
            case MODIFIER_REVIVAL -> Optional.of(Material.GOLDEN_APPLE);
            case MODIFIER_RESET_TICKET, RECALL_SCROLL, SPATIAL_RECALL_SCROLL, EMERGENCY_RECALL_SCROLL ->
                    Optional.of(Material.PAPER);
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
            case EXPLOSIVE_BOW -> {
                // 弓は重ねられないので amount は見ない
                ItemStack item = ItemStack.of(Material.BOW, 1);
                name(item, "爆裂弓", NamedTextColor.RED);
                lore(item, "放った矢が敵対モブに当たると、命中地点で爆発する",
                        "ブロックは壊れず、火も点かない。近くの仲間は巻き込む");
                item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
                item.editPersistentDataContainer(pdc -> pdc.set(RELICS_ITEM, PersistentDataType.STRING, EXPLOSIVE_BOW));
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
            case MODIFIER_RESET_TICKET -> {
                ItemStack item = ItemStack.of(Material.PAPER, amount);
                name(item, "モディファイアリセットチケット", NamedTextColor.LIGHT_PURPLE);
                lore(item, "長押しで " + (int) TICKET_CHARGE_SECONDS + " 秒チャージするとモディファイアを選び直す",
                        "今の効果は失われる。開始アイテムは配られない", "使うと 1 枚消える");
                item.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                        .consumeSeconds(TICKET_CHARGE_SECONDS)
                        .animation(ItemUseAnimation.BOW)
                        .sound(Key.key("block.beacon.ambient"))
                        .hasConsumeParticles(false)
                        .build());
                item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
                item.editPersistentDataContainer(pdc -> pdc.set(MODIFIER_ITEM, PersistentDataType.STRING, "reset_ticket"));
                yield item;
            }
            case RECALL_SCROLL -> {
                ItemStack item = ItemStack.of(Material.PAPER, amount);
                name(item, "リコールスクロール", NamedTextColor.AQUA);
                lore(item, "長押しで " + (int) SCROLL_CHARGE_SECONDS + " 秒チャージするとアンカーへ帰る",
                        "使うと 1 枚消える");
                item.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                        .consumeSeconds(SCROLL_CHARGE_SECONDS)
                        .animation(ItemUseAnimation.BOW)
                        .sound(Key.key("block.beacon.ambient"))
                        .hasConsumeParticles(false)
                        .build());
                item.editPersistentDataContainer(pdc -> pdc.set(MANCHOR_ITEM, PersistentDataType.STRING, RECALL_SCROLL));
                yield item;
            }
            case SPATIAL_RECALL_SCROLL -> {
                ItemStack item = ItemStack.of(Material.PAPER, amount);
                name(item, "空間リコールスクロール", NamedTextColor.LIGHT_PURPLE);
                item.setData(DataComponentTypes.ITEM_MODEL, Key.key("minecraft:map"));
                lore(item, "長押しで半径 " + SPATIAL_RADIUS + " ブロックの領域を示し",
                        "離すと領域内のプレイヤー全員がアンカーへ帰る", "使うと 1 枚消える");
                item.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                        .consumeSeconds(SPATIAL_HOLD_SECONDS)
                        .animation(ItemUseAnimation.BOW)
                        .sound(Key.key("block.conduit.ambient"))
                        .hasConsumeParticles(false)
                        .build());
                item.editPersistentDataContainer(pdc -> pdc.set(MANCHOR_ITEM, PersistentDataType.STRING, SPATIAL_RECALL_SCROLL));
                yield item;
            }
            case EMERGENCY_RECALL_SCROLL -> {
                ItemStack item = ItemStack.of(Material.PAPER, amount);
                name(item, "緊急リコールスクロール", NamedTextColor.RED);
                item.setData(DataComponentTypes.ITEM_MODEL, Key.key("minecraft:written_book"));
                lore(item, "右クリックで即座にアンカーへ帰る",
                        "代償: 体力と満腹度が 2 になり、経験値が半減し、",
                        "装備の残り耐久が半減する。使うと 1 枚消える");
                item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
                item.editPersistentDataContainer(pdc -> pdc.set(MANCHOR_ITEM, PersistentDataType.STRING, EMERGENCY_RECALL_SCROLL));
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
