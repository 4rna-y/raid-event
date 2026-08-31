package io.github.raidevent;

import java.util.List;
import java.util.Random;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.java.JavaPlugin;

/** RaidEvent プラグインの入口。 */
public final class RaidEventPlugin extends JavaPlugin {

    /** config.yml を消して起動した場合の接頭辞。同梱の config.yml と揃えること。 */
    public static final String DEFAULT_MESSAGE_PREFIX = "<gray>[<gold>RaidEvent<gray>]</gray> ";

    private RaidManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // キルスイッチ。リスナーもコマンドも周期処理も登録しない
        if (!getConfig().getBoolean("enabled", true)) {
            getSLF4JLogger().warn("config.yml で enabled: false になっているため、何も行いません。");
            return;
        }

        this.manager = new RaidManager(this, new Random());
        // テーブルの不備は起動時に落として気付く (黙って痩せたレイドを回さない)
        manager.load(getConfig());

        getServer().getPluginManager().registerEvents(new RaidListener(manager), this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("raidevent", "レイドイベントの確認と管理",
                        List.of("re"), new RaidCommand(this)));

        long period = getConfig().getLong("raid.check-period-ticks",
                RaidManager.DEFAULT_CHECK_PERIOD_TICKS);
        getServer().getGlobalRegionScheduler().runAtFixedRate(this,
                task -> manager.tick(), period, period);
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public RaidManager manager() {
        return manager;
    }

    /** 設定とテーブルを読み直す。不備があれば IllegalArgumentException。 */
    public void reloadAll() {
        reloadConfig();
        manager.load(getConfig());
    }

    /** 設定された接頭辞を付けたメッセージを組み立てる。 */
    public Component message(String miniMessage) {
        String prefix = getConfig().getString("message-prefix", DEFAULT_MESSAGE_PREFIX);
        return MiniMessage.miniMessage().deserialize(prefix + miniMessage);
    }
}
