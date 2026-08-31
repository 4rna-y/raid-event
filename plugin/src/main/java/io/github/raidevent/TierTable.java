package io.github.raidevent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

/**
 * ティアごとのウェーブ構成と強化倍率。config.yml の {@code tiers} を読む。
 *
 * <p>死=ワールド消滅のサーバー (wiah) なので、一撃事故を起こすモブと遠距離の集中砲火は
 * ここで機械的に拒否する。設定を編集した人が意図せず危険な構成を作れないようにするため。
 */
public record TierTable(Map<Integer, Tier> tiers) {

    /** 昼の基礎ティアの上限。Tier 5 は夜 (+1) でしか出ない。 */
    public static final int MAX_TIER = 5;
    public static final int DAY_MAX_TIER = 4;

    /** 一撃事故の元になるので使わないモブ。 */
    static final Set<EntityType> FORBIDDEN = Set.of(EntityType.CREEPER, EntityType.RAVAGER);

    /** 弓・クロスボウ持ち。同時に多いと集中砲火で即死するため、ウェーブごとに上限を課す。 */
    static final Set<EntityType> RANGED = Set.of(EntityType.SKELETON, EntityType.PILLAGER);
    static final int RANGED_CAP_PER_WAVE = 4;

    /** ティア1つ。倍率はそのティアの全モブへかかる (子ゾンビには speed をかけない)。 */
    public record Tier(int number, double health, double damage, double speed, List<Wave> waves) {

        public int totalMobs() {
            return waves.stream().mapToInt(Wave::total).sum();
        }
    }

    public record Wave(List<MobEntry> mobs) {

        public int total() {
            return mobs.stream().mapToInt(MobEntry::count).sum();
        }
    }

    /**
     * ウェーブに湧くモブ1種。
     *
     * <p>{@code weaponEnchants} のキーはエンチャント名 (minecraft 名前空間)。レジストリの
     * 解決はサーバー起動後にしかできないので、ここでは文字列のまま持つ。
     */
    public record MobEntry(EntityType type, int count, boolean baby,
            Material helmet, Material chestplate, Material weapon,
            Map<String, Integer> weaponEnchants) {
    }

    public Tier tier(int number) {
        Tier tier = tiers.get(number);
        if (tier == null) {
            throw new IllegalArgumentException("ティア " + number + " が設定に無い");
        }
        return tier;
    }

    /** {@code tiers} セクションを読む。不備は起動時に気付きたいので、黙って直さず例外にする。 */
    public static TierTable parse(ConfigurationSection root) {
        ConfigurationSection section = root.getConfigurationSection("tiers");
        if (section == null) {
            throw new IllegalArgumentException("config.yml に tiers が無い");
        }
        Map<Integer, Tier> tiers = new LinkedHashMap<>();
        for (int number = 1; number <= MAX_TIER; number++) {
            ConfigurationSection tierSection = section.getConfigurationSection(String.valueOf(number));
            if (tierSection == null) {
                throw new IllegalArgumentException("tiers." + number + " が無い。1〜" + MAX_TIER
                        + " を全て定義すること");
            }
            tiers.put(number, parseTier(number, tierSection));
        }
        return new TierTable(Map.copyOf(tiers));
    }

    private static Tier parseTier(int number, ConfigurationSection section) {
        ConfigurationSection attributes = section.getConfigurationSection("attributes");
        double health = attributes == null ? 1.0 : attributes.getDouble("health", 1.0);
        double damage = attributes == null ? 1.0 : attributes.getDouble("damage", 1.0);
        double speed = attributes == null ? 1.0 : attributes.getDouble("speed", 1.0);

        List<Wave> waves = new ArrayList<>();
        for (Map<?, ?> waveMap : section.getMapList("waves")) {
            waves.add(parseWave(number, waves.size() + 1, waveMap));
        }
        if (waves.isEmpty()) {
            throw new IllegalArgumentException("tiers." + number + " にウェーブが無い");
        }
        return new Tier(number, health, damage, speed, List.copyOf(waves));
    }

    private static Wave parseWave(int tier, int waveNumber, Map<?, ?> waveMap) {
        Object mobsRaw = waveMap.get("mobs");
        if (!(mobsRaw instanceof List<?> mobsList) || mobsList.isEmpty()) {
            throw new IllegalArgumentException(
                    "tiers." + tier + " のウェーブ " + waveNumber + " に mobs が無い");
        }
        List<MobEntry> mobs = new ArrayList<>();
        int ranged = 0;
        for (Object entryRaw : mobsList) {
            if (!(entryRaw instanceof Map<?, ?> entry)) {
                throw new IllegalArgumentException(
                        "tiers." + tier + " のウェーブ " + waveNumber + " の mobs の形が違う: " + entryRaw);
            }
            MobEntry mob = parseMob(tier, waveNumber, entry);
            if (RANGED.contains(mob.type())) {
                ranged += mob.count();
            }
            mobs.add(mob);
        }
        if (ranged > RANGED_CAP_PER_WAVE) {
            throw new IllegalArgumentException("tiers." + tier + " のウェーブ " + waveNumber
                    + " は遠距離モブが " + ranged + " 体いる。集中砲火は即死事故の元なので "
                    + RANGED_CAP_PER_WAVE + " 体まで");
        }
        return new Wave(List.copyOf(mobs));
    }

    private static MobEntry parseMob(int tier, int waveNumber, Map<?, ?> entry) {
        String typeName = String.valueOf(entry.get("type"));
        EntityType type;
        try {
            type = EntityType.valueOf(typeName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("tiers." + tier + " のウェーブ " + waveNumber
                    + " に知らないモブ: " + typeName);
        }
        if (FORBIDDEN.contains(type)) {
            throw new IllegalArgumentException(type + " はレイドに使えない"
                    + " (一撃事故=ワールド消滅の元)。tiers." + tier + " のウェーブ " + waveNumber);
        }
        int count = intOf(entry.get("count"), 1);
        if (count < 1) {
            throw new IllegalArgumentException("tiers." + tier + " のウェーブ " + waveNumber
                    + " の " + typeName + " の count が 1 未満");
        }
        boolean baby = Boolean.parseBoolean(String.valueOf(entry.get("baby")));

        Map<String, Integer> enchants = new LinkedHashMap<>();
        if (entry.get("weapon-enchants") instanceof Map<?, ?> enchantMap) {
            enchantMap.forEach((name, level) ->
                    enchants.put(String.valueOf(name), intOf(level, 1)));
        }
        return new MobEntry(type, count, baby,
                materialOf(entry.get("helmet"), tier, waveNumber),
                materialOf(entry.get("chestplate"), tier, waveNumber),
                materialOf(entry.get("weapon"), tier, waveNumber),
                Map.copyOf(enchants));
    }

    private static Material materialOf(Object raw, int tier, int waveNumber) {
        if (raw == null) {
            return null;
        }
        Material material = Material.matchMaterial(String.valueOf(raw));
        if (material == null) {
            throw new IllegalArgumentException("tiers." + tier + " のウェーブ " + waveNumber
                    + " に知らないアイテム: " + raw);
        }
        return material;
    }

    static int intOf(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw == null) {
            return fallback;
        }
        return Integer.parseInt(String.valueOf(raw));
    }
}
