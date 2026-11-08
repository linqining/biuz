// 004-连接中 / 005-连接失败（同页两态，与 page-index 的 building 约定一致）
//
// web 权威源：
//   004 packages/web/src/web-remote-control/ui/webRemoteControlConnectingScreen.tsx:82
//   005 packages/web/src/web-remote-control/ui/webRemoteControlFailureScreen.tsx:46
//       + domain/webRemoteControlScreenCopy.ts:16-141（12 种原因文案）
//
// 入参（连接层接入后由上一页带过来；acceptance 缝用于实拍）：
//   ?mode=connecting&name=<设备名>&state=<connecting|authenticating|waiting|paired|reconnecting|suspended>
//   ?mode=failure&reason=<12 选一>&detail=<服务端原文>
const model = require('../../utils/connection-screen-model.js');

Page({
  data: {
    mode: 'connecting',
    statusBarHeight: 0,
    // connecting
    title: model.CONNECTING.connectingFallback,
    stepIndex: 0,
    steps: model.CONNECTING.steps.map(function (label, index) {
      return { label: label, index: index, state: index === 0 ? 'active' : 'pending' };
    }),
    stageLabel: model.CONNECTING.stages.other,
    stageDescription: '',
    // failure
    copy: null,
    detailLabel: '',
    detail: '',
    nextStepsTitle: model.NEXT_STEPS_TITLE,
  },

  onLoad(query) {
    const params = query || {};
    let statusBarHeight = 0;
    try {
      const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
      statusBarHeight = info.statusBarHeight || 0;
    } catch (err) {
      statusBarHeight = 0;
    }

    if (params.mode === 'failure') {
      const copy = model.failureCopy(params.reason);
      this.setData({
        statusBarHeight: statusBarHeight,
        mode: 'failure',
        copy: copy,
        detailLabel: copy.detailLabel,
        detail: params.detail || '',
      });
      return;
    }

    this.setData({
      statusBarHeight: statusBarHeight,
      mode: 'connecting',
      title: model.connectingTitle(params.name || ''),
    });
    this.applyStage(params.state || 'connecting');
  },

  /** 连接层推送阶段时调用（transportState 变化 → 进度与文案）。 */
  applyStage(state) {
    const stage = model.connectingStage(state);
    const steps = model.CONNECTING.steps.map(function (label, index) {
      let stepState = 'pending';
      if (stage.stepIndex >= 0) {
        if (index < stage.stepIndex) stepState = 'done';
        else if (index === stage.stepIndex) stepState = 'active';
      }
      return { label: label, index: index, state: stepState };
    });
    this.setData({
      stepIndex: stage.stepIndex,
      steps: steps,
      stageLabel: stage.label,
      stageDescription: stage.description,
    });
  },

  /** 失败态入口：连接层捕获错误后调用，reason 取 12 种之一。 */
  showFailure(reason, detail) {
    const copy = model.failureCopy(reason);
    this.setData({
      mode: 'failure',
      copy: copy,
      detailLabel: copy.detailLabel,
      detail: detail || '',
    });
  },

  onPrimaryAction() {
    // 重新连接/重试/重新加载：回落设备列表由连接层决定；此页只负责触发
    this.triggerEvent && this.triggerEvent('retry');
    wx.navigateBack({ delta: 1, fail: function () { wx.reLaunch({ url: '/pages/devices/devices' }); } });
  },
});
