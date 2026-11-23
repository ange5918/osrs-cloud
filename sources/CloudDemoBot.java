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

    private boolean attacked = false;

    public void startUp() {
        ctx.log("[云端脚本] ★★★ 我是从云端 dex 动态加载并运行成功的! ★★★");
        ctx.log("[云端脚本] version=1.4 interact 发包验证");
        attacked = false;
    }

    /** 启动后打印 NPC 诊断并对第一只 Chicken 发 attack */
    private void diagnoseAndAttack(long tick) {
        if (attacked || tick < 30) return;
        attacked = true;
        java.util.List<com.astrastudio.api.model.NPC> npcs = client.getNpcs();
        ctx.log("[云端脚本] NPC 总数=" + npcs.size());
        com.astrastudio.api.model.NPC target = null;
        int shown = 0;
        for (com.astrastudio.api.model.NPC n : npcs) {
            if (shown++ < 8) {
                com.astrastudio.api.model.WorldPoint p = n.getWorldLocation();
                ctx.log("[云端脚本] NPC name=" + n.getName() + " id=" + n.getId()
                    + " idx=" + n.getIndex() + " pos=" + (p==null?"?":p.getX()+","+p.getY()));
            }
            if ("Chicken".equals(n.getName()) && target == null) target = n;
        }
        if (target != null) {
            ctx.log("[云端脚本] >>> 对 Chicken idx=" + target.getIndex() + " 发 Attack 包");
            client.interactNpc(target, "Attack");
            ctx.log("[云端脚本] >>> Attack 发包已调用");
        } else {
            ctx.log("[云端脚本] >>> 未找到 Chicken（诊断上面）");
        }
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
        diagnoseAndAttack(tick.getTick());
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
