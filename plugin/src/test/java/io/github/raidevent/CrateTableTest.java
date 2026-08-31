package io.github.raidevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 同梱 config.yml のロットテーブルが設計どおりであることを見る。 */
class CrateTableTest {

    private final CrateTable table = CrateTable.parse(DefaultConfigTest.loadBundledConfig());

    @Test
    @DisplayName("クレートは miner / magical / soldier の3種")
    void threeCrates() {
        assertEquals(Set.of("miner", "magical", "soldier"), table.crates().keySet());
    }

    @Test
    @DisplayName("各クレートに5ティアあり、pool は空でない")
    void fiveTiersEach() {
        for (CrateTable.Crate crate : table.crates().values()) {
            assertEquals(5, crate.tiers().size(), crate.id());
            for (var tier : crate.tiers().values()) {
                assertFalse(tier.pool().isEmpty());
            }
        }
    }

    @Test
    @DisplayName("T4 と T5 には目玉確定枠 (featured) がある")
    void featuredOnHighTiers() {
        for (CrateTable.Crate crate : table.crates().values()) {
            for (int number = 1; number <= 3; number++) {
                assertTrue(crate.tier(number).featured().isEmpty(),
                        crate.id() + " T" + number + " に featured は要らない");
            }
            for (int number = 4; number <= 5; number++) {
                assertFalse(crate.tier(number).featured().isEmpty(),
                        crate.id() + " T" + number + " には目玉確定枠が要る");
            }
        }
    }

    @Test
    @DisplayName("充填スロット数はティアで単調に増える")
    void slotsGrowWithTier() {
        for (CrateTable.Crate crate : table.crates().values()) {
            for (int number = 2; number <= 5; number++) {
                assertTrue(crate.tier(number).slotsMin() >= crate.tier(number - 1).slotsMin());
                assertTrue(crate.tier(number).slotsMax() >= crate.tier(number - 1).slotsMax());
            }
        }
    }

    @Test
    @DisplayName("修繕はマジカル T5 の目玉枠に入っている")
    void mendingIsMagicalT5Featured() {
        List<CrateTable.LootEntry> featured = table.crate("magical").tier(5).featured();
        assertTrue(featured.stream().anyMatch(e -> e.enchants().containsKey("mending")),
                "修繕が目玉枠に無い");
    }

    @Test
    @DisplayName("抽選: スロット数は範囲内で、目玉枠から必ず1つ出る")
    void rollStaysInBounds() {
        Random random = new Random(42);
        for (CrateTable.Crate crate : table.crates().values()) {
            for (int number = 1; number <= 5; number++) {
                CrateTable.CrateTier tier = crate.tier(number);
                for (int i = 0; i < 200; i++) {
                    List<CrateTable.RolledItem> items = tier.roll(random);
                    assertTrue(items.size() >= tier.slotsMin(), crate.id() + " T" + number);
                    assertTrue(items.size() <= tier.slotsMax(), crate.id() + " T" + number);
                    assertTrue(items.size() <= CrateTable.CHEST_SLOTS);
                    if (!tier.featured().isEmpty()) {
                        assertTrue(tier.featured().contains(items.get(0).entry()),
                                "先頭は目玉枠から出るはず");
                    }
                    for (CrateTable.RolledItem item : items) {
                        assertTrue(item.amount() >= item.entry().min());
                        assertTrue(item.amount() <= item.entry().max());
                    }
                }
            }
        }
    }
}
