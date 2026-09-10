package io.github.raidevent;

import java.util.LinkedHashSet;
import java.util.Set;

import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EntityType;
import org.bukkit.persistence.PersistentDataType;

/**
 * ワールド単位の節目。エンドラ・ウィザー・ウォーデンの討伐。
 *
 * <p>討伐の実績や統計はとどめを刺した 1 人にしか付かないので、パーティで倒しても 1 人しか
 * 段が上がらない。だから節目はワールドで持つ。エンドラはエンドのワールドが覚えている
 * ({@link DragonBattle#hasBeenPreviouslyKilled()})。ウィザーとウォーデンは死亡イベントを見て
 * 主ワールドの PDC に印を付ける。wiah でワールドが消えれば印も消える。
 *
 * <p>節目はレベルの上限になる: 無し → 5、エンドラ → 6、+ どちらか → 7、+ 両方 → 8。
 */
public final class Milestones {

    public static final String DRAGON = "dragon";
    public static final String WITHER = "wither";
    public static final String WARDEN = "warden";

    /** 節目 1 つぶんのスコア。 */
    public static final double POINTS = 6;
    /** 節目が無いときのレベルの上限 (エンド突入前の最高)。 */
    public static final int BASE_CAP = 5;

    private final Server server;
    private final NamespacedKey witherKey;
    private final NamespacedKey wardenKey;

    public Milestones(Server server, String namespace) {
        this.server = server;
        this.witherKey = new NamespacedKey(namespace, "wither-killed");
        this.wardenKey = new NamespacedKey(namespace, "warden-killed");
    }

    /** 達成済みの節目。 */
    public Set<String> achieved() {
        Set<String> achieved = new LinkedHashSet<>();
        if (dragonKilled()) {
            achieved.add(DRAGON);
        }
        World main = mainWorld();
        if (main != null && main.getPersistentDataContainer().has(witherKey, PersistentDataType.BYTE)) {
            achieved.add(WITHER);
        }
        if (main != null && main.getPersistentDataContainer().has(wardenKey, PersistentDataType.BYTE)) {
            achieved.add(WARDEN);
        }
        return achieved;
    }

    public boolean dragonKilled() {
        for (World world : server.getWorlds()) {
            if (world.getEnvironment() != World.Environment.THE_END) {
                continue;
            }
            DragonBattle battle = world.getEnderDragonBattle();
            if (battle != null && battle.hasBeenPreviouslyKilled()) {
                return true;
            }
        }
        return false;
    }

    /** 討伐を記録する。ウィザーとウォーデン以外は無視。新しく記録したときだけ true。 */
    public boolean record(EntityType type) {
        NamespacedKey key = switch (type) {
            case WITHER -> witherKey;
            case WARDEN -> wardenKey;
            default -> null;
        };
        World main = mainWorld();
        if (key == null || main == null) {
            return false;
        }
        if (main.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
            return false;
        }
        main.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        return true;
    }

    private World mainWorld() {
        return server.getWorlds().isEmpty() ? null : server.getWorlds().get(0);
    }

    /** 節目で決まるレベルの上限。 */
    public static int cap(Set<String> achieved) {
        if (!achieved.contains(DRAGON)) {
            return BASE_CAP;
        }
        boolean wither = achieved.contains(WITHER);
        boolean warden = achieved.contains(WARDEN);
        if (wither && warden) {
            return BASE_CAP + 3;
        }
        return wither || warden ? BASE_CAP + 2 : BASE_CAP + 1;
    }

    /** 節目ぶんのスコア。 */
    public static double score(Set<String> achieved) {
        return achieved.size() * POINTS;
    }
}
