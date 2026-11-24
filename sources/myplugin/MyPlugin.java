package myplugin;

import com.astrastudio.api.Plugin;
import com.astrastudio.api.annotation.PluginDescriptor;
import com.astrastudio.api.annotation.Subscribe;
import com.astrastudio.api.event.GameTick;
import com.astrastudio.api.model.Client;
import com.astrastudio.api.model.NPC;
import com.astrastudio.api.model.WorldPoint;

/**
 * MyPlugin —— AstraStudio 版（真正的云端 dex 脚本）。
 *
 * 从 Ghost 版 com.ghost.api.LoopedPlugin 搬迁到 com.astrastudio.api.Plugin：
 *   LoopedPlugin/declare/Walker/NPCs  ->  Plugin/@Subscribe/Client.walkTo/getNpcs/interactNpc
 * 状态机一字不改：走到鸡区 -> 找最近存活 Chicken -> Attack -> 休息。
 *
 * 由 CloudScriptPlugin 从云端下载 dex、DexClassLoader 加载、反射 @PluginDescriptor 后
 * 挂进运行中的框架。这是端上真正能启动的脚本（区别于旧的 ghost-source 源码模板）。
 */
@PluginDescriptor(
        name = "MyPlugin(杀鸡)",
        description = "AstraStudio 版杀鸡脚本：走到鸡区 → 找 Chicken 攻击 → 攻击后休息",
        tags = {"战斗", "脚本搬迁"},
        enabledByDefault = true
)
public final class MyPlugin implements Plugin {
    private PluginContext ctx;
    private Client client;

    private static final int AREA_MIN_X = 3173;
    private static final int AREA_MIN_Y = 3289;
    private static final int AREA_MAX_X = 3184;
    private static final int AREA_MAX_Y = 3303;
    private static final int PLANE = 0;
    private static final int LOOP_EVERY = 30;   // 20ms/tick -> ~600ms
    private static final int REST_LOOPS = 4;

    private static final String CMD_FILE = "/data/local/tmp/astra_fish.txt";

    private long loopCount = 0;
    private int restingLeft = 0;
    private int lastTargetId = -1;
    private boolean active = true;
    private String lastCmd = "";
    private int filePollCounter = 0;

    @Override
    public void configure(PluginContext context) {
        this.ctx = context;
        this.client = context.getClient();
    }

    @Override
    public void startUp() {
        active = true;
        ctx.log("[MyPlugin] 启动(AstraStudio版)，目标区 x[" + AREA_MIN_X + "," + AREA_MAX_X
                + "] y[" + AREA_MIN_Y + "," + AREA_MAX_Y + "]");
        ctx.log("[MyPlugin] 启停: echo stop > " + CMD_FILE + " 停 / echo start 启 / echo pos 查坐标");
    }

    @Override
    public void shutDown() {
        active = false;
        ctx.log("[MyPlugin] 已停止，共 " + loopCount + " tick，最后目标 Chicken#" + lastTargetId);
    }

    private void pollCommandFile() {
        if (++filePollCounter < 10) return;
        filePollCounter = 0;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(CMD_FILE))) {
            String line = r.readLine();
            if (line == null) return;
            line = line.trim();
            if (line.isEmpty() || line.equals(lastCmd)) return;
            lastCmd = line;
            if (line.equalsIgnoreCase("stop")) {
                active = false;
                ctx.log("[MyPlugin] 收到 stop → 已停止");
            } else if (line.equalsIgnoreCase("start")) {
                active = true;
                ctx.log("[MyPlugin] 收到 start → 已启动");
            } else if (line.equalsIgnoreCase("pos")) {
                WorldPoint me = client.getLocalPlayerLocation();
                ctx.log(me == null ? "[MyPlugin] 读不到坐标" : "[MyPlugin] 当前坐标 " + me);
            }
        } catch (java.io.FileNotFoundException e) {
            // 无指令，静默
        } catch (Exception e) {
            ctx.log("[MyPlugin] 读指令异常: " + e);
        }
    }

    @Subscribe
    public void onTick(GameTick tick) {
        pollCommandFile();
        if (!active) return;
        if (tick.getTick() % LOOP_EVERY != 0) return;
        if (!client.isLoggedIn()) return;
        loopCount++;

        WorldPoint me = client.getLocalPlayerLocation();
        if (me == null) {
            ctx.log("[MyPlugin] 读不到坐标（未登录/引擎未就绪），本轮跳过");
            return;
        }

        if (restingLeft > 0) { restingLeft--; return; }

        if (!inArea(me)) {
            client.walkTo((AREA_MIN_X + AREA_MAX_X) / 2, (AREA_MIN_Y + AREA_MAX_Y) / 2);
            ctx.log("[MyPlugin] 走向鸡区，当前 " + me);
            return;
        }

        NPC best = null;
        int bestDist = Integer.MAX_VALUE;
        for (NPC n : client.getNpcs()) {
            if (n == null || n.getName() == null) continue;
            if (!n.getName().contains("Chicken")) continue;
            if (n.getHealth() <= 0) continue;
            WorldPoint p = n.getWorldLocation();
            if (p == null) continue;
            int d = me.distanceTo(p);
            if (d > 20) continue;
            if (d < bestDist) { bestDist = d; best = n; }
        }

        if (best == null) {
            ctx.log("[MyPlugin] 区内暂无存活 Chicken");
            return;
        }

        client.interactNpc(best, "Attack");
        lastTargetId = best.getId();
        restingLeft = REST_LOOPS;
        ctx.log("[MyPlugin] 攻击 Chicken#" + lastTargetId + " (dist=" + bestDist + ")");
    }

    private static boolean inArea(WorldPoint p) {
        final int PAD = 3;
        return p.getPlane() == PLANE
                && p.getX() >= AREA_MIN_X - PAD && p.getX() <= AREA_MAX_X + PAD
                && p.getY() >= AREA_MIN_Y - PAD && p.getY() <= AREA_MAX_Y + PAD;
    }
}
