// 013-设置
//
// web 权威源：`packages/ui/src/SettingsPage.tsx:1928-2242`（19 个分区；手机端有效项见
// `settingsPageConfig.ts:259-266` —— remoteDevices/dataSync/computerUse 为桌面专属）。
// 移动端对位 Android `ui/screen/SettingsScreen.kt`：只保留本机可用的基础项；需要桌面数据的
// 项（用量/能力面）如实提示「需要连接桌面端」，不做假数据。
const layout = require('../../utils/layout.js');
const settings = require('../../utils/settings-store.js');

// 开发者模式隐藏入口：品牌行连点 7 次（与 iOS/Android 同口径）
const DEV_TAP_COUNT = 7;

Page({
  data: {
    statusBarHeight: 20,
    languageLabel: '',
    appearanceLabel: '',
    version: '1.0.0',
    showDeveloper: false,
  },

  onLoad(query) {
    this.acceptanceQuery = query || {};
    this.tapCount = 0;
    this.setData({
      statusBarHeight: layout.statusBarHeight(),
      languageLabel: settings.labelOf(settings.LANGUAGES, settings.language()),
      appearanceLabel: settings.labelOf(settings.APPEARANCES, settings.appearance()),
    });
  },

  onChooseLanguage() {
    wx.showActionSheet({
      itemList: settings.LANGUAGES.map(function (item) { return item.label; }),
      success: (res) => {
        const picked = settings.LANGUAGES[res.tapIndex];
        if (!picked) return;
        settings.setLanguage(picked.key);
        this.setData({ languageLabel: picked.label });
      },
    });
  },

  onChooseAppearance() {
    wx.showActionSheet({
      itemList: settings.APPEARANCES.map(function (item) { return item.label; }),
      success: (res) => {
        const picked = settings.APPEARANCES[res.tapIndex];
        if (!picked) return;
        settings.setAppearance(picked.key);
        this.setData({ appearanceLabel: picked.label });
      },
    });
  },

  // 需要桌面数据的只读面：未接连接层时如实提示（不伪造数值）
  onOpenUsage() {
    wx.showToast({ title: '需要连接桌面端', icon: 'none' });
  },

  onOpenCapability(event) {
    const kind = (event.currentTarget && event.currentTarget.dataset.kind) || 'memory';
    wx.navigateTo({ url: '/pages/capability/capability?kind=' + kind });
  },

  onOpenAbout() {
    wx.showModal({
      title: 'BiuZ',
      content: '版本 ' + this.data.version + '\nZCode 桌面端移动遥控台',
      showCancel: false,
    });
  },

  onBrandTap() {
    this.tapCount += 1;
    if (this.tapCount >= DEV_TAP_COUNT && !this.data.showDeveloper) {
      this.setData({ showDeveloper: true });
      wx.showToast({ title: '已开启开发者选项', icon: 'none' });
    }
  },
});
