package io.github.raidevent;

import java.util.Collection;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * レイド地点を記した地図の配布。
 *
 * <p>死=ワールド消滅のサーバーでは「リスクを承知で挑むか」の判断材料を必ず与えるべき
 * なので、地図の名前と説明にクレート種・レベル・座標・昼夜を全部書く。
 *
 * <p>用済みになった地図 (発火済み・失効・キャンセル) は {@link #revoke} で回収する。
 * 死んだ地点を指す地図が手元に残ると、次のレイドの地図と紛らわしい。回収のために
 * レイドの id を PDC に焼き込んである。
 */
final class MapService {

    /** 地図に焼き込むレイド id の PDC キー。 */
    private static final String ID_KEY = "raid-id";

    private MapService() {
    }

    /** 全員に1枚ずつ配る。インベントリが満杯なら足元に落とす。 */
    static void give(Plugin plugin, Collection<? extends Player> players, Raid raid,
            String crateName) {
        for (Player player : players) {
            ItemStack map = build(plugin, raid, crateName);
            player.getInventory().addItem(map).values()
                    .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
        }
    }

    static ItemStack build(Plugin plugin, Raid raid, String crateName) {
        Location site = raid.site;
        MapView view = Bukkit.createMap(site.getWorld());
        view.setScale(MapView.Scale.NORMAL);
        view.setCenterX(site.getBlockX());
        view.setCenterZ(site.getBlockZ());
        // Bukkit.createMap() は位置追跡を切った状態の地図を作るので、明示的に入れないと
        // 自分のインジケータが一切出ない。地図を受け取る時点でプレイヤーは地点から
        // 200〜600m 離れていて、320m を超えると unlimited-tracking が無い限り
        // インジケータごと消される。どちらも要る
        view.setTrackingPosition(true);
        view.setUnlimitedTracking(true);
        view.addRenderer(new MarkerRenderer());

        ItemStack stack = new ItemStack(Material.FILLED_MAP);
        if (stack.getItemMeta() instanceof MapMeta meta) {
            meta.setMapView(view);
            meta.displayName(Component.text("レイドの地図: ", NamedTextColor.GOLD)
                    .append(Component.text(crateName + " (Level " + raid.level + ")",
                            NamedTextColor.YELLOW))
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("地点: (" + site.getBlockX() + ", " + site.getBlockY()
                            + ", " + site.getBlockZ() + ")", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text(raid.night ? "夜のレイド (高レベル)" : "昼のレイド",
                            raid.night ? NamedTextColor.DARK_PURPLE : NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("有効期限: マインクラフト時間で半日", NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(
                    idKey(plugin), PersistentDataType.STRING, raid.id.toString());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * このレイドの地図を回収する。
     *
     * <p>オンラインプレイヤーの持ち物 (防具・オフハンド込み) とエンダーチェスト、
     * それに地面に落ちている分 (配布時にインベントリ満杯で溢れた分) まで見る。
     * オフラインのプレイヤーの分は取り残るが、次に配られる地図とは id が違うので
     * 現行のレイドと取り違える事故にはならない。
     *
     * @return 取り除いた枚数
     */
    static int revoke(Plugin plugin, Raid raid) {
        NamespacedKey key = idKey(plugin);
        String id = raid.id.toString();
        int removed = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            removed += purge(player.getInventory(), key, id);
            removed += purge(player.getEnderChest(), key, id);
        }
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (isRaidMap(item.getItemStack(), key, id)) {
                    item.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    private static int purge(Inventory inventory, NamespacedKey key, String id) {
        int removed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isRaidMap(inventory.getItem(slot), key, id)) {
                inventory.setItem(slot, null);
                removed++;
            }
        }
        return removed;
    }

    private static boolean isRaidMap(ItemStack stack, NamespacedKey key, String id) {
        if (stack == null || stack.getType() != Material.FILLED_MAP) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && id.equals(
                meta.getPersistentDataContainer().get(key, PersistentDataType.STRING));
    }

    private static NamespacedKey idKey(Plugin plugin) {
        return new NamespacedKey(plugin, ID_KEY);
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
