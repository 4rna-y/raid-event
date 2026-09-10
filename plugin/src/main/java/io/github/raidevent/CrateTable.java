package io.github.raidevent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 報酬クレートのロットテーブル。config.yml の {@code crates} を読む。
 *
 * <p>エンチャント名とポーション名はここでは文字列のまま持つ (レジストリの解決は
 * サーバーが要るので {@link ItemBuilder} に任せる)。抽選そのものは純粋なロジックなので、
 * サーバー無しでテストできる。
 */
public record CrateTable(Map<String, Crate> crates) {

    /** チェストのスロット数。充填率はこの中でのスロット数で表す。 */
    public static final int CHEST_SLOTS = 27;

    public record Crate(String id, String displayName, Map<Integer, CrateLevel> levels) {

        public CrateLevel level(int number) {
            CrateLevel level = levels.get(number);
            if (level == null) {
                throw new IllegalArgumentException("クレート " + id + " にレベル " + number + " が無い");
            }
            return level;
        }
    }

    /** クレート1種の1レベルぶん。 */
    public record CrateLevel(int slotsMin, int slotsMax, List<LootEntry> pool,
            List<LootEntry> featured) {

        /**
         * 中身を抽選する。featured があれば、まずそこから1つ確定で選ぶ (目玉枠)。
         * 残りのスロットは pool から重み付きで独立に選ぶ (同じアイテムの重複あり)。
         */
        public List<RolledItem> roll(Random random) {
            int slots = slotsMin + random.nextInt(slotsMax - slotsMin + 1);
            List<RolledItem> out = new ArrayList<>();
            if (!featured.isEmpty()) {
                out.add(rollOne(featured, random));
                slots--;
            }
            for (int i = 0; i < slots; i++) {
                out.add(rollOne(pool, random));
            }
            return out;
        }

        private static RolledItem rollOne(List<LootEntry> entries, Random random) {
            int total = entries.stream().mapToInt(LootEntry::weight).sum();
            int pick = random.nextInt(total);
            for (LootEntry entry : entries) {
                pick -= entry.weight();
                if (pick < 0) {
                    int amount = entry.min() == entry.max() ? entry.min()
                            : entry.min() + random.nextInt(entry.max() - entry.min() + 1);
                    return new RolledItem(entry, amount);
                }
            }
            throw new IllegalStateException("抽選が壊れている");
        }
    }

    /**
     * ロット1件。{@code enchants} のキーはエンチャント名、{@code potion} はバニラの
     * PotionType 名 (potion / splash_potion / lingering_potion のときだけ意味を持つ)。
     */
    public record LootEntry(Material item, int min, int max, int weight,
            Map<String, Integer> enchants, String potion, String custom) {

        public LootEntry(Material item, int min, int max, int weight, Map<String, Integer> enchants, String potion) {
            this(item, min, max, weight, enchants, potion, null);
        }
    }

    /** 抽選済みの1スロットぶん。 */
    public record RolledItem(LootEntry entry, int amount) {
    }

    public Crate crate(String id) {
        Crate crate = crates.get(id);
        if (crate == null) {
            throw new IllegalArgumentException("クレート " + id + " が設定に無い");
        }
        return crate;
    }

    /** {@code crates} セクションを読む。不備は起動時に気付きたいので例外にする。 */
    public static CrateTable parse(ConfigurationSection root) {
        ConfigurationSection section = root.getConfigurationSection("crates");
        if (section == null) {
            throw new IllegalArgumentException("config.yml に crates が無い");
        }
        Map<String, Crate> crates = new LinkedHashMap<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection crateSection = section.getConfigurationSection(id);
            if (crateSection == null) {
                throw new IllegalArgumentException("crates." + id + " の形が違う");
            }
            crates.put(id, parseCrate(id, crateSection));
        }
        if (crates.isEmpty()) {
            throw new IllegalArgumentException("crates が空");
        }
        return new CrateTable(Map.copyOf(crates));
    }

    private static Crate parseCrate(String id, ConfigurationSection section) {
        String displayName = section.getString("display-name", id);
        ConfigurationSection levelsSection = section.getConfigurationSection("levels");
        if (levelsSection == null) {
            throw new IllegalArgumentException("crates." + id + " に levels が無い");
        }
        Map<Integer, CrateLevel> levels = new LinkedHashMap<>();
        for (int number = 1; number <= LevelTable.MAX_LEVEL; number++) {
            ConfigurationSection levelSection =
                    levelsSection.getConfigurationSection(String.valueOf(number));
            if (levelSection == null) {
                throw new IllegalArgumentException("crates." + id + ".levels." + number + " が無い");
            }
            levels.put(number, parseLevel(id, number, levelSection));
        }
        return new Crate(id, displayName, Map.copyOf(levels));
    }

    private static CrateLevel parseLevel(String id, int number, ConfigurationSection section) {
        ConfigurationSection slots = section.getConfigurationSection("slots");
        int min = slots == null ? 1 : slots.getInt("min", 1);
        int max = slots == null ? min : slots.getInt("max", min);
        if (min < 1 || max < min || max > CHEST_SLOTS) {
            throw new IllegalArgumentException("crates." + id + ".levels." + number
                    + " の slots が変 (1 <= min <= max <= " + CHEST_SLOTS + "): "
                    + min + ".." + max);
        }
        List<LootEntry> pool = parseEntries(id, number, section.getMapList("pool"));
        if (pool.isEmpty()) {
            throw new IllegalArgumentException("crates." + id + ".levels." + number + " の pool が空");
        }
        List<LootEntry> featured = parseEntries(id, number, section.getMapList("featured"));
        return new CrateLevel(min, max, pool, featured);
    }

    private static List<LootEntry> parseEntries(String id, int number, List<Map<?, ?>> maps) {
        List<LootEntry> entries = new ArrayList<>();
        for (Map<?, ?> map : maps) {
            // custom: 特別なアイテム (異次元チェストなど)。土台は CustomItems が決める
            String custom = map.get("custom") == null ? null : String.valueOf(map.get("custom"));
            String itemName = custom != null ? custom : String.valueOf(map.get("item"));
            Material material = custom != null
                    ? CustomItems.baseOf(custom).orElse(null)
                    : Material.matchMaterial(itemName);
            if (material == null) {
                throw new IllegalArgumentException(
                        "crates." + id + ".levels." + number + " に知らないアイテム: " + itemName);
            }
            int min = LevelTable.intOf(map.get("min"), 1);
            int max = LevelTable.intOf(map.get("max"), min);
            int weight = LevelTable.intOf(map.get("weight"), 0);
            if (weight < 1) {
                throw new IllegalArgumentException("crates." + id + ".levels." + number
                        + " の " + itemName + " の weight が 1 未満");
            }
            if (min < 1 || max < min) {
                throw new IllegalArgumentException("crates." + id + ".levels." + number
                        + " の " + itemName + " の個数が変: " + min + ".." + max);
            }
            Map<String, Integer> enchants = new LinkedHashMap<>();
            if (map.get("enchants") instanceof Map<?, ?> enchantMap) {
                enchantMap.forEach((name, level) ->
                        enchants.put(String.valueOf(name), LevelTable.intOf(level, 1)));
            }
            String potion = map.get("potion") == null ? null : String.valueOf(map.get("potion"));
            entries.add(new LootEntry(material, min, max, weight, Map.copyOf(enchants), potion, custom));
        }
        return List.copyOf(entries);
    }
}
