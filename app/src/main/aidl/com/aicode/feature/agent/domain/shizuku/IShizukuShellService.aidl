// Shizuku UserService 接口：运行在 shell（uid 2000）进程，代 App 执行 shell 命令。
// 返回 Bundle：outputFd(ParcelFileDescriptor) 完整输出、exitCode(int) 退出码（超时/异常为负值）；
// 异常时退化为 output(String) 错误信息。输出走 FD 以绕开 Binder 事务的 1MB 上限。
package com.aicode.feature.agent.domain.shizuku;

import android.os.Bundle;

interface IShizukuShellService {
    // Shizuku 保留的销毁入口：事务号固定（aidl 写 16777114，服务端实际按 16777115 调用），
    // unbindUserService(remove=true) 时触发，实现里应结束进程。不可改动。
    void destroy() = 16777114;

    Bundle exec(String command, int timeoutMs) = 1;
}