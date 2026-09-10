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
    @DisplayName("各クレートに 8 レベルあり、pool は空でない")
    void fiveLevelsEach() {
        for (CrateTable.Crate crate : table.crates().values()) {
            assertEquals(8, crate.levels().size(), crate.id());
            for (var level : crate.levels().values()) {
                assertFalse(level.pool().isEmpty());
            }
        }
    }

    @Test
    @DisplayName("L4 以上には目玉確定枠 (featured) がある")
    void featuredOnHighLevels() {
        for (CrateTable.Crate crate : table.crates().values()) {
            for (int number = 1; number <= 3; number++) {
                assertTrue(crate.level(number).featured().isEmpty(),
                        crate.id() + " T" + number + " に featured は要らない");
            }
            for (int number = 4; number <= 8; number++) {
                assertFalse(crate.level(number).featured().isEmpty(),
                        crate.id() + " T" + number + " には目玉確定枠が要る");
            }
        }
    }

    @Test
    @DisplayName("充填スロット数はレベルで単調に増える")
    void slotsGrowWithLevel() {
        for (CrateTable.Crate crate : table.crates().values()) {
            for (int number = 2; number <= 8; number++) {
                assertTrue(crate.level(number).slotsMin() >= crate.level(number - 1).slotsMin());
                assertTrue(crate.level(number).slotsMax() >= crate.level(number - 1).slotsMax());
            }
        }
    }

    @Test
    @DisplayName("修繕はマジカル L5 の目玉枠に入っている")
    void mendingIsMagicalT5Featured() {
        List<CrateTable.LootEntry> featured = table.crate("magical").level(5).featured();
        assertTrue(featured.stream().anyMatch(e -> e.enchants().containsKey("mending")),
                "修繕が目玉枠に無い");
    }

    @Test
    @DisplayName("抽選: スロット数は範囲内で、目玉枠から必ず1つ出る")
    void rollStaysInBounds() {
        Random random = new Random(42);
        for (CrateTable.Crate crate : table.crates().values()) {
            for (int number = 1; number <= 5; number++) {
                CrateTable.CrateLevel level = crate.level(number);
                for (int i = 0; i < 200; i++) {
                    List<CrateTable.RolledItem> items = level.roll(random);
                    assertTrue(items.size() >= level.slotsMin(), crate.id() + " T" + number);
                    assertTrue(items.size() <= level.slotsMax(), crate.id() + " T" + number);
                    assertTrue(items.size() <= CrateTable.CHEST_SLOTS);
                    if (!level.featured().isEmpty()) {
                        assertTrue(level.featured().contains(items.get(0).entry()),
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

    @Test
    @DisplayName("特別なアイテムは L7/L8 の目玉枠にだけあり、土台が解決されている")
    void customItemsOnlyInTopFeatured() {
        java.util.Set<String> found = new java.util.HashSet<>();
        for (CrateTable.Crate crate : table.crates().values()) {
            for (int number = 1; number <= 8; number++) {
                for (CrateTable.LootEntry entry : crate.level(number).pool()) {
                    assertTrue(entry.custom() == null, crate.id() + " L" + number + " の pool に custom がある");
                }
                for (CrateTable.LootEntry entry : crate.level(number).featured()) {
                    if (entry.custom() != null) {
                        assertTrue(number >= 7, crate.id() + " L" + number + " に custom");
                        assertEquals(CustomItems.baseOf(entry.custom()).orElseThrow(), entry.item());
                        found.add(entry.custom());
                    }
                }
            }
        }
        assertEquals(java.util.Set.of(CustomItems.DIMENSIONAL_CHEST, CustomItems.SNIPER_RIFLE, CustomItems.MODIFIER_REVIVAL), found);
    }

    @Test
    @DisplayName("エリトラ・ネザースター・メイス・パール・ブレイズロッド・トーテムは出さない")
    void neverDropsStoryItems() {
        java.util.Set<org.bukkit.Material> banned = java.util.Set.of(org.bukkit.Material.ELYTRA, org.bukkit.Material.NETHER_STAR,
                org.bukkit.Material.MACE, org.bukkit.Material.ENDER_PEARL, org.bukkit.Material.BLAZE_ROD, org.bukkit.Material.TOTEM_OF_UNDYING);
        for (CrateTable.Crate crate : table.crates().values()) {
            for (var level : crate.levels().values()) {
                for (CrateTable.LootEntry entry : level.pool()) {
                    assertFalse(banned.contains(entry.item()), crate.id() + " に " + entry.item());
                }
                for (CrateTable.LootEntry entry : level.featured()) {
                    assertFalse(banned.contains(entry.item()), crate.id() + " に " + entry.item());
                }
            }
        }
    }
}
