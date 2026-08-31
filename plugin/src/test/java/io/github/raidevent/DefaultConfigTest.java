package io.github.raidevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 同梱する config.yml の既定値を固定する。 */
class DefaultConfigTest {

    private final YamlConfiguration config = loadBundledConfig();

    @Test
    @DisplayName("既定で有効")
    void enabledByDefault() {
        assertTrue(config.getBoolean("enabled"));
    }

    @Test
    @DisplayName("接頭辞は同梱 config.yml とコード側の既定値で一致する")
    void prefixMatchesCodeDefault() {
        String raw = config.getString("message-prefix");
        assertNotNull(raw, "message-prefix が config.yml に無い");
        assertEquals(RaidEventPlugin.DEFAULT_MESSAGE_PREFIX, raw,
                "config.yml を消して起動したときに見た目が変わってしまう");
    }

    @Test
    @DisplayName("接頭辞は MiniMessage として解釈できる")
    void prefixIsValidMiniMessage() {
        String rendered = PlainTextComponentSerializer.plainText().serialize(
                MiniMessage.miniMessage().deserialize(config.getString("message-prefix", "")));
        assertEquals("[RaidEvent] ", rendered);
    }

    @Test
    @DisplayName("raid.* の既定値はコード側と一致する")
    void raidDefaultsMatchCode() {
        assertEquals(RaidManager.DEFAULT_DAILY_CHANCE, config.getDouble("raid.daily-chance"));
        assertEquals(RaidManager.DEFAULT_EXPIRE_TICKS, config.getLong("raid.expire-ticks"));
        assertEquals(RaidManager.DEFAULT_TRIGGER_RADIUS, config.getInt("raid.trigger-radius"));
        assertEquals(RaidManager.DEFAULT_MIN_DISTANCE, config.getInt("raid.min-distance"));
        assertEquals(RaidManager.DEFAULT_MAX_DISTANCE, config.getInt("raid.max-distance"));
        assertEquals(RaidManager.DEFAULT_PARTICIPANT_RADIUS,
                config.getInt("raid.participant-radius"));
        assertEquals(RaidManager.DEFAULT_WAVE_INTERVAL_TICKS,
                config.getLong("raid.wave-interval-ticks"));
        assertEquals(RaidManager.DEFAULT_CHECK_PERIOD_TICKS,
                config.getLong("raid.check-period-ticks"));
        assertEquals(RaidManager.DEFAULT_ABANDON_RADIUS, config.getInt("raid.abandon-radius"));
        assertEquals(RaidManager.DEFAULT_ABANDON_TICKS, config.getLong("raid.abandon-ticks"));
    }

    @Test
    @DisplayName("失効はマインクラフト時間の半日")
    void expiresInHalfDay() {
        assertEquals(12000, config.getLong("raid.expire-ticks"),
                "仕様: レイドイベントの有効期限はマインクラフト時間で半日");
    }

    @Test
    @DisplayName("スコアの既定値もコード側と一致する")
    void scoreDefaultsMatchCode() {
        assertEquals(ProgressionScore.DEFAULT_TIER_THRESHOLDS,
                List.copyOf(config.getIntegerList("score.tier-thresholds")));
        assertEquals(ProgressionScore.DEFAULT_DAYS_PER_POINT,
                config.getInt("score.days-per-point"));
        assertEquals(ProgressionScore.DEFAULT_DAYS_MAX_POINTS,
                config.getInt("score.days-max-points"));
    }

    static YamlConfiguration loadBundledConfig() {
        try (InputStream in = DefaultConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml がテストのクラスパスに無い");
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("config.yml を読めません", e);
        }
    }
}
