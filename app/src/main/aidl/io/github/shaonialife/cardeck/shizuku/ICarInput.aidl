// Shizuku UserService 接口：跑在常驻 shell 进程中，以毫秒级延迟注入触摸事件。
// 相比每次 spawn `input` 命令（约 200-400ms），injectInputEvent 直连系统服务。
package io.github.shaonialife.cardeck.shizuku;

import android.os.ParcelFileDescriptor;
import android.view.Surface;

interface ICarInput {
    boolean tap(int displayId, int x, int y);
    boolean swipe(int displayId, int startX, int startY, int endX, int endY, int durationMs);

    // 连续拖拽原语：整段滑动共用一次 DOWN，MOVE 逐事件直注（真实时间戳），
    // 应用侧速度跟踪器才能算出正确的滚动惯性。一次只支持单指。
    boolean pointerDown(int displayId, int x, int y);
    boolean pointerMove(int displayId, int x, int y);
    boolean pointerUp(int displayId, int x, int y);

    // 方控/旋钮原语（2026-10-23）：按键 down+up 与滚轮滚动（ACTION_SCROLL/AXIS_VSCROLL）。
    // key 用于确认/返回/语音助手；scroll 的 vSteps 有符号，语义同 AXIS_VSCROLL（正=向下滚）。
    boolean key(int displayId, int keyCode);
    boolean scroll(int displayId, int x, int y, float vSteps);

    // 受信虚拟屏（shell 权限）：第三方应用的新任务可落在其上，
    // 解决「应用内跳转的任务被重定向到手机主屏」问题（AOSP 安全策略）。
    // trusted=false：不声明 FLAG_TRUSTED——第三方任务无法被系统安置到该屏
    // （dock 屏只承载自有 DockActivity，去掉受信可杜绝其它应用任务逃逸上来）。
    int createDisplay(String name, int width, int height, int dpi, in Surface surface, boolean trusted);
    boolean releaseDisplay(int displayId);

    // 动态调整虚拟屏尺寸（创建者才可调）：屏上应用的配置变更由系统派发，
    // 应用自行 relayout——地图屏「分屏 ⇄ 全屏」切换即用此能力（任务不动，状态不丢）。
    boolean resizeDisplay(int displayId, int width, int height, int dpi);

    boolean ping();
    String diag();

    // 服务进程 PID：应用侧清理「非当前绑定进程」的陈旧 UserService 时用于排除自己
    // （旧进程持有的虚拟屏跨进程无法 release，杀进程是唯一释放途径，见 CarInputClient）。
    int pid();

    // ---- 播放音频采集（免 MediaProjection 授权）----
    // 以 shell uid 的 CAPTURE_AUDIO_OUTPUT 权限直接采 REMOTE_SUBMIX，
    // 返回管道读端（服务侧持续写入采集到的 PCM），调用方读完即得音频流。
    // 采样参数须与 SETUP 协商结果一致；bufferBytes 为采集缓冲提示。
    ParcelFileDescriptor startAudioCapture(int sampleRate, int channelMask, int bufferBytes);
    void stopAudioCapture();
}
