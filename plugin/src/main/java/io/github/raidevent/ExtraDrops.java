package io.github.raidevent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * クレートの種類に依らない追加報酬。
 *
 * <p>{@link CrateTable} の抽選は「スロットを N 個、weight の比で埋める」相対抽選なので、
 * 「クレートが何であれ一定の確率で出す」ができない。こちらはエントリごとに独立した
 * ベルヌーイ試行で、当たったものがそのままレイド成功の報酬チェストに入る。
 *
 * <p>アイテムの組み立ては {@link CrateTable.LootEntry} と {@link ItemBuilder} をそのまま使うので、
 * {@code custom} / {@code enchants} / {@code potion} はクレートと同じように書ける。
 * {@code weight} だけは意味を持たない (独立判定なので比べる相手が居ない)。
 *
 * <pre>
 * extra-drops:
 *   - {custom: modifier_reset_ticket, chance: 0.08}
 *   - {custom: recall_scroll, min: 1, max: 2, chance: 0.30, min-level: 3}
 * </pre>
 *
 * <p>設定の不備は {@code crates} と同じく起動時の例外にする。
 */
public record ExtraDrops(List<Entry> entries) {

    /**
     * 追加報酬1件。
     *
     * @param chance   レイド成功1回あたりの排出率 (0 より大きく 1 以下)
     * @param minLevel このレベル未満のレイドでは判定しない
     */
    public record Entry(CrateTable.LootEntry loot, double chance, int minLevel) {
    }

    /** 1件も無い表。{@code extra-drops} を書かなければこれになる。 */
    public static ExtraDrops empty() {
        return new ExtraDrops(List.of());
    }

    /**
     * そのレイドで当たった追加報酬。
     *
     * <p>エントリごとに独立して振るので、全部出ることも 1 つも出ないこともある。
     */
    public List<CrateTable.RolledItem> roll(int level, Random random) {
        List<CrateTable.RolledItem> out = new ArrayList<>();
        for (Entry entry : entries) {
            if (level < entry.minLevel() || random.nextDouble() >= entry.chance()) {
                continue;
            }
            CrateTable.LootEntry loot = entry.loot();
            int amount = loot.min() == loot.max() ? loot.min()
                    : loot.min() + random.nextInt(loot.max() - loot.min() + 1);
            out.add(new CrateTable.RolledItem(loot, amount));
        }
        return out;
    }

    /** {@code extra-drops} セクションを読む。無ければ空。 */
    public static ExtraDrops parse(ConfigurationSection root) {
        List<Map<?, ?>> maps = root.getMapList("extra-drops");
        if (maps.isEmpty()) {
            return empty();
        }
        List<Entry> entries = new ArrayList<>();
        for (Map<?, ?> map : maps) {
            String custom = map.get("custom") == null ? null : String.valueOf(map.get("custom"));
            String itemName = custom != null ? custom : String.valueOf(map.get("item"));
            Material material = custom != null
                    ? CustomItems.baseOf(custom).orElse(null)
                    : Material.matchMaterial(itemName);
            if (material == null) {
                throw new IllegalArgumentException("extra-drops に知らないアイテム: " + itemName);
            }
            int min = LevelTable.intOf(map.get("min"), 1);
            int max = LevelTable.intOf(map.get("max"), min);
            if (min < 1 || max < min) {
                throw new IllegalArgumentException(
                        "extra-drops の " + itemName + " の個数が変: " + min + ".." + max);
            }
            double chance = doubleOf(map.get("chance"));
            if (chance <= 0 || chance > 1) {
                throw new IllegalArgumentException("extra-drops の " + itemName
                        + " の chance が 0 より大きく 1 以下ではない: " + chance);
            }
            int minLevel = LevelTable.intOf(map.get("min-level"), 1);
            if (minLevel < 1 || minLevel > LevelTable.MAX_LEVEL) {
                throw new IllegalArgumentException("extra-drops の " + itemName
                        + " の min-level が 1.." + LevelTable.MAX_LEVEL + " の外: " + minLevel);
            }
            Map<String, Integer> enchants = new LinkedHashMap<>();
            if (map.get("enchants") instanceof Map<?, ?> enchantMap) {
                enchantMap.forEach((name, level) ->
                        enchants.put(String.valueOf(name), LevelTable.intOf(level, 1)));
            }
            String potion = map.get("potion") == null ? null : String.valueOf(map.get("potion"));
            // weight は独立判定なので使わない。埋める値は 1 で固定
            entries.add(new Entry(
                    new CrateTable.LootEntry(material, min, max, 1, Map.copyOf(enchants), potion, custom),
                    chance, minLevel));
        }
        return new ExtraDrops(List.copyOf(entries));
    }

    private static double doubleOf(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw == null) {
            throw new IllegalArgumentException("extra-drops のエントリに chance が無い");
        }
        try {
            return Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("extra-drops の chance が数ではない: " + raw, e);
        }
    }
}
