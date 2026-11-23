package walktrial;

import com.astrastudio.api.Plugin;
import com.astrastudio.api.annotation.PluginDescriptor;
import com.astrastudio.api.annotation.Subscribe;
import com.astrastudio.api.event.GameTick;
import com.astrastudio.api.model.Client;
import com.astrastudio.api.model.WorldPoint;
import com.astrastudio.api.model.TileObject;

import java.util.List;

/**
 * 走路试炼：先验证单层 walk 包能否推动角色，再打印附近楼梯/门物体。
 * 走 L2 / GE2 是跨楼层长程，必须靠 object 交互；本脚本先把地基打实。
 */
@PluginDescriptor(name = "走路试炼", description = "验证walk包+打印楼梯门物体", enabledByDefault = true)
public final class WalkTrial implements Plugin {
    private PluginContext ctx;
    private Client client;
    private long lastLog = 0;
    private long startTick = -1;
    private WorldPoint startPos = null;

    // 阶段0: 短走验证(走到 start+4x)，每 40 tick 发一次，共 6 次
    private int walkTries = 0;
    private int lastSentX = -1, lastSentY = -1;

    public void configure(PluginContext context) {
        this.ctx = context;
        this.client = context.getClient();
    }

    public void startUp() {
        lastLog = 0; startTick = -1; startPos = null; walkTries = 0;
        ctx.log("[走路试炼] 启动 v1.0");
    }

    public void shutDown() { ctx.log("[走路试炼] 停止"); }

    @Subscribe
    public void onTick(GameTick tick) {
        if (!client.isLoggedIn()) return;
        long t = tick.getTick();
        WorldPoint me = client.getLocalPlayerLocation();
        if (me == null) return;
        if (startTick < 0) {
            startTick = t; startPos = me;
            ctx.log("[走路试炼] 起点 " + me + " tick=" + t);
            dumpObjects(me);
        }

        // 心跳每 50 tick
        if (t - lastLog >= 50) {
            lastLog = t;
            ctx.log("[走路试炼] pos=" + me.getX() + "," + me.getY() + "," + me.getPlane()
                    + " 距起点=" + me.distanceTo(startPos) + " tick=" + (t - startTick));
        }

        // 短走验证：起步后每 60 tick 发一次 walk 到 start+(4+tries*2) x 方向，观察是否真的移动
        if (walkTries < 6 && t - startTick >= 10 + walkTries * 60) {
            int tx = startPos.getX() + 4 + walkTries * 2;
            int ty = startPos.getY();
            ctx.log("[走路试炼] >>> walkTo(" + tx + "," + ty + ") 第" + (walkTries+1) + "次 @pos=" + me.getX()+","+me.getY());
            client.walkTo(tx, ty);
            walkTries++;
            if (walkTries == 6) {
                ctx.log("[走路试炼] 短走序列完成，等待观察最终坐标");
            }
        }
    }

    private void dumpObjects(WorldPoint me) {
        try {
            List<TileObject> objs = client.getTileObjects();
            ctx.log("[走路试炼] 场景物体总数=" + objs.size());
            int shown = 0;
            for (TileObject o : objs) {
                String n = o.getName();
                if (n == null) n = "";
                if (n.contains("Stair") || n.contains("Ladder") || n.contains("Door") || n.contains("Gate")) {
                    WorldPoint p = o.getWorldLocation();
                    ctx.log("[走路试炼] 楼梯/门 id=" + o.getId() + " name=" + n
                            + " @" + (p==null?"?":p.getX()+","+p.getY()+","+p.getPlane()));
                    if (++shown >= 20) break;
                }
            }
        } catch (Throwable e) {
            ctx.log("[走路试炼] dumpObjects异常: " + e);
        }
    }
}
