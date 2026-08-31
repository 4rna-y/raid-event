package io.github.raidevent;

import java.util.Locale;
import java.util.Map;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.slf4j.Logger;

/**
 * ロットテーブルの文字列指定を実際の ItemStack へ組み立てる。
 *
 * <p>エンチャントとポーションはレジストリ (サーバー) が要るので、パース時ではなく
 * ここで解決する。名前の書き間違いは黙って落とさず警告を出す (報酬が痩せていることに
 * 気付けるように)。
 */
public final class ItemBuilder {

    private ItemBuilder() {
    }

    public static ItemStack build(CrateTable.RolledItem rolled, Logger logger) {
        CrateTable.LootEntry entry = rolled.entry();
        ItemStack stack = new ItemStack(entry.item(), rolled.amount());

        if (!entry.enchants().isEmpty()) {
            applyEnchants(stack, entry.item(), entry.enchants(), logger);
        }
        if (entry.potion() != null) {
            applyPotion(stack, entry.potion(), logger);
        }
        return stack;
    }

    private static void applyEnchants(ItemStack stack, Material material,
            Map<String, Integer> enchants, Logger logger) {
        for (var e : enchants.entrySet()) {
            Enchantment enchantment = enchantment(e.getKey());
            if (enchantment == null) {
                logger.warn("知らないエンチャント {} を飛ばした ({})", e.getKey(), material);
                continue;
            }
            if (material == Material.ENCHANTED_BOOK) {
                // 本は「格納されたエンチャント」でないと金床で使えない
                if (stack.getItemMeta() instanceof EnchantmentStorageMeta meta) {
                    meta.addStoredEnchant(enchantment, e.getValue(), true);
                    stack.setItemMeta(meta);
                }
            } else {
                stack.addUnsafeEnchantment(enchantment, e.getValue());
            }
        }
    }

    private static void applyPotion(ItemStack stack, String potionName, Logger logger) {
        if (!(stack.getItemMeta() instanceof PotionMeta meta)) {
            logger.warn("{} はポーションではないのに potion 指定がある", stack.getType());
            return;
        }
        try {
            meta.setBasePotionType(PotionType.valueOf(potionName.toUpperCase(Locale.ROOT)));
            stack.setItemMeta(meta);
        } catch (IllegalArgumentException e) {
            logger.warn("知らないポーション {} を飛ばした", potionName);
        }
    }

    /** モブの武器にも使うので公開しておく。見つからなければ null。 */
    public static Enchantment enchantment(String name) {
        return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.ENCHANTMENT)
                .get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
    }
}
