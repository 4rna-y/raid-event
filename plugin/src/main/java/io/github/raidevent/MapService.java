package io.github.raidevent;

import java.util.Collection;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * レイド地点を記した地図の配布。
 *
 * <p>死=ワールド消滅のサーバーでは「リスクを承知で挑むか」の判断材料を必ず与えるべき
 * なので、地図の名前と説明にクレート種・ティア・座標・昼夜を全部書く。
 */
final class MapService {

    private MapService() {
    }

    /** 全員に1枚ずつ配る。インベントリが満杯なら足元に落とす。 */
    static void give(Collection<? extends Player> players, Raid raid, String crateName) {
        for (Player player : players) {
            ItemStack map = build(raid, crateName);
            player.getInventory().addItem(map).values()
                    .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
        }
    }

    static ItemStack build(Raid raid, String crateName) {
        Location site = raid.site;
        MapView view = Bukkit.createMap(site.getWorld());
        view.setScale(MapView.Scale.NORMAL);
        view.setCenterX(site.getBlockX());
        view.setCenterZ(site.getBlockZ());
        view.addRenderer(new MarkerRenderer());

        ItemStack stack = new ItemStack(Material.FILLED_MAP);
        if (stack.getItemMeta() instanceof MapMeta meta) {
            meta.setMapView(view);
            meta.displayName(Component.text("レイドの地図: ", NamedTextColor.GOLD)
                    .append(Component.text(crateName + " (Tier " + raid.tier + ")",
                            NamedTextColor.YELLOW))
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("地点: (" + site.getBlockX() + ", " + site.getBlockY()
                            + ", " + site.getBlockZ() + ")", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text(raid.night ? "夜のレイド (高ティア)" : "昼のレイド",
                            raid.night ? NamedTextColor.DARK_PURPLE : NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("有効期限: マインクラフト時間で半日", NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** 地図の中心 (=地点 p) に赤い×印を描く。 */
    private static final class MarkerRenderer extends MapRenderer {

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            // MapCanvas は java.awt.Color を取る
            java.awt.Color red = java.awt.Color.RED;
            for (int d = -4; d <= 4; d++) {
                canvas.setPixelColor(64 + d, 64 + d, red);
                canvas.setPixelColor(64 + d, 64 - d, red);
            }
        }
    }
}
