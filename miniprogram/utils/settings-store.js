// 本地设置（语言/外观）：只存在本机，不依赖连接层。
// 键名与 iOS UserDefaults / Android AppSettings 口径对齐：settings.language / settings.appearance。

const LANGUAGE_KEY = 'settings.language';
const APPEARANCE_KEY = 'settings.appearance';

const LANGUAGES = [
  { key: 'system', label: '跟随系统' },
  { key: 'zh', label: '简体中文' },
  { key: 'en', label: 'English' },
];

const APPEARANCES = [
  { key: 'system', label: '跟随系统' },
  { key: 'light', label: '浅色' },
  { key: 'dark', label: '深色' },
];

function read(key, fallback) {
  try {
    const value = wx.getStorageSync(key);
    return value || fallback;
  } catch (err) {
    return fallback;
  }
}

function write(key, value) {
  try {
    wx.setStorageSync(key, value);
  } catch (err) {
    console.error('[BiuZ] settings write failed', err);
  }
}

function language() {
  return read(LANGUAGE_KEY, 'system');
}

function setLanguage(key) {
  write(LANGUAGE_KEY, key);
}

function appearance() {
  return read(APPEARANCE_KEY, 'system');
}

function setAppearance(key) {
  write(APPEARANCE_KEY, key);
}

function labelOf(list, key) {
  const hit = list.filter(function (item) { return item.key === key; })[0];
  return hit ? hit.label : (list[0] && list[0].label) || '';
}

module.exports = {
  LANGUAGES: LANGUAGES,
  APPEARANCES: APPEARANCES,
  language: language,
  setLanguage: setLanguage,
  appearance: appearance,
  setAppearance: setAppearance,
  labelOf: labelOf,
};
