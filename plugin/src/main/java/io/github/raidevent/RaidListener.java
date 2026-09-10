package io.github.raidevent;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;

/**
 * レイドモブの増減を見張る。
 *
 * <p>死亡は {@link EntityDeathEvent} (MONITOR、観測のみ) で数え、それ以外の消え方は
 * {@link EntityRemoveEvent} の原因で仕分ける。
 * wiah が居るサーバーではプレイヤー死亡=ワールド消滅なので、プレイヤーの死には触らない。
 *
 * <p>あわせて、レイドモブの日光発火もここで止める ({@link #onCombust})。
 */
final class RaidListener implements Listener {

    private final RaidManager manager;

    RaidListener(RaidManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onDeath(EntityDeathEvent event) {
        manager.onMobGone(event.getEntity().getUniqueId(), false);
        // ウィザー・ウォーデンの討伐は誰が倒したかを問わずワールドの節目にする
        if (manager.milestones().record(event.getEntityType())) {
            manager.onMilestone(event.getEntityType());
        }
    }

    /**
     * 日光による発火を握り潰す。昼レイドでモブが勝手に燃え死ぬのを防ぐ。
     *
     * <p>{@code MobSpawner} の {@code setShouldBurnInDay(false)} は型ごとの API で、
     * それを持たないモブや将来増える経路を取りこぼす。日光発火は素の
     * {@link EntityCombustEvent} で飛ぶので、レイドモブについてはここで一律に弾く。
     *
     * <p>溶岩・炎ブロック・火属性の攻撃はサブクラス
     * ({@code EntityCombustByBlockEvent} / {@code EntityCombustByEntityEvent}) で飛ぶ。
     * これらは同じハンドラリストを共有していてこのメソッドにも届くため、
     * 素の型のときだけ弾いてプレイヤーの火攻めは殺さない。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    void onCombust(EntityCombustEvent event) {
        if (event.getClass() != EntityCombustEvent.class) {
            return;
        }
        if (event.getEntity().getScoreboardTags().contains(MobSpawner.TAG)) {
            event.setCancelled(true);
        }
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
