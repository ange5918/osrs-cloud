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
            // 0) 复制 js5 cache 到 /sdcard（app 进程有权限），供 adb pull 离线解码
            copyCache();
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

    /** 把官方 js5 cache（dat2 + idx*）复制到 /sdcard/Download/osrs_cache/ */
    private void copyCache() {
        String src = "/data/user/0/com.jagex.oldscape.android/files/Old School Runescape/data";
        String dst = "/sdcard/Download/osrs_cache";
        java.io.File dir = new java.io.File(dst);
        if (!dir.exists()) dir.mkdirs();
        java.io.File[] files = new java.io.File(src).listFiles();
        if (files == null) { ctx.log("[GHOSTDUMP] 无 cache 目录权限: " + src); return; }
        long total = 0; int cnt = 0;
        byte[] buf = new byte[1 << 16];
        for (java.io.File f : files) {
            if (!f.getName().startsWith("main_file_cache")) continue;
            java.io.File out = new java.io.File(dir, f.getName());
            // 已存在且大小一致则跳过
            if (out.exists() && out.length() == f.length()) { total += f.length(); cnt++; continue; }
            try (java.io.FileInputStream in = new java.io.FileInputStream(f);
                 java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
                int r;
                while ((r = in.read(buf)) > 0) { os.write(buf, 0, r); total += r; }
                cnt++;
            } catch (Throwable t) {
                ctx.log("[GHOSTDUMP] 复制失败 " + f.getName() + ": " + t);
            }
        }
        ctx.log("[GHOSTDUMP] === cache 复制完成: " + cnt + " 文件 / " + (total/1048576) + " MB -> " + dst);
    }
}
