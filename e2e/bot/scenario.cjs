'use strict';

/**
 * E2E 用のヘッドレスクライアント。
 *
 * <p>26.2 のプロトコルに対応した実装がまだ無いので、テストサーバーだけ 26.1 を使う。
 * プラグインの jar は本番と同じもの (26.2 でコンパイルしたもの) をそのまま載せる。
 *
 * 引数は JSON: {"port":25568,"version":"26.1","scenario":"raid_run"}
 * 結果は1行1件の JSON で標準出力へ流す。JUnit 側はそれを読んで検証する。
 * 観測を出すだけで、成否の判断はしない。レイドの駆動 (spawn / tp / kill) は
 * JUnit 側がサーバーコンソールから行う。
 */

const mineflayer = require('mineflayer');

const config = JSON.parse(process.argv[2]);
const HOST = config.host || '127.0.0.1';
const PORT = config.port;
const VERSION = config.version || '26.1';
const TIMEOUT_MS = config.timeoutMs || 120000;

/** 観測を1件流す。 */
function emit(event, fields) {
  process.stdout.write(JSON.stringify({ event, ...fields }) + '\n');
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** 参加してスポーンするまで待つ。 */
function connect(username) {
  return new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({
      host: HOST, port: PORT, username, auth: 'offline', version: VERSION,
    });
    bot.chatLog = [];
    bot.on('message', (message) => bot.chatLog.push(message.toString()));
    bot.on('kicked', (reason) =>
      reject(new Error(username + ' kicked: ' + JSON.stringify(reason).slice(0, 300))));
    bot.on('error', reject);
    bot.once('spawn', () => {
      const p = bot.entity.position;
      emit('spawned', { bot: username, x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z) });
      resolve(bot);
    });
  });
}

/** 指定した語を含むチャットが来るまで待つ。届けば true。 */
function waitForChat(bot, fragment, timeoutMs) {
  return new Promise((resolve) => {
    const deadline = Date.now() + timeoutMs;
    const timer = setInterval(() => {
      if (bot.chatLog.some((line) => line.includes(fragment))) {
        clearInterval(timer);
        resolve(true);
      } else if (Date.now() > deadline) {
        clearInterval(timer);
        resolve(false);
      }
    }, 300);
  });
}

/** 指定アイテムがインベントリに現れるまで待つ。現れれば true。 */
function waitForItem(bot, itemName, timeoutMs) {
  return new Promise((resolve) => {
    const deadline = Date.now() + timeoutMs;
    const timer = setInterval(() => {
      if (bot.inventory.items().some((item) => item.name === itemName)) {
        clearInterval(timer);
        resolve(true);
      } else if (Date.now() > deadline) {
        clearInterval(timer);
        resolve(false);
      }
    }, 500);
  });
}

// ---- シナリオ -------------------------------------------------------------

const SCENARIOS = {
  /**
   * レイド一周: 地図を受け取り、(JUnit 側の tp で) 地点へ運ばれてレイドを発火させ、
   * (JUnit 側の kill で) 全ウェーブが片付いて「レイド成功」の告知を見るまで。
   */
  async raid_run() {
    const bot = await connect('E2eRaider');

    const gotMap = await waitForItem(bot, 'filled_map', 60000);
    emit(gotMap ? 'map_received' : 'map_missing', { bot: 'E2eRaider' });

    const seen = await waitForChat(bot, 'レイド成功', 240000);
    emit('raid_success_seen', { seen });
    bot.quit();
  },

  /** レイド発火後にプレイヤーが消える (spectator 化はJUnit側)。キャンセル告知を見張る。 */
  async raid_cancel_watch() {
    const bot = await connect('E2eWatcher');
    const seen = await waitForChat(bot, 'レイドはキャンセルされた', 120000);
    emit('cancel_seen', { seen });
    bot.quit();
  },

  /** 誰も近づかないまま (JUnit 側が time add で) 半日経った失効の告知を見張る。 */
  async raid_expire_watch() {
    const bot = await connect('E2eExpirer');
    const seen = await waitForChat(bot, '失効', 90000);
    emit('expire_seen', { seen });
    bot.quit();
  },
};

(async () => {
  const scenario = SCENARIOS[config.scenario];
  if (!scenario) {
    emit('failed', { reason: '知らないシナリオ: ' + config.scenario });
    process.exit(2);
  }
  const guard = setTimeout(() => {
    emit('failed', { reason: 'シナリオがタイムアウトした' });
    process.exit(3);
  }, TIMEOUT_MS);

  try {
    await scenario();
    emit('done', {});
    clearTimeout(guard);
    await sleep(300);
    process.exit(0);
  } catch (error) {
    emit('failed', { reason: error.message });
    clearTimeout(guard);
    await sleep(300);
    process.exit(1);
  }
})();
