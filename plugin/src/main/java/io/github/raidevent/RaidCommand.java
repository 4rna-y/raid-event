package io.github.raidevent;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /raidevent} (別名 {@code /re})。
 *
 * <p>引数なしは「今レイドがあるかどうか」を返すだけなので誰でも実行できる。
 * 生成やキャンセルなど状態を触るものは {@code raidevent.admin} 持ちだけ。
 */
public final class RaidCommand implements BasicCommand {

    private static final List<String> ADMIN_SUB_COMMANDS =
            List.of("status", "spawn", "cancel", "reload");

    private final RaidEventPlugin plugin;

    public RaidCommand(RaidEventPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String permission() {
        return "raidevent.use";
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> showCurrent(sender);
            case "status" -> {
                if (requireAdmin(sender)) {
                    showStatus(sender);
                }
            }
            case "spawn" -> {
                if (requireAdmin(sender)) {
                    spawn(sender, args);
                }
            }
            case "cancel" -> {
                if (requireAdmin(sender)) {
                    if (plugin.manager().current().isEmpty()) {
                        sender.sendMessage(plugin.message("<gray>進行中のレイドはありません。"));
                    } else {
                        plugin.manager().cancel("管理コマンド");
                    }
                }
            }
            case "reload" -> {
                if (requireAdmin(sender)) {
                    try {
                        plugin.reloadAll();
                        sender.sendMessage(plugin.message("<green>config.yml を再読み込みしました。"));
                    } catch (IllegalArgumentException e) {
                        sender.sendMessage(plugin.message(
                                "<red>設定に不備があります: " + e.getMessage()));
                    }
                }
            }
            default -> sender.sendMessage(plugin.message(sender.hasPermission("raidevent.admin")
                    ? "<red>使い方: /re [status|spawn <tier> <crate> [player]|cancel|reload]"
                    : "<red>使い方: /re (進行中のレイドを表示)"));
        }
    }

    // ------------------------------------------------------------------ 表示

    private void showCurrent(CommandSender sender) {
        var current = plugin.manager().current();
        if (current.isEmpty()) {
            sender.sendMessage(plugin.message("<gray>今はレイドが無い。地図が配られたら急げ。"));
            return;
        }
        Raid raid = current.get();
        String state = raid.state == Raid.State.PENDING
                ? "接近待ち" : "ウェーブ " + raid.waveIndex + " 進行中 (残り " + raid.aliveMobs.size() + "体)";
        sender.sendMessage(plugin.message("<yellow>レイド " + state
                + " <gray>Tier " + raid.tier + " / 地点 (" + raid.site.getBlockX()
                + ", " + raid.site.getBlockY() + ", " + raid.site.getBlockZ() + ")"));
    }

    private void showStatus(CommandSender sender) {
        sender.sendMessage(plugin.message("<white>v" + plugin.getPluginMeta().getVersion()
                + " / enabled: " + plugin.getConfig().getBoolean("enabled", true)
                + " / ティア: " + plugin.manager().tiers().tiers().size()
                + " / クレート: " + plugin.manager().crates().crates().size()));
        showCurrent(sender);
    }

    // ------------------------------------------------------------------ 生成

    /** {@code /re spawn <tier> <crate> [player]}。コンソールからは player 必須。 */
    private void spawn(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.message("<red>使い方: /re spawn <tier> <crate> [player]"));
            return;
        }
        int tier;
        try {
            tier = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.message("<red>ティアは数字で: " + args[1]));
            return;
        }
        Player center = null;
        if (args.length >= 4) {
            center = Bukkit.getPlayerExact(args[3]);
            if (center == null) {
                sender.sendMessage(plugin.message("<red>プレイヤーが見つからない: " + args[3]));
                return;
            }
        } else if (sender instanceof Player player) {
            center = player;
        } else {
            sender.sendMessage(plugin.message("<red>コンソールからは基準プレイヤーを指定すること。"));
            return;
        }
        try {
            Raid raid = plugin.manager().forceSpawn(tier, args[2].toLowerCase(Locale.ROOT), center);
            if (raid == null) {
                sender.sendMessage(plugin.message(
                        "<red>生成できなかった (進行中のレイドがあるか、地点が見つからない)。"));
            } else {
                sender.sendMessage(plugin.message("<green>生成した: 地点 ("
                        + raid.site.getBlockX() + ", " + raid.site.getBlockY()
                        + ", " + raid.site.getBlockZ() + ")"));
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage(plugin.message("<red>" + e.getMessage()));
        }
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("raidevent.admin")) {
            return true;
        }
        sender.sendMessage(plugin.message("<red>権限がありません。"));
        return false;
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
            String @NotNull [] args) {
        if (args.length > 1 || !source.getSender().hasPermission("raidevent.admin")) {
            return List.of();
        }
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return ADMIN_SUB_COMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
    }
}
