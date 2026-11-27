// @desc MyPlugin 杀鸡脚本(class 式): 坐标框内找鸡→Attack, 攻击后休息, 卡住重走
// @version 2.1
// 悬浮窗下载本文件 -> 游戏内容器 eval -> 每 tick 调 MyPlugin.onLoop()
// 容器内 dex 类型一律用 Object 承接(BeanShell 在 Android 不能解析具体 dex 类型字段),
// 方法靠动态反射调用, 逻辑不变。

class MyPlugin {
    int tickLocal = 0;
    boolean resting = false;
    int restLeft = 0;
    Object lastPos = null;
    int stuckTicks = 0;

    // 杀鸡区域：左下角 (3173,3289,0)，宽 12 高 15
    int MINE_MINX = 3173;
    int MINE_MAXX = 3173 + 12 - 1;
    int MINE_MINY = 3289;
    int MINE_MAXY = 3289 + 15 - 1;
    int MINE_PLANE = 0;

    public boolean inMine(int x, int y, int plane) {
        return plane == MINE_PLANE && x >= MINE_MINX && x <= MINE_MAXX
                && y >= MINE_MINY && y <= MINE_MAXY;
    }

    public void onLoop() {
        tickLocal = tickLocal + 1;

        String p = api.me();
        if (p == null || p.length() == 0) {
            if (tickLocal <= 3 || tickLocal % 60 == 0) log("[诊断] 未获取到本地坐标，跳过");
            return;
        }
        String[] pp = p.split(",");
        int px = Integer.parseInt(pp[0].trim());
        int py = Integer.parseInt(pp[1].trim());
        int plane = pp.length > 2 ? Integer.parseInt(pp[2].trim()) : 0;

        boolean moving = api.moving();
        int interacting = api.query("F_INTERACTING");
        int idle = api.query("F_IDLE");

        // 每 10 tick 无条件输出真值
        if (tickLocal % 10 == 0) {
            log("BBBB : targeting=" + (interacting > 0) + " , moving=" + moving
                + " , idle=" + (idle > 0) + " , me=" + p + " , t=" + tickLocal);
        }

        // 休息中
        if (resting) {
            restLeft = restLeft - 1;
            if (restLeft > 0) return;
            resting = false;
        }

        // 卡住检测(Object 动态调 getX/getY)
        if (lastPos != null && ((Integer) invoke(lastPos, "getX")) == px
                && ((Integer) invoke(lastPos, "getY")) == py && moving) {
            stuckTicks = stuckTicks + 1;
        } else {
            stuckTicks = 0;
        }
        try { lastPos = client.getLocalPlayerLocation(); } catch (Throwable ignore) { }

        // 正在攻击/移动/非空闲 → 不抢操作
        if (interacting > 0 || moving || idle == 0) return;

        if (!inMine(px, py, plane)) {
            if (tickLocal % 5 == 0) {
                log("走回鸡区 me=" + p + " -> 3179,3296 stuck=" + stuckTicks);
                api.walk_once(3179, 3296, plane);
            }
            return;
        }

        Object best = null;
        int bestDist = 999999;
        try {
            java.util.List npcs = client.getNpcs();
            for (int i = 0; i < npcs.size(); i++) {
                Object n = npcs.get(i);
                if (n == null) continue;
                String nm = (String) invoke(n, "getName");
                if (nm == null || !nm.contains("Chicken")) continue;
                Object loc = invoke(n, "getWorldLocation");
                if (loc == null) continue;
                int lx = ((Integer) invoke(loc, "getX")).intValue();
                int ly = ((Integer) invoke(loc, "getY")).intValue();
                int lp = ((Integer) invoke(loc, "getPlane")).intValue();
                if (!inMine(lx, ly, lp)) continue;
                int d = Math.abs(lx - px) + Math.abs(ly - py);
                if (d < bestDist) { bestDist = d; best = n; }
            }
        } catch (Throwable t) {
            log("扫描 NPC 异常: " + t);
        }

        if (best != null) {
            String nm = (String) invoke(best, "getName");
            Object loc = invoke(best, "getWorldLocation");
            int bx = ((Integer) invoke(loc, "getX")).intValue();
            int by = ((Integer) invoke(loc, "getY")).intValue();
            int idx = ((Integer) invoke(best, "getIndex")).intValue();
            log("Attack " + nm + " @" + bx + "," + by + " idx=" + idx + " dist=" + bestDist);
            api.npc_option(nm, "Attack");
            resting = true;
            restLeft = 4 + (int)(Math.random() * 2);
        } else {
            if (tickLocal % 10 == 0) log("鸡区内暂无可攻击的鸡，等待… stuck=" + stuckTicks);
        }
    }

    // 反射调用, 规避 BeanShell 对 dex 具体类型的静态解析
    Object invoke(Object obj, String method) {
        try {
            return obj.getClass().getMethod(method).invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    public void onStop() {
        log("MyPlugin 已停止，共运行 " + tickLocal + " tick");
    }
}
