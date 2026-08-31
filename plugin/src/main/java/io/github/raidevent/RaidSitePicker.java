package io.github.raidevent;

import java.util.Random;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/** レイド発生地点 p の選定。 */
final class RaidSitePicker {

    private RaidSitePicker() {
    }

    /**
     * 中心プレイヤーから min..max ブロックの環状帯で、地表の安全な場所を探す。
     *
     * <p>{@code getHighestBlockYAt} は未生成チャンクを同期生成するので、呼ぶのは
     * レイド生成時の数十回だけに留める。液体の上と奈落は避ける。見つからなければ null。
     */
    static Location pick(Location center, int minDistance, int maxDistance, Random random) {
        World world = center.getWorld();
        for (int attempt = 0; attempt < 30; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = minDistance + random.nextDouble() * (maxDistance - minDistance);
            int x = (int) Math.round(center.getX() + Math.cos(angle) * distance);
            int z = (int) Math.round(center.getZ() + Math.sin(angle) * distance);

            int y = world.getHighestBlockYAt(x, z);
            if (y <= world.getMinHeight() + 1) {
                continue;
            }
            Block ground = world.getBlockAt(x, y, z);
            if (ground.isLiquid() || !ground.getType().isSolid()) {
                continue;
            }
            return new Location(world, x + 0.5, y + 1, z + 0.5);
        }
        return null;
    }

    /**
     * 地点 p の周囲 (min..max ブロック) にモブの足場を選ぶ。
     *
     * <p>近すぎるとトリガーした瞬間に囲まれるので 8 ブロックは離す。地形が悪ければ
     * 諦めて p の真上を返す (スポーンできないよりまし)。
     */
    static Location spread(Location site, Random random, double min, double max) {
        World world = site.getWorld();
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = min + random.nextDouble() * (max - min);
            int x = (int) Math.round(site.getX() + Math.cos(angle) * distance);
            int z = (int) Math.round(site.getZ() + Math.sin(angle) * distance);
            int y = world.getHighestBlockYAt(x, z);
            Block ground = world.getBlockAt(x, y, z);
            if (ground.isLiquid()) {
                continue;
            }
            return new Location(world, x + 0.5, y + 1, z + 0.5);
        }
        return site.clone();
    }
}
