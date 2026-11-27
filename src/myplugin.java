package myplugin;

import com.ghost.api.Declaration;
import com.ghost.api.Kind;
import com.ghost.api.LoopedPlugin;
import com.ghost.api.entities.NPC;
import com.ghost.api.entities.NPCs;
import com.ghost.api.entities.Player;
import com.ghost.api.entities.Players;
import com.ghost.api.movement.Coord;
import com.ghost.api.movement.Walker;

/**
 * MyPlugin —— 标准 Ghost 插件（class 式，设备端编译成 dex 运行）。
 * 杀鸡区域：左下角 (3173,3289,0)，宽 12 高 15。
 * 悬浮窗下载本文件 -> 手机端 ecj+dx 编译成 dex -> 容器每 tick 调 onLoop()。
 */
final class myplugin extends LoopedPlugin {

    @Override public Kind kind() { return Kind.MAIN; }
    @Override public String name() { return "myplugin"; }
    @Override public String description() { return "杀鸡脚本(设备端编译 dex 版)"; }
    @Override public String version() { return "3.0"; }

    @Override public Declaration declare() {
        return Declaration.none().withNpcs().withWorld().withVars();
    }

    private static final int MINX = 3173, MINY = 3289, MAXX = 3184, MAXY = 3303, PLANE = 0;
    private int tick = 0;

    private boolean inMine(Coord c) {
        return c != null && c.floor() == PLANE && c.x() >= MINX && c.x() <= MAXX
                && c.y() >= MINY && c.y() <= MAXY;
    }

    @Override public void onLoop() {
        tick++;
        // BBBB 心跳: 每 10 tick 无条件输出一次, 证明 onLoop 在循环
        if (tick % 10 == 0) log("BBBB : tick=" + tick);

        java.util.Optional<Player> meOpt = Players.getLocal();
        if (!meOpt.isPresent()) {
            if (tick <= 3 || tick % 60 == 0) log("未获取到本地玩家，跳过 tick=" + tick);
            return;
        }
        Player me = meOpt.get();
        Coord here = me.coord();

        // 不在鸡区: 走回去
        if (!inMine(here)) {
            if (tick % 20 == 0) log("走回鸡区 " + here + " -> 3179,3296");
            Walker.walkTo(3179, 3296, PLANE);
            return;
        }

        // 框内找最近的鸡
        NPC best = null;
        int bestDist = Integer.MAX_VALUE;
        for (NPC n : NPCs.getAll()) {
            if (n == null) continue;
            String nm = n.name().orElse("");
            if (nm.toLowerCase().indexOf("chicken") < 0) continue;
            Coord c = n.coord();
            if (!inMine(c)) continue;
            int d = Math.abs(c.x() - here.x()) + Math.abs(c.y() - here.y());
            if (d < bestDist) { bestDist = d; best = n; }
        }
        if (best == null) {
            if (tick % 10 == 0) log("鸡区内暂无可攻击的鸡");
            return;
        }
        if (tick % 10 == 0 || tick % 3 == 0) {
            log("Attack " + best.name().orElse("?") + " idx=" + best.index() + " dist=" + bestDist);
        }
        // 高层 API 攻击
        best.attack();
    }

    @Override public void onStop() {
        log("MyPlugin 已停止，共运行 " + tick + " tick");
    }
}
