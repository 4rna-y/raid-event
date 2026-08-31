package io.github.raidevent;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;

/**
 * レイドモブの増減を見張る。
 *
 * <p>死亡は {@link EntityDeathEvent} (MONITOR、観測のみ) で数え、それ以外の消え方は
 * {@link EntityRemoveEvent} の原因で仕分ける。
 * wiah が居るサーバーではプレイヤー死亡=ワールド消滅なので、プレイヤーの死には触らない。
 */
final class RaidListener implements Listener {

    private final RaidManager manager;

    RaidListener(RaidManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onDeath(EntityDeathEvent event) {
        manager.onMobGone(event.getEntity().getUniqueId(), false);
    }

    @EventHandler
    void onRemove(EntityRemoveEvent event) {
        switch (event.getCause()) {
            // 「プレイヤーが逃げて生成されたモンスターがデスポーンした場合、レイドは
            // キャンセル」の仕様。UNLOAD も同じ扱いにする: 逃げた先でチャンクごと
            // アンロードされるとデスポーンは観測できないが、プレイヤーから見れば同じ逃走
            case DESPAWN, UNLOAD -> manager.onMobGone(event.getEntity().getUniqueId(), true);
            // 変身 (ゾンビ→ドラウンド等) や奈落は討伐扱い。逃走ではないのでレイドは続く
            case TRANSFORMATION, OUT_OF_WORLD ->
                    manager.onMobGone(event.getEntity().getUniqueId(), false);
            default -> {
                // DEATH は onDeath が先に数えている。PLUGIN (自前の掃除) は cleaning 旗で無視される
            }
        }
    }
}
