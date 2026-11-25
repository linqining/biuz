// G49 滚动位置记忆（web chatSessionScrollMemory.ts 同构，2026-11-25 与 iOS/Android 同步）：
// 进程内 LRU 200（web MAX_ENTRIES 同值）；只存内存——进程死即清，与 web 行为一致。
// 键 = ws::session:<id>（单 pane 简化）；值 = scrollTop 绝对值（web 同存绝对值，
// 恢复时 scroll-view scroll-top 属性自然夹紧到内容高）。

const CAPACITY = 200;
const entries = new Map(); // 迭代序 = 插入序；最新在末尾

function save(key, scrollTop) {
  if (typeof scrollTop !== 'number' || !(scrollTop >= 0)) return;
  entries.delete(key);
  entries.set(key, scrollTop);
  while (entries.size > CAPACITY) {
    const oldest = entries.keys().next().value;
    entries.delete(oldest);
  }
}

function load(key) {
  const value = entries.get(key);
  if (value === undefined) return null;
  // 读即 touch（web :116-125 同口径）
  entries.delete(key);
  entries.set(key, value);
  return value;
}

module.exports = {
  save: save,
  load: load,
};
