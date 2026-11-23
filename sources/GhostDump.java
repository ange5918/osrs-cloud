package ghostdump;

import com.astrastudio.api.Plugin;
import com.astrastudio.api.annotation.PluginDescriptor;
import com.astrastudio.api.annotation.Subscribe;
import com.astrastudio.api.event.GameTick;
import com.astrastudio.runtime.internal.NativeBridge;

/**
 * 从官方客户端内存导出 def 真值 + 定位 js5 cache 文件。
 * 目的：验证去 Ghost 依赖的数据源可行性。一次性诊断脚本。
 */
@PluginDescriptor(
    name = "Ghost数据导出诊断",
    description = "内存读 NPC def 真名 + 列出官方 cache 文件",
    tags = {"诊断", "def"},
    enabledByDefault = false
)
public final class GhostDump implements Plugin {
    private Plugin.PluginContext ctx;
    private boolean done = false;

    public void configure(Plugin.PluginContext context) {
        this.ctx = context;
    }

    public void startUp() {
        ctx.log("[GHOSTDUMP] 启动，等待登录后导出");
    }

    public void shutDown() {
        ctx.log("[GHOSTDUMP] 停止");
    }

    @Subscribe
    public void onTick(GameTick tick) {
        if (done || tick.getTick() < 20) return;
        done = true;
        try {
            // 1) 列私有目录，找 js5 cache 文件
            String files = NativeBridge.nativeListPrivateFiles();
            ctx.log("[GHOSTDUMP] === 官方私有目录 ===");
            if (files == null) {
                ctx.log("[GHOSTDUMP] 列目录返回 null（app 进程无权？）");
            } else {
                for (String line : files.split("\n")) {
                    if (line.length() > 0) ctx.log("[GHOSTDUMP] FILE " + line);
                }
            }

            // 2) 遍历已加载 NPC def，读 id + 真名
            int[] defs = NativeBridge.nativeListLoadedNpcDefs(2048);
            ctx.log("[GHOSTDUMP] 已加载 NPC def 条数=" + (defs == null ? 0 : defs.length / 3));
            if (defs != null) {
                int shown = 0;
                for (int i = 0; i + 2 < defs.length; i += 3) {
                    String rn = NativeBridge.nativeReadDefName(defs[i], defs[i + 1]);
                    if (rn != null && shown++ < 40) {
                        ctx.log("[GHOSTDUMP] DEF " + rn);
                    }
                }
            }
        } catch (Throwable t) {
            ctx.log("[GHOSTDUMP] 异常: " + t);
        }
    }
}
