package io.github.raidevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** クレートに依らない追加報酬。 */
class ExtraDropsTest {

    private final ExtraDrops bundled = ExtraDrops.parse(DefaultConfigTest.loadBundledConfig());

    private static ExtraDrops parse(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return ExtraDrops.parse(config);
    }

    @Test
    @DisplayName("同梱 config.yml にはチケットとスクロール3種が載っている")
    void bundledEntries() {
        assertEquals(List.of(CustomItems.MODIFIER_RESET_TICKET, CustomItems.RECALL_SCROLL,
                        CustomItems.SPATIAL_RECALL_SCROLL, CustomItems.EMERGENCY_RECALL_SCROLL),
                bundled.entries().stream().map(entry -> entry.loot().custom()).toList());
        for (ExtraDrops.Entry entry : bundled.entries()) {
            assertEquals(CustomItems.baseOf(entry.loot().custom()).orElseThrow(), entry.loot().item());
            assertTrue(entry.chance() > 0 && entry.chance() <= 1, entry.loot().custom());
        }
    }

    @Test
    @DisplayName("extra-drops を書かなければ何も出ない")
    void missingSectionIsEmpty() {
        ExtraDrops drops = parse("enabled: true\n");
        assertTrue(drops.entries().isEmpty());
        assertTrue(drops.roll(8, new Random(0)).isEmpty());
    }

    @Test
    @DisplayName("chance 1.0 なら必ず出る。個数は min..max")
    void certainDropAlwaysAppears() {
        ExtraDrops drops = parse("""
                extra-drops:
                  - {item: diamond, min: 2, max: 4, chance: 1.0}
                """);
        Random random = new Random(12345);
        for (int i = 0; i < 50; i++) {
            List<CrateTable.RolledItem> rolled = drops.roll(1, random);
            assertEquals(1, rolled.size());
            int amount = rolled.get(0).amount();
            assertTrue(amount >= 2 && amount <= 4, "個数が範囲外: " + amount);
        }
    }

    @Test
    @DisplayName("min-level 未満のレイドでは判定しない")
    void minLevelGatesTheRoll() {
        ExtraDrops drops = parse("""
                extra-drops:
                  - {item: diamond, chance: 1.0, min-level: 5}
                """);
        Random random = new Random(1);
        for (int level = 1; level <= 4; level++) {
            assertTrue(drops.roll(level, random).isEmpty(), "L" + level + " で出た");
        }
        assertEquals(1, drops.roll(5, random).size());
    }

    @Test
    @DisplayName("エントリごとに独立して振る。出現率はおよそ chance に寄る")
    void entriesAreIndependent() {
        ExtraDrops drops = parse("""
                extra-drops:
                  - {item: diamond, chance: 0.3}
                  - {item: emerald, chance: 0.3}
                """);
        Random random = new Random(20260919L);
        int hits = 0;
        int both = 0;
        int trials = 20000;
        for (int i = 0; i < trials; i++) {
            List<CrateTable.RolledItem> rolled = drops.roll(8, random);
            hits += rolled.size();
            if (rolled.size() == 2) {
                both++;
            }
        }
        double rate = hits / (double) (trials * 2);
        assertTrue(Math.abs(rate - 0.3) < 0.02, "出現率が 0.3 から離れすぎ: " + rate);
        // 独立なら両方当たるのは 0.09 前後。片方だけに寄っていないことを見る
        double bothRate = both / (double) trials;
        assertTrue(Math.abs(bothRate - 0.09) < 0.02, "同時当選率が 0.09 から離れすぎ: " + bothRate);
    }

    @Test
    @DisplayName("設定の不備は起動時に落とす")
    void badConfigThrows() {
        assertThrows(IllegalArgumentException.class, () -> parse("""
                extra-drops:
                  - {item: diamond}
                """), "chance が無い");
        assertThrows(IllegalArgumentException.class, () -> parse("""
                extra-drops:
                  - {item: diamond, chance: 0}
                """), "chance が 0");
        assertThrows(IllegalArgumentException.class, () -> parse("""
                extra-drops:
                  - {item: diamond, chance: 1.5}
                """), "chance が 1 超");
        assertThrows(IllegalArgumentException.class, () -> parse("""
                extra-drops:
                  - {item: not_an_item, chance: 0.5}
                """), "知らないアイテム");
        assertThrows(IllegalArgumentException.class, () -> parse("""
                extra-drops:
                  - {custom: nope, chance: 0.5}
                """), "知らない custom");
        assertThrows(IllegalArgumentException.class, () -> parse("""
                extra-drops:
                  - {item: diamond, chance: 0.5, min-level: 9}
                """), "min-level が上限超え");
        assertThrows(IllegalArgumentException.class, () -> parse("""
                extra-drops:
                  - {item: diamond, min: 4, max: 2, chance: 0.5}
                """), "個数が逆");
    }

    @Test
    @DisplayName("追加報酬は全部当たってもチェストに入る (溢れるのはクレートの側)")
    void extrasAlwaysFit() {
        assertTrue(bundled.entries().size() <= CrateTable.CHEST_SLOTS,
                "追加報酬がチェストの枠より多い: " + bundled.entries().size());

        // 溢れる分はクレートの中身から削られる。どれだけ削られうるかを見えるようにしておく
        CrateTable crates = CrateTable.parse(DefaultConfigTest.loadBundledConfig());
        int worstOverflow = 0;
        for (CrateTable.Crate crate : crates.crates().values()) {
            for (var level : crate.levels().values()) {
                worstOverflow = Math.max(worstOverflow,
                        level.slotsMax() + bundled.entries().size() - CrateTable.CHEST_SLOTS);
            }
        }
        assertTrue(worstOverflow <= bundled.entries().size(),
                "クレートの枠が広すぎて、追加報酬の数を超える数が削られる: " + worstOverflow);
    }
}
