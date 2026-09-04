package io.github.raidevent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("レイドモブの日光発火だけを握り潰す")
class RaidListenerTest {

    private final RaidListener listener = new RaidListener(mock(RaidManager.class));

    private static Entity entity(String... tags) {
        Entity entity = mock(Entity.class);
        when(entity.getScoreboardTags()).thenReturn(Set.of(tags));
        return entity;
    }

    @Test
    @DisplayName("レイドモブの日光発火はキャンセルされる (昼レイドの自壊防止)")
    void cancelsSunlightBurnForRaidMobs() {
        EntityCombustEvent event = new EntityCombustEvent(entity(MobSpawner.TAG), 8f);
        listener.onCombust(event);
        assertTrue(event.isCancelled(), "レイドモブが日光で燃えてしまう");
    }

    @Test
    @DisplayName("レイド外のモブは昼どおり燃える")
    void leavesOtherMobsAlone() {
        EntityCombustEvent event = new EntityCombustEvent(entity(), 8f);
        listener.onCombust(event);
        assertFalse(event.isCancelled(), "無関係なモブの日光発火まで止めている");
    }

    @Test
    @DisplayName("火属性の攻撃はレイドモブにも通る (サブクラスのイベントは弾かない)")
    void leavesFireAttacksAlone() {
        // EntityCombustByBlockEvent / EntityCombustByEntityEvent は EntityCombustEvent と
        // ハンドラリストを共有していて onCombust にも届く。ここを弾くと火攻めが死ぬ
        EntityCombustEvent event = new EntityCombustByEntityEvent(
                entity(), entity(MobSpawner.TAG), 5f);
        listener.onCombust(event);
        assertFalse(event.isCancelled(), "プレイヤーの火属性攻撃まで止めている");
    }
}
