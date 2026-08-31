package io.github.raidevent;

import java.util.Collection;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * ゲーム進行度スコアとティア決定。
 *
 * <p>この企画のゴールはエンドラ討伐で、エンドは突入=不可帰還点。だからスコアは
 * 「エンド突入前に稼げるもの」だけで構成する (装備・ネザー系の実績・経過日数)。
 * エンド到達や討伐をスコアに入れても、加算された頃にはレイドに参加する局面が無い。
 *
 * <p>ワールドは wiah でいつでも消えるので、進行度は毎回その場で計算する。永続化しない。
 */
public final class ProgressionScore {

    /** 実績の確認。テストではサーバーが無いので、ここだけ差し替えられるようにする。 */
    public interface AdvancementChecker {
        boolean isDone(Player player, NamespacedKey advancement);
    }

    public static final NamespacedKey ENTER_NETHER =
            NamespacedKey.minecraft("story/enter_the_nether");
    public static final NamespacedKey OBTAIN_BLAZE_ROD =
            NamespacedKey.minecraft("nether/obtain_blaze_rod");
    public static final NamespacedKey EYE_SPY =
            NamespacedKey.minecraft("story/follow_ender_eye");

    /** エンダーアイ相当と数えるパールの数。 */
    static final int PEARLS_FOR_EYES = 12;

    public static final List<Integer> DEFAULT_TIER_THRESHOLDS = List.of(10, 19, 28);
    public static final int DEFAULT_DAYS_PER_POINT = 5;
    public static final int DEFAULT_DAYS_MAX_POINTS = 6;

    private final AdvancementChecker advancements;
    private final List<Integer> thresholds;
    private final int daysPerPoint;
    private final int daysMaxPoints;

    public ProgressionScore(AdvancementChecker advancements, ConfigurationSection config) {
        this.advancements = advancements;
        List<Integer> configured = config.getIntegerList("score.tier-thresholds");
        this.thresholds = configured.size() == TierTable.DAY_MAX_TIER - 1
                ? List.copyOf(configured) : DEFAULT_TIER_THRESHOLDS;
        this.daysPerPoint = Math.max(1,
                config.getInt("score.days-per-point", DEFAULT_DAYS_PER_POINT));
        this.daysMaxPoints = config.getInt("score.days-max-points", DEFAULT_DAYS_MAX_POINTS);
    }

    /** プレイヤー1人ぶんのスコア。 */
    public double score(Player player, long worldFullTime) {
        return armorScore(player) + weaponScore(player) + enchantScore(player)
                + advancementScore(player) + daysScore(worldFullTime);
    }

    /** 参加者の平均スコアから基礎ティアを出し、夜なら +1 する。 */
    public int tierFor(Collection<Player> players, World world, boolean night) {
        double average = players.stream()
                .mapToDouble(player -> score(player, world.getFullTime()))
                .average().orElse(0);
        return tierOf(average, night);
    }

    /** スコア → ティア。昼は上限 {@value TierTable#DAY_MAX_TIER}、夜は +1。 */
    public int tierOf(double averageScore, boolean night) {
        int base = 1;
        for (int threshold : thresholds) {
            if (averageScore >= threshold) {
                base++;
            }
        }
        base = Math.min(base, TierTable.DAY_MAX_TIER);
        return night ? Math.min(base + 1, TierTable.MAX_TIER) : base;
    }

    /** 夜かどうか。ベッドで寝られる時間帯を夜と数える。 */
    public static boolean isNight(World world) {
        long time = world.getTime();
        return time >= 12542 && time < 23460;
    }

    // ------------------------------------------------------------------ 内訳

    /** 装着防具: 革・金=1、チェーン=2、鉄=3、ダイヤ=4、ネザライト=5 (部位ごと)。 */
    double armorScore(Player player) {
        double score = 0;
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            if (piece != null) {
                score += materialValue(piece.getType());
            }
        }
        return score;
    }

    /** 手持ちの最良の剣・斧: 木・金=1、石=2、鉄=3、ダイヤ=4、ネザライト=5。 */
    double weaponScore(Player player) {
        double best = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null) {
                continue;
            }
            String name = item.getType().name();
            if (name.endsWith("_SWORD") || name.endsWith("_AXE")) {
                best = Math.max(best, materialValue(item.getType()));
            }
        }
        return best;
    }

    /** 防具と武器のエンチャントレベル合計 ×0.5 (上限5)。 */
    double enchantScore(Player player) {
        int levels = 0;
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            levels += enchantLevels(piece);
        }
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null) {
                continue;
            }
            String name = item.getType().name();
            if (name.endsWith("_SWORD") || name.endsWith("_AXE") || name.endsWith("BOW")) {
                levels += enchantLevels(item);
            }
        }
        return Math.min(levels * 0.5, 5.0);
    }

    /**
     * 実績: ネザー到達 +4、ブレイズロッド +4、要塞発見 +3。
     * エンダーアイ作成に実績は無いので、アイ1個かパール12個の保有で +4 と数える。
     */
    double advancementScore(Player player) {
        double score = 0;
        if (advancements.isDone(player, ENTER_NETHER)) {
            score += 4;
        }
        if (advancements.isDone(player, OBTAIN_BLAZE_ROD)) {
            score += 4;
        }
        if (advancements.isDone(player, EYE_SPY)) {
            score += 3;
        }
        if (hasEyeMaterials(player)) {
            score += 4;
        }
        return score;
    }

    double daysScore(long worldFullTime) {
        long days = worldFullTime / 24000L;
        return Math.min(days / daysPerPoint, daysMaxPoints);
    }

    private boolean hasEyeMaterials(Player player) {
        int pearls = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null) {
                continue;
            }
            if (item.getType() == Material.ENDER_EYE) {
                return true;
            }
            if (item.getType() == Material.ENDER_PEARL) {
                pearls += item.getAmount();
            }
        }
        return pearls >= PEARLS_FOR_EYES;
    }

    /**
     * エンチャントレベルの合計。どのエンチャントかは見ない (レベルだけで十分だし、
     * Enchantment クラスはサーバー無しだと初期化できない)。
     */
    private static int enchantLevels(ItemStack item) {
        if (item == null) {
            return 0;
        }
        int levels = 0;
        for (int level : item.getEnchantments().values()) {
            levels += level;
        }
        return levels;
    }

    static double materialValue(Material material) {
        String name = material.name();
        if (name.startsWith("NETHERITE_")) {
            return 5;
        }
        if (name.startsWith("DIAMOND_")) {
            return 4;
        }
        if (name.startsWith("IRON_")) {
            return 3;
        }
        if (name.startsWith("CHAINMAIL_") || name.startsWith("STONE_")) {
            return 2;
        }
        if (name.startsWith("LEATHER_") || name.startsWith("GOLDEN_")
                || name.startsWith("WOODEN_")) {
            return 1;
        }
        return 0;
    }
}
