package io.github.raidevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 進行度スコアとティア決定。 */
class ProgressionScoreTest {

    /** 実績を持っていない前提のスコア計算機 (既定値設定)。 */
    private final ProgressionScore none =
            new ProgressionScore((player, key) -> false, new YamlConfiguration());

    // ------------------------------------------------------------------ ティア境界

    @Test
    @DisplayName("スコア → 基礎ティアの境界 (10 / 19 / 28)")
    void tierBands() {
        assertEquals(1, none.tierOf(0, false));
        assertEquals(1, none.tierOf(9.9, false));
        assertEquals(2, none.tierOf(10, false));
        assertEquals(3, none.tierOf(19, false));
        assertEquals(4, none.tierOf(28, false));
    }

    @Test
    @DisplayName("昼は上限 T4。T5 は夜 (+1) でしか出ない")
    void dayCapAndNightBump() {
        assertEquals(4, none.tierOf(1000, false), "昼はどれだけ進行しても T4 まで");
        assertEquals(5, none.tierOf(28, true), "夜は同じ進行度で必ず1段上がる");
        assertEquals(5, none.tierOf(1000, true));
        assertEquals(2, none.tierOf(0, true), "序盤でも夜なら T2");
    }

    @Test
    @DisplayName("夜は同じスコアで必ず昼よりティアが高い")
    void nightAlwaysBeatsDay() {
        for (double score = 0; score <= 60; score += 0.5) {
            assertTrue(none.tierOf(score, true) > none.tierOf(score, false),
                    "score=" + score);
        }
    }

    // ------------------------------------------------------------------ スコアの内訳

    @Test
    @DisplayName("素手・裸・実績なしはスコア0 = T1")
    void emptyPlayerIsTierOne() {
        Player player = playerWith(new Material[4], new Material[0]);
        assertEquals(0, none.score(player, 0));
    }

    @Test
    @DisplayName("ダイヤ一式+ダイヤ剣+ネザー系実績で昼 T4 に届く")
    void endgamePlayerReachesTierFour() {
        ProgressionScore score = new ProgressionScore(
                (player, key) -> Set.of(ProgressionScore.ENTER_NETHER,
                        ProgressionScore.OBTAIN_BLAZE_ROD).contains(key),
                new YamlConfiguration());
        Player player = playerWith(
                new Material[] {Material.DIAMOND_BOOTS, Material.DIAMOND_LEGGINGS,
                        Material.DIAMOND_CHESTPLATE, Material.DIAMOND_HELMET},
                new Material[] {Material.DIAMOND_SWORD});
        // 防具 16 + 剣 4 + 実績 8 = 28
        double value = score.score(player, 0);
        assertEquals(28.0, value, 0.001);
        assertEquals(4, score.tierOf(value, false));
        assertEquals(5, score.tierOf(value, true));
    }

    @Test
    @DisplayName("鉄装備の中盤プレイヤーは昼 T2〜T3 の帯に入る")
    void midgamePlayerLandsInMiddle() {
        Player player = playerWith(
                new Material[] {Material.IRON_BOOTS, Material.IRON_LEGGINGS,
                        Material.IRON_CHESTPLATE, Material.IRON_HELMET},
                new Material[] {Material.IRON_SWORD});
        // 防具 12 + 剣 3 = 15
        double value = none.score(player, 0);
        assertEquals(15.0, value, 0.001);
        assertEquals(2, none.tierOf(value, false));
    }

    @Test
    @DisplayName("エンチャントは合計レベル×0.5 で最大5")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void enchantScoreIsCapped() {
        // Enchantment クラスはサーバー無しだと初期化できないので、キーはダミーで渡す。
        // スコア計算はレベル (値) しか見ない
        ItemStack sword = item(Material.DIAMOND_SWORD);
        Map enchants = Map.of(new Object(), 20);
        when(sword.getEnchantments()).thenReturn(enchants);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getArmorContents()).thenReturn(new ItemStack[4]);
        when(inventory.getContents()).thenReturn(new ItemStack[] {sword});
        assertEquals(5.0, none.enchantScore(player), 0.001,
                "レベル20でも上限5で止まるはず");
    }

    @Test
    @DisplayName("パール12個はエンダーアイ相当として +4")
    void pearlsCountAsEyes() {
        ItemStack pearls = item(Material.ENDER_PEARL);
        when(pearls.getAmount()).thenReturn(12);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getArmorContents()).thenReturn(new ItemStack[4]);
        when(inventory.getContents()).thenReturn(new ItemStack[] {pearls});
        assertEquals(4.0, none.advancementScore(player), 0.001);
    }

    @Test
    @DisplayName("経過日数は days-per-point 日ごとに +1、上限あり")
    void daysScore() {
        assertEquals(0, none.daysScore(0));
        assertEquals(1, none.daysScore(5 * 24000L));
        assertEquals(6, none.daysScore(30 * 24000L));
        assertEquals(6, none.daysScore(300 * 24000L), "上限で止まるはず");
    }

    @Test
    @DisplayName("夜の判定はベッドで寝られる時間帯")
    void nightDetection() {
        World world = mock(World.class);
        when(world.getTime()).thenReturn(1000L);
        assertTrue(!ProgressionScore.isNight(world), "昼");
        when(world.getTime()).thenReturn(13000L);
        assertTrue(ProgressionScore.isNight(world), "夜");
        when(world.getTime()).thenReturn(23500L);
        assertTrue(!ProgressionScore.isNight(world), "夜明け前");
    }

    // ------------------------------------------------------------------ 道具

    /** 装着防具と手持ちを指定したプレイヤーのモック。 */
    private static Player playerWith(Material[] armor, Material[] items) {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);

        ItemStack[] armorStacks = new ItemStack[armor.length];
        for (int i = 0; i < armor.length; i++) {
            armorStacks[i] = armor[i] == null ? null : item(armor[i]);
        }
        when(inventory.getArmorContents()).thenReturn(armorStacks);

        ItemStack[] itemStacks = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            itemStacks[i] = item(items[i]);
        }
        when(inventory.getContents()).thenReturn(itemStacks);
        return player;
    }

    private static ItemStack item(Material material) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(1);
        when(stack.getEnchantments()).thenReturn(Map.of());
        return stack;
    }

    /** NamespacedKey がテストから触れることの確認 (レジストリ非依存であること)。 */
    @Test
    @DisplayName("実績キーは minecraft 名前空間")
    void advancementKeys() {
        assertEquals(NamespacedKey.minecraft("story/enter_the_nether"),
                ProgressionScore.ENTER_NETHER);
    }
}
