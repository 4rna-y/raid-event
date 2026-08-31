package io.github.raidevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** モブ数の人数スケール。基準は参加者2人。 */
class ScalingTest {

    @Test
    @DisplayName("2人で等倍")
    void twoPlayersIsBaseline() {
        assertEquals(4, Scaling.scaled(4, 2));
        assertEquals(1, Scaling.scaled(1, 2));
    }

    @Test
    @DisplayName("1人なら ×0.6 (切り上げ)")
    void soloIsReduced() {
        assertEquals(3, Scaling.scaled(4, 1));   // 2.4 → 3
        assertEquals(2, Scaling.scaled(3, 1));   // 1.8 → 2
        assertEquals(1, Scaling.scaled(1, 1));
    }

    @Test
    @DisplayName("3人目からは1人ごとに +40%")
    void extraPlayersScaleUp() {
        assertEquals(6, Scaling.scaled(4, 3));   // 5.6 → 6
        assertEquals(8, Scaling.scaled(4, 4));   // 7.2 → 8
    }

    @Test
    @DisplayName("最低1体は湧く")
    void neverZero() {
        for (int players = 0; players <= 8; players++) {
            assertTrue(Scaling.scaled(1, players) >= 1);
        }
    }
}
