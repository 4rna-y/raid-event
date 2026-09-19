package io.github.raidevent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Chest;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

/**
 * レイドの一生を回す中枢。生成の抽選 → 地図配布 → 接近で発火 → ウェーブ進行 → 報酬設置。
 *
 * <p>同時に存在できるレイドは1つ。進行中は新しい抽選をしない (小さいサーバーで
 * 複数レイドが並ぶと、どれにも人が足りなくなるだけ)。
 *
 * <p>レイドはオーバーワールド専用。エンドは突入=不可帰還点 (討伐か死しかない) なので、
 * このプラグインの守備範囲は「ワールド生成〜エンド突入まで」。全員がネザーやエンドに
 * 居る間は抽選そのものを飛ばす。
 */
public final class RaidManager {

    /** config.yml の raid.* の既定値。DefaultConfigTest で同梱 yml と突き合わせる。 */
    public static final double DEFAULT_DAILY_CHANCE = 0.5;
    public static final long DEFAULT_EXPIRE_TICKS = 12000;
    public static final int DEFAULT_TRIGGER_RADIUS = 16;
    public static final int DEFAULT_MIN_DISTANCE = 200;
    public static final int DEFAULT_MAX_DISTANCE = 600;
    public static final int DEFAULT_PARTICIPANT_RADIUS = 48;
    public static final long DEFAULT_WAVE_INTERVAL_TICKS = 160;
    public static final long DEFAULT_CHECK_PERIOD_TICKS = 20;
    public static final int DEFAULT_ABANDON_RADIUS = 96;
    public static final long DEFAULT_ABANDON_TICKS = 2400;

    record Settings(double dailyChance, long expireTicks, double triggerRadius,
            int minDistance, int maxDistance, double participantRadius,
            long waveIntervalTicks, double abandonRadius, long abandonTicks) {

        static Settings from(FileConfiguration config) {
            return new Settings(
                    config.getDouble("raid.daily-chance", DEFAULT_DAILY_CHANCE),
                    config.getLong("raid.expire-ticks", DEFAULT_EXPIRE_TICKS),
                    config.getDouble("raid.trigger-radius", DEFAULT_TRIGGER_RADIUS),
                    config.getInt("raid.min-distance", DEFAULT_MIN_DISTANCE),
                    config.getInt("raid.max-distance", DEFAULT_MAX_DISTANCE),
                    config.getDouble("raid.participant-radius", DEFAULT_PARTICIPANT_RADIUS),
                    config.getLong("raid.wave-interval-ticks", DEFAULT_WAVE_INTERVAL_TICKS),
                    config.getDouble("raid.abandon-radius", DEFAULT_ABANDON_RADIUS),
                    config.getLong("raid.abandon-ticks", DEFAULT_ABANDON_TICKS));
        }
    }

    private final RaidEventPlugin plugin;
    private final Random random;
    private final Milestones milestones;

    private Settings settings;
    private LevelTable levels;
    private CrateTable crates;
    private ExtraDrops extraDrops;
    private ProgressionScore score;

    private Raid current;
    /** 前回見た日番号。変わった瞬間が「新しい日」= 抽選のタイミング。 */
    private long lastDay = -1;
    /** 抽選に当たった日の発生予定時刻 (fullTime)。負なら予定なし。 */
    private long scheduledAt = -1;
    /** キャンセル時の自前削除で EntityRemoveEvent が飛ぶ。それを逃走と誤認しないための旗。 */
    private boolean cleaning;
    private BossBar bossBar;

    public RaidManager(RaidEventPlugin plugin, Random random, Milestones milestones) {
        this.plugin = plugin;
        this.random = random;
        this.milestones = milestones;
    }

    public Milestones milestones() {
        return milestones;
    }

    /** 設定とテーブルを読み直す。不備があれば例外 (起動時に気付くべきもの)。 */
    public void load(FileConfiguration config) {
        // 運用側の config.yml が raid.* だけを書き、levels / crates は同梱の既定値に
        // 任せる使い方を許す。copyDefaults が無いと getKeys() が既定値側のキーを
        // 列挙してくれず、「crates が空」で落ちる
        config.options().copyDefaults(true);
        this.settings = Settings.from(config);
        this.levels = LevelTable.parse(config);
        this.crates = CrateTable.parse(config);
        this.extraDrops = ExtraDrops.parse(config);
        this.score = new ProgressionScore((player, key) -> {
            Advancement advancement = Bukkit.getAdvancement(key);
            return advancement != null && player.getAdvancementProgress(advancement).isDone();
        }, config);
    }

    // ------------------------------------------------------------------ 周期処理

    /** check-period-ticks ごとに呼ばれる。 */
    public void tick() {
        World world = overworld();
        long now = world.getFullTime();
        rollDaily(now);
        if (scheduledAt >= 0 && now >= scheduledAt && current == null) {
            scheduledAt = -1;
            create(world, null, 0, null);
        }
        if (current != null) {
            if (current.state == Raid.State.PENDING) {
                tickPending(world, now);
            } else {
                tickActive(world, now);
            }
        }
        updateBossBar(world);
    }

    private void rollDaily(long now) {
        long day = now / 24000L;
        if (lastDay < 0) {
            // 起動直後は日の途中かもしれない。この日は抽選しない
            lastDay = day;
            return;
        }
        if (day == lastDay) {
            return;
        }
        lastDay = day;
        if (current != null || scheduledAt >= 0) {
            return;
        }
        if (random.nextDouble() < settings.dailyChance()) {
            // その日の中のランダムな時刻に発生させる。昼夜はここで自然に散る
            scheduledAt = day * 24000L + random.nextInt(24000);
            plugin.getSLF4JLogger().info("レイド抽選: 本日発生する (予定 fullTime={})", scheduledAt);
        }
    }

    private void tickPending(World world, long now) {
        if (now >= current.expiresAt) {
            expire();
            return;
        }
        double radius = settings.triggerRadius();
        boolean near = world.getNearbyPlayers(current.site, radius).stream()
                .anyMatch(MobSpawner::isSurvivalLike);
        if (near) {
            current.state = Raid.State.ACTIVE;
            // 発火したらもう地図の役目は終わり。回収してから始める
            revokeMaps("発動");
            broadcast("<red>レイド開始! モンスターの襲撃を退けろ。");
            spawnWave(world);
        }
    }

    private void tickActive(World world, long now) {
        // 放棄検出。チャンクごとアンロードされるとデスポーンを観測できないので、
        // 「近くに誰も居ない時間」でも打ち切れるようにしておく
        boolean present = world.getNearbyPlayers(current.site, settings.abandonRadius()).stream()
                .anyMatch(MobSpawner::isSurvivalLike);
        if (!present) {
            if (current.absentSince < 0) {
                current.absentSince = now;
            } else if (now - current.absentSince >= settings.abandonTicks()) {
                cancel("参加者が離脱した");
                return;
            }
        } else {
            current.absentSince = -1;
        }

        if (!current.aliveMobs.isEmpty()) {
            return;
        }
        int totalWaves = levels.level(current.level).waves().size();
        if (current.waveIndex >= totalWaves) {
            finish(world);
        } else if (current.nextWaveAt < 0) {
            current.nextWaveAt = now + settings.waveIntervalTicks();
        } else if (now >= current.nextWaveAt) {
            spawnWave(world);
        }
    }

    // ------------------------------------------------------------------ 生成と進行

    /**
     * レイドを1件生成して地図を配る。
     *
     * @param center      地点の基準にするプレイヤー。null なら対象者から無作為に選ぶ
     * @param forcedLevel  0 なら進行度から決める (管理コマンド用)
     * @param forcedCrate null なら無作為 (管理コマンド用)
     * @return 生成できなければ null (対象者が居ない・地形が見つからない)
     */
    Raid create(World world, Player center, int forcedLevel, String forcedCrate) {
        List<Player> eligible = eligiblePlayers(world);
        if (eligible.isEmpty()) {
            plugin.getSLF4JLogger().info("レイド生成を見送った: オーバーワールドに対象プレイヤーが居ない");
            return null;
        }
        Player anchor = center != null ? center
                : eligible.get(random.nextInt(eligible.size()));
        Location site = RaidSitePicker.pick(anchor.getLocation(),
                settings.minDistance(), settings.maxDistance(), random);
        if (site == null) {
            plugin.getSLF4JLogger().warn("レイド生成を見送った: 安全な地点が見つからない");
            return null;
        }
        boolean night = ProgressionScore.isNight(world);
        java.util.Set<String> achieved = milestones.achieved();
        int level = forcedLevel > 0 ? forcedLevel : score.levelFor(eligible, world, night, achieved);
        String crateId = forcedCrate != null ? forcedCrate : randomCrateId();

        current = new Raid(site, level, crateId, night,
                world.getFullTime() + settings.expireTicks());
        String crateName = crates.crate(crateId).displayName();
        MapService.give(plugin, eligible, current, crateName);
        broadcast("<yellow>レイドが発生した! 配られた地図を確認せよ。"
                + " <gray>(" + crateName + " / Level " + level + ")");
        plugin.getSLF4JLogger().info("レイド生成: level={} crate={} night={} 節目={} 地点=({}, {}, {})",
                level, crateId, night, achieved,
                site.getBlockX(), site.getBlockY(), site.getBlockZ());
        return current;
    }

    private void spawnWave(World world) {
        LevelTable.Level level = levels.level(current.level);
        LevelTable.Wave wave = level.waves().get(current.waveIndex);
        int participants = (int) world.getNearbyPlayers(
                        current.site, settings.participantRadius()).stream()
                .filter(MobSpawner::isSurvivalLike).count();

        current.aliveMobs.clear();
        int spawned = 0;
        for (LevelTable.MobEntry entry : wave.mobs()) {
            int count = Scaling.scaled(entry.count(), participants);
            for (int i = 0; i < count; i++) {
                Location location = RaidSitePicker.spread(current.site, random, 8, 16);
                Mob mob = MobSpawner.spawn(plugin, location, entry, level, random);
                if (mob != null) {
                    current.aliveMobs.add(mob.getUniqueId());
                    spawned++;
                }
            }
        }
        current.waveIndex++;
        current.waveTotal = spawned;
        current.nextWaveAt = -1;
        broadcast("<red>ウェーブ " + current.waveIndex + "/" + level.waves().size()
                + " <gray>(" + spawned + "体)");
        plugin.getSLF4JLogger().info("ウェーブ {}/{} 開始 ({}体)",
                current.waveIndex, level.waves().size(), spawned);
    }

    /** レイドモブが1体消えた。死亡なら討伐、逃走デスポーンならレイドごとキャンセル。 */
    public void onMobGone(UUID id, boolean fled) {
        if (current == null || cleaning || current.state != Raid.State.ACTIVE) {
            return;
        }
        if (!current.aliveMobs.remove(id)) {
            return;
        }
        if (fled) {
            cancel("モンスターがデスポーンした (プレイヤーの逃走)");
        }
        // 全滅していれば次の tick が次ウェーブか報酬設置を拾う
    }

    private void finish(World world) {
        Raid raid = current;
        CrateTable.Crate crate = crates.crate(raid.crateId);
        placeChest(world, raid.site, crate.level(raid.level), raid.level);
        broadcast("<green>レイド成功! <yellow>" + crate.displayName() + " (Level " + raid.level
                + ")<green> を <white>(" + raid.site.getBlockX() + ", " + raid.site.getBlockY()
                + ", " + raid.site.getBlockZ() + ")<green> に設置した。");
        plugin.getSLF4JLogger().info("レイド成功: 報酬チェスト ({}, {}, {})",
                raid.site.getBlockX(), raid.site.getBlockY(), raid.site.getBlockZ());
        clear();
    }

    private void placeChest(World world, Location site, CrateTable.CrateLevel crateLevel, int level) {
        var block = world.getBlockAt(site);
        block.setType(Material.CHEST);
        if (!(block.getState() instanceof Chest chest)) {
            plugin.getSLF4JLogger().warn("チェストを設置できなかった: {}", site);
            return;
        }
        // 追加報酬を先に並べる。クレートの中身と合わせると 27 枠を超えることがあるので、
        // 溢れさせるなら稀少な方ではなく通常枠の側にする
        List<CrateTable.RolledItem> items = new ArrayList<>(extraDrops.roll(level, random));
        int extras = items.size();
        items.addAll(crateLevel.roll(random));
        if (items.size() > CrateTable.CHEST_SLOTS) {
            plugin.getSLF4JLogger().warn("報酬がチェストに入り切らない ({} 個中 {} 個を捨てた)",
                    items.size(), items.size() - CrateTable.CHEST_SLOTS);
        }
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < CrateTable.CHEST_SLOTS; i++) {
            slots.add(i);
        }
        Collections.shuffle(slots, random);
        for (int i = 0; i < items.size() && i < slots.size(); i++) {
            chest.getBlockInventory().setItem(slots.get(i),
                    ItemBuilder.build(items.get(i), plugin.getSLF4JLogger()));
        }
        if (extras > 0) {
            plugin.getSLF4JLogger().info("追加報酬 {} 個: {}", extras, items.subList(0, extras).stream()
                    .map(item -> item.entry().custom() != null
                            ? item.entry().custom() : item.entry().item().getKey().getKey())
                    .toList());
        }
    }

    /** 進行中のレイドを打ち切る。残っているモブは消す。 */
    public void cancel(String reason) {
        if (current == null) {
            return;
        }
        cleaning = true;
        try {
            for (UUID id : current.aliveMobs) {
                Entity entity = Bukkit.getEntity(id);
                if (entity != null) {
                    entity.remove();
                }
            }
        } finally {
            cleaning = false;
        }
        broadcast("<red>レイドはキャンセルされた <gray>(" + reason + ")");
        plugin.getSLF4JLogger().info("レイドキャンセル: {}", reason);
        clear();
    }

    private void expire() {
        broadcast("<gray>レイドの地図は失効した。");
        plugin.getSLF4JLogger().info("レイド失効");
        clear();
    }

    private void clear() {
        hideBossBar();
        // 失効・成功・キャンセル・停止のどれで終わっても、死んだ地点を指す地図は残さない
        // (発動時に回収済みなら空振りするだけ)
        revokeMaps("終了");
        current = null;
    }

    /** 現行レイドの地図を配布先から回収する。 */
    private void revokeMaps(String reason) {
        if (current == null) {
            return;
        }
        int removed = MapService.revoke(plugin, current);
        if (removed > 0) {
            plugin.getSLF4JLogger().info("レイドの地図を {}枚 回収した ({})", removed, reason);
        }
    }

    /** 停止時の後片付け。モブを残すとプラグイン無しの世界に強化モブが漂う。 */
    public void shutdown() {
        if (current != null) {
            cleaning = true;
            try {
                for (UUID id : current.aliveMobs) {
                    Entity entity = Bukkit.getEntity(id);
                    if (entity != null) {
                        entity.remove();
                    }
                }
            } finally {
                cleaning = false;
            }
        }
        clear();
    }

    // ------------------------------------------------------------------ 管理コマンド用

    /** 任意のレベル・クレートで今すぐ生成する。進行中があれば作らない。 */
    public Raid forceSpawn(int level, String crateId, Player center) {
        if (current != null) {
            return null;
        }
        crates.crate(crateId);   // 知らない id なら例外
        if (level < 1 || level > LevelTable.MAX_LEVEL) {
            throw new IllegalArgumentException("レベルは 1〜" + LevelTable.MAX_LEVEL);
        }
        return create(overworld(), center, level, crateId);
    }

    /** 節目が増えた。レベルの上限が上がったことを知らせる。 */
    public void onMilestone(org.bukkit.entity.EntityType type) {
        java.util.Set<String> achieved = milestones.achieved();
        broadcast("<gold>" + (type == org.bukkit.entity.EntityType.WITHER ? "ウィザー" : "ウォーデン")
                + " が討伐された。<gray>レイドは Level " + Milestones.cap(achieved) + " まで出るようになった。");
        plugin.getSLF4JLogger().info("節目: {} (達成 {})", type, achieved);
    }

    public Optional<Raid> current() {
        return Optional.ofNullable(current);
    }

    public LevelTable levels() {
        return levels;
    }

    public CrateTable crates() {
        return crates;
    }

    // ------------------------------------------------------------------ 下回り

    private World overworld() {
        return Bukkit.getWorlds().get(0);
    }

    /** レイドの対象者: オーバーワールドに居るサバイバル系のプレイヤー。 */
    private List<Player> eligiblePlayers(World world) {
        return world.getPlayers().stream().filter(MobSpawner::isSurvivalLike).toList();
    }

    private String randomCrateId() {
        List<String> ids = List.copyOf(crates.crates().keySet());
        return ids.get(random.nextInt(ids.size()));
    }

    private void broadcast(String miniMessage) {
        Bukkit.getOnlinePlayers().forEach(player ->
                player.sendMessage(plugin.message(miniMessage)));
    }

    private void updateBossBar(World world) {
        if (current == null || current.state != Raid.State.ACTIVE) {
            hideBossBar();
            return;
        }
        int totalWaves = levels.level(current.level).waves().size();
        if (bossBar == null) {
            bossBar = BossBar.bossBar(Component.empty(), 1f,
                    BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        }
        bossBar.name(Component.text("レイド ウェーブ " + current.waveIndex + "/" + totalWaves
                + " (残り " + current.aliveMobs.size() + "体)"));
        float progress = current.waveTotal == 0 ? 0f
                : (float) current.aliveMobs.size() / current.waveTotal;
        bossBar.progress(Math.clamp(progress, 0f, 1f));
        world.getPlayers().forEach(player -> player.showBossBar(bossBar));
    }

    private void hideBossBar() {
        if (bossBar == null) {
            return;
        }
        Bukkit.getOnlinePlayers().forEach(player -> player.hideBossBar(bossBar));
    }
}
