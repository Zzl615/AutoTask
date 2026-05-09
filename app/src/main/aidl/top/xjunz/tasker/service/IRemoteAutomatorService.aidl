// IRemoteAutomatorService.aidl
package top.xjunz.tasker.service;

import top.xjunz.tasker.task.runtime.IRemoteTaskManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;
import java.util.List;
import top.xjunz.tasker.engine.dto.XTaskDTO;
import top.xjunz.tasker.task.runtime.ITaskCompletionCallback;
import android.os.ResultReceiver;

interface IRemoteAutomatorService {

    void connect(boolean acquireWakeLock, in ResultReceiver callback) = 1;

    long getStartTimestamp() = 2;

    boolean isConnected() = 4;

    IRemoteTaskManager getTaskManager() = 8;

    void setSystemTypefaceSharedMemory(in SharedMemory mem) = 9;

    void suppressResidentTaskScheduler(boolean suppress) = 10;

    void scheduleOneshotTask(long id, in ITaskCompletionCallback callback) = 11;

    void stopOneshotTask(long id) = 12;

    void setPremiumContextStoragePath(in String path) = 13;

    void loadPremiumContext() = 14;

    void acquireWakeLock() = 15;

    void releaseWakeLock() = 16;

    /**
     * 让主进程拿到当前焦点窗口节点树的压缩 JSON。
     * 在特权进程里调本地 uiAutomation.rootInActiveWindow + StableNodeInfo.freeze + AiNodeTreeCompactor。
     * 主进程拿到 JSON 后用 AiJson 反序列化为 AiUiSnapshot 即用。
     *
     * 这一条是 AI agent 在 Shizuku 模式下能"看见屏幕"的唯一新增 IPC 契约：
     * agent 的"执行动作"复用 scheduleOneshotTask，不再扩 AIDL。
     */
    String captureUiSnapshotJson(int maxNodes, int maxTextLen) = 17;

    oneway void destroy() = 16777114; // Destroy method defined by Shizuku server

}