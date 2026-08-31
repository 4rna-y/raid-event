package io.github.raidevent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;

/**
 * レイド1件の状態。生成 (地図配布) から、発火・ウェーブ進行・終了までを持つ。
 *
 * <p>サーバー再起動をまたいで持ち越さない。wiah がいつでもワールドごと消すサーバーなので、
 * 永続化してもワールドと整合しなくなるだけ。再起動したら消えるものとして扱う。
 */
final class Raid {

    enum State {
        /** 地図は配った。誰かが近づくのを待っている。 */
        PENDING,
        /** ウェーブ進行中。 */
        ACTIVE,
    }

    final Location site;
    final int tier;
    final String crateId;
    final boolean night;
    /** ワールドの fullTime 基準の失効時刻。発火したら失効しない。 */
    final long expiresAt;

    State state = State.PENDING;
    /** 次に湧かせるウェーブ (0始まり)。ACTIVE 中は「今のウェーブ番号」= waveIndex。 */
    int waveIndex = 0;
    /** 今のウェーブで生きているモブ。 */
    final Set<UUID> aliveMobs = new HashSet<>();
    /** 今のウェーブの開始時の体数 (ボスバー用)。 */
    int waveTotal = 0;
    /** 全滅後、次ウェーブを湧かせる時刻 (fullTime)。負なら未定。 */
    long nextWaveAt = -1;
    /** 参加者が誰も居なくなった時刻 (fullTime)。負なら居る。放棄検出用。 */
    long absentSince = -1;

    Raid(Location site, int tier, String crateId, boolean night, long expiresAt) {
        this.site = site;
        this.tier = tier;
        this.crateId = crateId;
        this.night = night;
        this.expiresAt = expiresAt;
    }
}
