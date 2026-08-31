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
class TierTableTest {

    private final TierTable table = TierTable.parse(DefaultConfigTest.loadBundledConfig());

    @Test
    @DisplayName("5ティアすべて定義されている")
    void hasFiveTiers() {
        assertEquals(5, table.tiers().size());
    }

    @Test
    @DisplayName("ウェーブ数は 2/3/3/4/5")
    void waveCounts() {
        assertEquals(List.of(2, 3, 3, 4, 5), List.of(
                table.tier(1).waves().size(),
                table.tier(2).waves().size(),
                table.tier(3).waves().size(),
                table.tier(4).waves().size(),
                table.tier(5).waves().size()));
    }

    @Test
    @DisplayName("総数は 8/14/18/25/32 (参加者2人基準)")
    void totalMobs() {
        assertEquals(List.of(8, 14, 18, 25, 32), List.of(
                table.tier(1).totalMobs(),
                table.tier(2).totalMobs(),
                table.tier(3).totalMobs(),
                table.tier(4).totalMobs(),
                table.tier(5).totalMobs()));
    }

    @Test
    @DisplayName("エヴォーカーは T5 の最終ウェーブに1体だけ")
    void evokerOnlyInFinalWave() {
        for (int tier = 1; tier <= 4; tier++) {
            for (TierTable.Wave wave : table.tier(tier).waves()) {
                assertTrue(wave.mobs().stream().noneMatch(m -> m.type() == EntityType.EVOKER),
                        "T" + tier + " にエヴォーカーが居る。脅威は T5 のボス戦だけに集約する");
            }
        }
        List<TierTable.Wave> waves = table.tier(5).waves();
        for (int i = 0; i < waves.size() - 1; i++) {
            assertTrue(waves.get(i).mobs().stream().noneMatch(m -> m.type() == EntityType.EVOKER));
        }
        int evokers = waves.get(waves.size() - 1).mobs().stream()
                .filter(m -> m.type() == EntityType.EVOKER)
                .mapToInt(TierTable.MobEntry::count).sum();
        assertEquals(1, evokers);
    }

    @Test
    @DisplayName("強化倍率はティアで単調に上がる")
    void attributesAreMonotonic() {
        for (int tier = 2; tier <= 5; tier++) {
            assertTrue(table.tier(tier).health() >= table.tier(tier - 1).health());
            assertTrue(table.tier(tier).damage() >= table.tier(tier - 1).damage());
            assertTrue(table.tier(tier).speed() >= table.tier(tier - 1).speed());
        }
        assertEquals(1.75, table.tier(5).health());
        assertEquals(1.25, table.tier(5).damage());
    }

    @Test
    @DisplayName("クリーパーは拒否される (一撃事故=ワールド消滅の元)")
    void rejectsCreeper() {
        YamlConfiguration config = configWithMob("creeper");
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> TierTable.parse(config));
        assertTrue(e.getMessage().contains("CREEPER"), e.getMessage());
    }

    @Test
    @DisplayName("遠距離モブ5体以上のウェーブは拒否される (集中砲火の即死防止)")
    void rejectsTooManyRanged() {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString("""
                    tiers:
                      "1":
                        waves:
                          - mobs:
                              - {type: skeleton, count: 3}
                              - {type: pillager, count: 2}
                    """);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        // 1ティアしか無いことより先に、まずウェーブの中身で落ちてほしい
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> TierTable.parse(config));
        assertTrue(e.getMessage().contains("遠距離"), e.getMessage());
    }

    private static YamlConfiguration configWithMob(String type) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString("""
                    tiers:
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
