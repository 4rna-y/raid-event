package io.github.raidevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 他プラグインと揃えなければならないアイテムの「契約」。
 *
 * <p>実物の組み立てはサーバーが要るので確かめられない。代わりに双方で一致していなければならない
 * もの (土台・印のキー・id・説明に焼き込む数字) をここに固定する。相手側にも同じ値を固定した
 * 試験があるので、どちらかを動かせば片方が落ちる。
 *
 * <ul>
 *   <li>異次元チェスト・スナイパーライフル・爆裂弓 → relics の {@code RelicItems}</li>
 *   <li>復活剤・リセットチケット → Modifier の {@code RevivalItem} / {@code ResetTicket}</li>
 *   <li>リコールスクロール 3 種 → manchor の {@code ManchorItems}</li>
 * </ul>
 */
class CrossPluginItemsTest {

    @Test
    @DisplayName("印のキーは持ち主の名前空間と同じ")
    void stampKeys() {
        assertEquals("relics:item", CustomItems.RELICS_ITEM.toString());
        assertEquals("relics:shots", CustomItems.RELICS_SHOTS.toString());
        assertEquals("modifier:item", CustomItems.MODIFIER_ITEM.toString());
        assertEquals("manchor:item", CustomItems.MANCHOR_ITEM.toString());
    }

    @Test
    @DisplayName("id と土台は持ち主の写しと同じ")
    void basesMatchOwners() {
        Map<String, Material> expected = Map.of(
                CustomItems.DIMENSIONAL_CHEST, Material.ENDER_CHEST,
                CustomItems.SNIPER_RIFLE, Material.SPYGLASS,
                CustomItems.EXPLOSIVE_BOW, Material.BOW,
                CustomItems.MODIFIER_REVIVAL, Material.GOLDEN_APPLE,
                CustomItems.MODIFIER_RESET_TICKET, Material.PAPER,
                CustomItems.RECALL_SCROLL, Material.PAPER,
                CustomItems.SPATIAL_RECALL_SCROLL, Material.PAPER,
                CustomItems.EMERGENCY_RECALL_SCROLL, Material.PAPER);

        assertEquals(expected.keySet(), Set.copyOf(CustomItems.IDS));
        assertEquals(CustomItems.IDS.size(), Set.copyOf(CustomItems.IDS).size(), "IDS に重複がある");
        expected.forEach((id, base) ->
                assertEquals(base, CustomItems.baseOf(id).orElseThrow(), id + " の土台"));
        assertTrue(CustomItems.baseOf("nope").isEmpty());
    }

    @Test
    @DisplayName("説明に焼き込む数字は、持ち主の既定の設定と同じ")
    void numbersMatchOwnerDefaults() {
        // relics の sniper.shots
        assertEquals(200, CustomItems.SNIPER_SHOTS);
        // manchor の recall.charge-seconds / recall.spatial-radius
        assertEquals(3f, CustomItems.SCROLL_CHARGE_SECONDS);
        assertEquals(5, CustomItems.SPATIAL_RADIUS);
        assertTrue(CustomItems.SPATIAL_HOLD_SECONDS > CustomItems.SCROLL_CHARGE_SECONDS,
                "空間リコールは離した瞬間が発動なので、チャージが完走してはいけない");
        // Modifier の ResetTicket.CHARGE_SECONDS
        assertEquals(3f, CustomItems.TICKET_CHARGE_SECONDS);
    }
}
