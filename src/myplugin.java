// @desc myplugin 杀鸡脚本(源码容器版): 坐标框内找鸡→Attack, 攻击后休息, 卡住重走
// @version 1.0
// 说明: APK 内置 BeanShell 容器直接解释执行的 Java 源码。悬浮窗点本脚本 → 下载到本地 → 游戏内容器运行。
//       绑定: ctx / client / api / log() / print() / vars / name / version / tick
//       api: me() moving() query(field) npc_option(name,op) walk_once(x,y[,plane]) invoke_object_at widget press_button cast_on_npc

int tickLocal = 0;          // 本脚本自己的 tick 计数（tick 是容器全局 tick）
boolean resting = false;    // 攻击后休息中
int restLeft = 0;           // 剩余休息 tick
com.astrastudio.api.model.WorldPoint lastPos = null;
int stuckTicks = 0;

// 杀鸡区域：左下角 (3173,3289,0)，宽 12 高 15
int MINE_MINX = 3173;
int MINE_MAXX = 3173 + 12 - 1;
int MINE_MINY = 3289;
int MINE_MAXY = 3289 + 15 - 1;
int MINE_PLANE = 0;

boolean inMine(int x, int y, int plane) {
    return plane == MINE_PLANE && x >= MINE_MINX && x <= MINE_MAXX
            && y >= MINE_MINY && y <= MINE_MAXY;
}

void onLoad() {
    log("myplugin 源码容器已加载 v" + version + " —— 杀鸡区域 x[" + MINE_MINX + "," + MINE_MAXX
        + "] y[" + MINE_MINY + "," + MINE_MAXY + "]");
}

void onTick() {
    tickLocal = tickLocal + 1;
    tick = tick;  // 保持容器 tick 绑定可见

    String p = api.me();
    if (p == null || p.length() == 0) {
        if (tickLocal <= 3 || tickLocal % 60 == 0) log("[诊断 t=" + tickLocal + "] 未获取到本地坐标，跳过");
        return;
    }
    // 解析 "x,y,plane"
    String[] pp = p.split(",");
    int px = Integer.parseInt(pp[0].trim());
    int py = Integer.parseInt(pp[1].trim());
    int plane = pp.length > 2 ? Integer.parseInt(pp[2].trim()) : 0;

    boolean moving = api.moving();
    int interacting = api.query("F_INTERACTING");   // 正在攻击为真
    int idle = api.query("F_IDLE");

    // 诊断：无条件每 10 tick 输出真值（之前写在攻击分支里，空闲时永远打不出来）
    if (tickLocal % 10 == 0) {
        log("BBBB : targeting=" + (interacting > 0) + " , moving=" + moving
            + " , idle=" + (idle > 0) + " , me=" + p + " , t=" + tickLocal);
    }

    // 休息中：每 tick 递减，没到时间不找下一只
    if (resting) {
        restLeft = restLeft - 1;
        if (restLeft > 0) return;
        resting = false;
    }

    // 卡住检测：坐标没变且在移动 → 累加；换位置则清零
    if (lastPos != null && lastPos.getX() == px && lastPos.getY() == py && moving) {
        stuckTicks = stuckTicks + 1;
    } else {
        stuckTicks = 0;
    }
    try { lastPos = client.getLocalPlayerLocation(); } catch (Throwable ignore) { }

    // 正在攻击/移动/非空闲 → 不抢操作（BBBB 已上移为无条件输出）
    if (interacting > 0 || moving || idle == 0) {
        return;
    }

    if (!inMine(px, py, plane)) {
        // 不在鸡区 → 走回区域中心 (3179, 3296)
        if (tickLocal % 5 == 0) {
            log("走回鸡区 me=" + p + " -> 3179,3296 stuck=" + stuckTicks);
            api.walk_once(3179, 3296, plane);
        }
        return;
    }

    // 在鸡区：找最近的、名字含 Chicken 且没被别人攻击的 NPC
    com.astrastudio.api.model.NPC best = null;
    int bestDist = 999999;
    try {
        java.util.List npcs = client.getNpcs();
        for (int i = 0; i < npcs.size(); i++) {
            com.astrastudio.api.model.NPC n = (com.astrastudio.api.model.NPC) npcs.get(i);
            if (n == null) continue;
            String nm = n.getName();
            if (nm == null || !nm.contains("Chicken")) continue;
            com.astrastudio.api.model.WorldPoint loc = n.getWorldLocation();
            if (loc == null || !inMine(loc.getX(), loc.getY(), loc.getPlane())) continue;
            int d = Math.abs(loc.getX() - px) + Math.abs(loc.getY() - py);
            if (d < bestDist) { bestDist = d; best = n; }
        }
    } catch (Throwable t) {
        log("扫描 NPC 异常: " + t);
    }

    if (best != null) {
        String nm = best.getName();
        log("Attack " + nm + " @" + best.getWorldLocation().getX() + "," + best.getWorldLocation().getY()
            + " idx=" + best.getIndex() + " dist=" + bestDist);
        api.npc_option(nm, "Attack");
        resting = true;
        restLeft = 4 + (int)(Math.random() * 2);   // 4~5 tick
    } else {
        if (tickLocal % 10 == 0) log("鸡区内暂无可攻击的鸡，等待… stuck=" + stuckTicks);
    }
}

void onUnload() {
    log("myplugin 源码容器已卸载，共运行 " + tickLocal + " tick");
}
