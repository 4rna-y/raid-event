package io.github.raidevent;

/** モブ数の人数スケール。テーブルの基準は参加者2人。 */
public final class Scaling {

    private Scaling() {
    }

    /**
     * 参加人数に応じた実際のスポーン数。
     *
     * <p>2人で等倍、1人で ×0.6、3人目からは1人ごとに +40%。端数は切り上げる
     * (0体のエントリを作らないため、最低1体)。
     */
    public static int scaled(int base, int participants) {
        double factor = participants <= 1 ? 0.6 : 1.0 + 0.4 * (participants - 2);
        return Math.max(1, (int) Math.ceil(base * factor));
    }
}
