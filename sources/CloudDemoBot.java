package cloudbot;

import com.astrastudio.api.Plugin;
import com.astrastudio.api.annotation.PluginDescriptor;
import com.astrastudio.api.annotation.Subscribe;
import com.astrastudio.api.event.GameTick;
import com.astrastudio.api.model.Client;
import com.astrastudio.api.model.WorldPoint;

@PluginDescriptor(
    name = "云端演示脚本",
    description = "从云端 dex 动态加载的验证脚本",
    tags = {"云端", "演示"},
    enabledByDefault = true
)
public final class CloudDemoBot implements Plugin {
    private PluginContext ctx;
    private Client client;
    private long counter = 0;

    public void configure(PluginContext context) {
        this.ctx = context;
        this.client = context.getClient();
    }

    private boolean walked = false;

    public void startUp() {
        ctx.log("[云端脚本] ★★★ 我是从云端 dex 动态加载并运行成功的! ★★★");
        ctx.log("[云端脚本] version=1.2 新增 socket 直发走路验证");
        walked = false;
    }

    /** 启动后延迟约 30 tick 走一步（向东 3 格），验证 native 发包 */
    private void tryWalkDemo(long tick) {
        if (walked || tick < 30) return;
        WorldPoint me = client.getLocalPlayerLocation();
        if (me == null) return;
        walked = true;
        int tx = me.getX() + 3, ty = me.getY();
        ctx.log("[云端脚本] 走路测试: (" + me.getX() + "," + me.getY() + ") -> (" + tx + "," + ty + ")");
        client.walkTo(tx, ty);   // socket 直发，日志会打印 OK/FAIL + fd
    }

    public void shutDown() {
        ctx.log("[云端脚本] 已停止, 共 " + counter + " tick");
    }

    @Subscribe
    public void onTick(GameTick tick) {
        counter++;
        if (tick.getTick() % 100 != 0) return;
        WorldPoint me = client.getLocalPlayerLocation();
        int npcCount = 0;
        try { npcCount = client.getNpcs().size(); } catch (Throwable ignore) {}
        ctx.log("[云端脚本] 心跳 tick=" + tick.getTick()
            + " 坐标=" + (me == null ? "未登录" : me.getX()+","+me.getY()+","+me.getPlane())
            + " 登录=" + client.isLoggedIn()
            + " NPC=" + npcCount);
    }
}
