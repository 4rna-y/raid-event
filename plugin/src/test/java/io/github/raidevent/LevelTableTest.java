package io.github.raidevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 同梱 config.yml のモブ表が設計どおりで、危険な構成を拒否できることを見る。 */
class LevelTableTest {

    private final LevelTable table = LevelTable.parse(DefaultConfigTest.loadBundledConfig());

    @Test
    @DisplayName("8 レベルすべて定義されている")
    void hasFiveLevels() {
        assertEquals(8, table.levels().size());
    }

    @Test
    @DisplayName("ウェーブ数は 2/3/3/4/5/5/6/6")
    void waveCounts() {
        assertEquals(List.of(2, 3, 3, 4, 5, 5, 6, 6), List.of(
                table.level(1).waves().size(),
                table.level(2).waves().size(),
                table.level(3).waves().size(),
                table.level(4).waves().size(),
                table.level(5).waves().size(),
                table.level(6).waves().size(),
                table.level(7).waves().size(),
                table.level(8).waves().size()));
    }

    @Test
    @DisplayName("総数は 8/14/18/25/32/38/46/54 (参加者2人基準)")
    void totalMobs() {
        assertEquals(List.of(8, 14, 18, 25, 32, 38, 46, 54), List.of(
                table.level(1).totalMobs(),
                table.level(2).totalMobs(),
                table.level(3).totalMobs(),
                table.level(4).totalMobs(),
                table.level(5).totalMobs(),
                table.level(6).totalMobs(),
                table.level(7).totalMobs(),
                table.level(8).totalMobs()));
    }

    @Test
    @DisplayName("エヴォーカーは L5 以上の最終ウェーブだけ (L5/L6 は 1 体、L7/L8 は 2 体)")
    void evokerOnlyInFinalWave() {
        for (int level = 1; level <= 4; level++) {
            for (LevelTable.Wave wave : table.level(level).waves()) {
                assertTrue(wave.mobs().stream().noneMatch(m -> m.type() == EntityType.EVOKER),
                        "T" + level + " にエヴォーカーが居る。脅威はボス戦だけに集約する");
            }
        }
        for (int level = 5; level <= 8; level++) {
            List<LevelTable.Wave> waves = table.level(level).waves();
            for (int i = 0; i < waves.size() - 1; i++) {
                assertTrue(waves.get(i).mobs().stream().noneMatch(m -> m.type() == EntityType.EVOKER), "L" + level);
            }
            int evokers = waves.get(waves.size() - 1).mobs().stream()
                    .filter(m -> m.type() == EntityType.EVOKER)
                    .mapToInt(LevelTable.MobEntry::count).sum();
            assertEquals(level <= 6 ? 1 : 2, evokers, "L" + level);
        }
    }

    @Test
    @DisplayName("強化倍率はレベルで単調に上がる")
    void attributesAreMonotonic() {
        for (int level = 2; level <= 8; level++) {
            assertTrue(table.level(level).health() >= table.level(level - 1).health());
            assertTrue(table.level(level).damage() >= table.level(level - 1).damage());
            assertTrue(table.level(level).speed() >= table.level(level - 1).speed());
        }
        assertEquals(1.75, table.level(5).health());
        assertEquals(1.25, table.level(5).damage());
        assertEquals(2.5, table.level(8).health());
        assertEquals(1.4, table.level(8).damage(), "攻撃は +40% で止める (一撃事故を作らない)");
        assertTrue(table.levels().values().stream().allMatch(level -> level.damage() <= 1.4));
    }

    @Test
    @DisplayName("クリーパーは拒否される (一撃事故=ワールド消滅の元)")
    void rejectsCreeper() {
        YamlConfiguration config = configWithMob("creeper");
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> LevelTable.parse(config));
        assertTrue(e.getMessage().contains("CREEPER"), e.getMessage());
    }

    @Test
    @DisplayName("遠距離モブ5体以上のウェーブは拒否される (集中砲火の即死防止)")
    void rejectsTooManyRanged() {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString("""
                    levels:
                      "1":
                        waves:
                          - mobs:
                              - {type: skeleton, count: 3}
                              - {type: pillager, count: 2}
                    """);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        // 1レベルしか無いことより先に、まずウェーブの中身で落ちてほしい
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> LevelTable.parse(config));
        assertTrue(e.getMessage().contains("遠距離"), e.getMessage());
    }

    private static YamlConfiguration configWithMob(String type) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString("""
                    levels:
                      "1":
                        waves:
                          - mobs:
                              - {type: %s, count: 1}
                    """.formatted(type));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return config;
    }

    @Test
    @DisplayName("エンド後に足した禁止モブ (シュルカー・ガスト・ホグリン・ブリーズ・ウォーデン) も拒否される")
    void rejectsNewForbiddenMobs() {
        for (String mob : List.of("shulker", "ghast", "hoglin", "zoglin", "breeze", "warden", "wither")) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> LevelTable.parse(levelYaml("{type: " + mob + ", count: 1}")), mob);
        }
    }

    @Test
    @DisplayName("ブレイズ・ボグド・ストレイ・イリュージョナーは遠距離枠に数える (5 体で拒否)")
    void newRangedMobsCountTowardCap() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> LevelTable.parse(levelYaml("{type: blaze, count: 3}, {type: bogged, count: 2}")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> LevelTable.parse(levelYaml("{type: illusioner, count: 1}, {type: stray, count: 4}")));
        LevelTable ok = LevelTable.parse(levelYaml("{type: blaze, count: 2}, {type: illusioner, count: 1}, {type: zombie, count: 5}"));
        assertEquals(8, ok.level(1).totalMobs());
    }

    /** 全レベルに同じ 1 ウェーブを持つ最小の levels。 */
    private static org.bukkit.configuration.file.YamlConfiguration levelYaml(String mobs) {
        StringBuilder sb = new StringBuilder("levels:\n");
        for (int level = 1; level <= LevelTable.MAX_LEVEL; level++) {
            sb.append("  \"").append(level).append("\":\n    waves:\n      - mobs: [").append(mobs).append("]\n");
        }
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
        try {
            config.loadFromString(sb.toString());
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return config;
    }
}
