// 朱批 小程序入口。
// 与 web 手机壳（ZCode/packages/ui/src/WebRemoteControlMobileShell.tsx）同构：
// 只有 home（pages/sessions）与 chat（pages/chat）两级主壳，设置/文件等为二级页。
// 冷启动不做假数据装配：未连接一律空态，连上桌面后由 RPC 回执填充。
App({
  globalData: {
    // 连接态由 utils/connection.js 管理；此处仅保存跨页只读快照
    connection: null,
  },

  onLaunch() {
    // 页面栈上限 10 层（微信限制）：超过后 reLaunch 而非 navigateTo，见 utils/nav.js

    // P-031 修复：启动即异步预取平台 CSPRNG 字节入池（wx.getRandomValues 只有异步回调形态）。
    // 不做阻塞；失败也不致命——真正写凭据时若池仍不足，会明确拒绝并提示，不会写弱密钥。
    try {
      const crypto = require('./utils/credential-crypto.js');
      if (crypto && typeof crypto.prefillEntropy === 'function') {
        crypto.prefillEntropy().catch(function (err) {
          console.warn('[朱批] CSPRNG 预取失败（凭据写入将拒绝而非降级）', err && err.code ? err.code : err);
        });
      }
    } catch (err) {
      console.warn('[朱批] CSPRNG 预取初始化异常', err);
    }

    // P-031：旧明文凭据（biuz.jwt / biuz.desktop）迁入加密容器。
    // 放在启动而非仅登录页：只连桌面、不进登录页的用户同样需要迁移，
    // 否则明文 token 会一直留在本地（且下一次写入清掉旧键时会永久丢失连接）。
    try {
      const store = require('./utils/credential-store.js');
      if (store && typeof store.migrateLegacyJwtAsync === 'function') {
        store.migrateLegacyJwtAsync().catch(function (err) {
          console.warn('[朱批] 旧明文 JWT 迁移失败（明文保留，下次启动重试）', err && err.code ? err.code : err);
        });
      }
      if (store && typeof store.migrateLegacyDesktopAsync === 'function') {
        store.migrateLegacyDesktopAsync().catch(function (err) {
          console.warn('[朱批] 旧明文桌面地址迁移失败（明文保留，下次启动重试）', err && err.code ? err.code : err);
        });
      }
    } catch (err) {
      console.warn('[朱批] 旧明文凭据迁移初始化异常', err);
    }
  },

  onError(err) {
    // 兜底：不静默吞错，交给 error-line 组件上屏（web 侧同口径）
    console.error("[朱批] app error", err);
  },
});
