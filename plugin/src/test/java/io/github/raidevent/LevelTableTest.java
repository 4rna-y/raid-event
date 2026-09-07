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
    @DisplayName("5レベルすべて定義されている")
    void hasFiveLevels() {
        assertEquals(5, table.levels().size());
    }

    @Test
    @DisplayName("ウェーブ数は 2/3/3/4/5")
    void waveCounts() {
        assertEquals(List.of(2, 3, 3, 4, 5), List.of(
                table.level(1).waves().size(),
                table.level(2).waves().size(),
                table.level(3).waves().size(),
                table.level(4).waves().size(),
                table.level(5).waves().size()));
    }

    @Test
    @DisplayName("総数は 8/14/18/25/32 (参加者2人基準)")
    void totalMobs() {
        assertEquals(List.of(8, 14, 18, 25, 32), List.of(
                table.level(1).totalMobs(),
                table.level(2).totalMobs(),
                table.level(3).totalMobs(),
                table.level(4).totalMobs(),
                table.level(5).totalMobs()));
    }

    @Test
    @DisplayName("エヴォーカーは L5 の最終ウェーブに1体だけ")
    void evokerOnlyInFinalWave() {
        for (int level = 1; level <= 4; level++) {
            for (LevelTable.Wave wave : table.level(level).waves()) {
                assertTrue(wave.mobs().stream().noneMatch(m -> m.type() == EntityType.EVOKER),
                        "T" + level + " にエヴォーカーが居る。脅威は L5 のボス戦だけに集約する");
            }
        }
        List<LevelTable.Wave> waves = table.level(5).waves();
        for (int i = 0; i < waves.size() - 1; i++) {
            assertTrue(waves.get(i).mobs().stream().noneMatch(m -> m.type() == EntityType.EVOKER));
        }
        int evokers = waves.get(waves.size() - 1).mobs().stream()
                .filter(m -> m.type() == EntityType.EVOKER)
                .mapToInt(LevelTable.MobEntry::count).sum();
        assertEquals(1, evokers);
    }

    @Test
    @DisplayName("強化倍率はレベルで単調に上がる")
    void attributesAreMonotonic() {
        for (int level = 2; level <= 5; level++) {
            assertTrue(table.level(level).health() >= table.level(level - 1).health());
            assertTrue(table.level(level).damage() >= table.level(level - 1).damage());
            assertTrue(table.level(level).speed() >= table.level(level - 1).speed());
        }
        assertEquals(1.75, table.level(5).health());
        assertEquals(1.25, table.level(5).damage());
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
}
