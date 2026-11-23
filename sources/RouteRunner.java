package routerunner;

import com.astrastudio.api.Plugin;
import com.astrastudio.api.annotation.PluginDescriptor;
import com.astrastudio.api.annotation.Subscribe;
import com.astrastudio.api.event.GameTick;
import com.astrastudio.api.model.Client;
import com.astrastudio.api.model.WorldPoint;

/**
 * 官方 Navigator 真寻路（复刻 PC Ghost 链路）：鸡区 -> L2 -> GE2。
 * 路线由 tools/official_navigator.py（官方 collision.astar3 + transports/doors）离线规划，
 * 执行用 native sendPacket（已修复重连 fd 失效 + ISAAC 串行）。
 * 楼梯/门交互按 Ghost rev242 send240 真值构造 payload。
 */
@PluginDescriptor(name = "寻路L2-GE2", description = "官方A*路线 鸡区->L2->GE2", enabledByDefault = true)
public final class RouteRunner implements Plugin {
    private PluginContext ctx;
    private Client client;

    private static final String[] LEG_TO_L2 = {
        "W:3185,3291,0","W:3180,3285,0","W:3181,3277,0","W:3181,3269,0","W:3187,3261,0",
        "W:3189,3253,0","W:3190,3245,0","W:3194,3237,0","W:3195,3229,0","W:3197,3221,0",
        "W:3202,3217,0","W:3208,3212,0","W:3205,3209,0",
        "I:3205,3209,0,16671,CU",
        "W:3205,3209,1",
        "I:3205,3209,1,16672,CU",
        "W:3205,3209,2","W:3206,3217,2","W:3208,3219,2"
    };
    private static final String[] LEG_TO_GE2 = {
        "W:3208,3219,2","W:3205,3212,2","W:3205,3209,2",
        "I:3205,3209,2,16673,CD",
        "W:3206,3208,1",
        "I:3206,3208,1,16672,CD",
        "W:3206,3208,0","W:3205,3214,0","W:3200,3218,0","W:3199,3224,0","W:3199,3232,0",
        "W:3202,3240,0","W:3206,3248,0","W:3208,3252,0","W:3215,3259,0","W:3223,3260,0",
        "W:3231,3261,0","W:3239,3261,0","W:3240,3268,0","W:3238,3276,0","W:3237,3284,0",
        "W:3238,3292,0","W:3238,3300,0","W:3233,3307,0","W:3225,3313,0","W:3219,3321,0",
        "W:3215,3328,0","W:3209,3336,0","W:3204,3344,0","W:3205,3352,0","W:3210,3360,0",
        "W:3211,3368,0","W:3207,3376,0","W:3208,3384,0","W:3208,3392,0","W:3210,3400,0",
        "W:3207,3408,0","W:3210,3416,0","W:3208,3424,0","W:3207,3432,0","W:3212,3440,0",
        "W:3212,3448,0","W:3212,3456,0","W:3209,3464,0","W:3213,3472,0",
        "I:3213,3472,0,11807,CU",
        "W:3213,3476,1","W:3220,3472,1","W:3223,3472,1",
        "I:3223,3472,1,11801,CU",
        "W:3223,3472,2","W:3217,3471,2","W:3209,3473,2","W:3202,3474,2","W:3203,3473,2"
    };

    private int leg = 0;          // 0=toL2, 1=toGE2, 2=done
    private int step = 0;
    private long stepStartTick = -1;
    private long lastWalkTick = -1;
    private long lastLogTick = -1;
    private int stuckTicks = 0;
    private int lastPlane = -1;
    private int interactTries = 0;
    private long lastInteractTick = -1;
    private int lastX = -1, lastY = -1;
    private boolean finished = false;

    private static final int TOL = 3;          // 到达容差(格)
    private static final int WALK_GAP = 30;    // walk 重发间隔(cycle, 20ms -> 600ms=1游戏tick)
    private static final int INTERACT_GAP = 40;
    private static final int STUCK_LIMIT = 200;

    public void configure(PluginContext context) {
        this.ctx = context;
        this.client = context.getClient();
    }

    public void startUp() {
        leg = 0; step = 0; stepStartTick = -1; lastWalkTick = -1;
        lastLogTick = -1; stuckTicks = 0; lastPlane = -1; interactTries = 0;
        finished = false; lastX = -1; lastY = -1;
        ctx.log("[导航] 启动 鸡区->L2->GE2 (官方A*路点)");
    }

    public void shutDown() { ctx.log("[导航] 停止"); }

    @Subscribe
    public void onTick(GameTick tick) {
        if (finished) return;
        if (!client.isLoggedIn()) return;
        WorldPoint me = client.getLocalPlayerLocation();
        if (me == null) return;
        long t = client.getTickCount();
        if (stepStartTick < 0) { stepStartTick = t; lastPlane = me.getPlane(); }

        String[] route = (leg == 0) ? LEG_TO_L2 : LEG_TO_GE2;
        if (step >= route.length) {
            if (leg == 0) {
                ctx.log("[导航] ★到达 L2 " + me + " → 切换 GE2 段");
                leg = 1; step = 0; stepStartTick = t; interactTries = 0; stuckTicks = 0;
                route = LEG_TO_GE2;
            } else {
                ctx.log("[导航] ★★★到达终点 " + me + " 全部完成！");
                finished = true;
                return;
            }
        }

        // 心跳
        if (lastLogTick < 0 || t - lastLogTick >= 50) {
            lastLogTick = t;
            ctx.log("[导航] pos=" + me.getX() + "," + me.getY() + ",p" + me.getPlane()
                    + " 段" + leg + " 步" + step + "/" + route.length);
        }

        String instr = route[step];
        boolean isWalk = instr.startsWith("W:");
        String[] f = instr.substring(2).split(",");
        int tx = Integer.parseInt(f[0]);
        int ty = Integer.parseInt(f[1]);
        int tp = Integer.parseInt(f[2]);

        if (isWalk) {
            handleWalk(t, me, tx, ty, tp);
        } else {
            int oid = Integer.parseInt(f[3]);
            String act = f[4];
            handleInteract(t, me, tx, ty, tp, oid, act);
        }
    }

    private void handleWalk(long t, WorldPoint me, int tx, int ty, int tp) {
        int dx = tx - me.getX(), dy = ty - me.getY();
        int dist = (int)Math.sqrt(dx*dx + dy*dy);
        // plane 变化或进入容差 -> 完成本步
        if (me.getPlane() == tp && dist <= TOL) {
            ctx.log("[导航] ✓walk到 (" + tx + "," + ty + ",p" + tp + ") 实际" + me.getX()+","+me.getY()+",p"+me.getPlane());
            step++; stepStartTick = t; stuckTicks = 0; lastWalkTick = -1; interactTries = 0;
            return;
        }
        // 卡住检测
        if (me.getX() == lastX && me.getY() == lastY) stuckTicks++; else stuckTicks = 0;
        lastX = me.getX(); lastY = me.getY();
        if (stuckTicks > STUCK_LIMIT) {
            // 侧移绕障：垂直方向偏移 4 格
            int sx = tx, sy = ty;
            if (Math.abs(dx) > Math.abs(dy)) sy = me.getY() + (dy >= 0 ? 4 : -4);
            else sx = me.getX() + (dx >= 0 ? 4 : -4);
            ctx.log("[导航] ↻卡住 侧移到 (" + sx + "," + sy + ")");
            client.walkTo(sx, sy);
            stuckTicks = 0; lastWalkTick = t;
            return;
        }
        if (lastWalkTick < 0 || t - lastWalkTick >= WALK_GAP) {
            client.walkTo(tx, ty);
            lastWalkTick = t;
        }
    }

    private void handleInteract(long t, WorldPoint me, int tx, int ty, int tp, int oid, String act) {
        // 先确保走到楼梯格附近
        int dx = tx - me.getX(), dy = ty - me.getY();
        int dist = (int)Math.sqrt(dx*dx + dy*dy);
        if (dist > TOL) {
            if (lastWalkTick < 0 || t - lastWalkTick >= WALK_GAP) {
                client.walkTo(tx, ty);
                lastWalkTick = t;
            }
            return;
        }
        // plane 已变 -> 交互成功
        if (me.getPlane() > tp) {
            ctx.log("[导航] ✓楼梯上楼成功 p" + tp + "->p" + me.getPlane() + " obj=" + oid);
            step++; stepStartTick = t; interactTries = 0; lastWalkTick = -1; stuckTicks = 0;
            return;
        }
        if (me.getPlane() < tp) {
            ctx.log("[导航] ✓楼梯下楼成功 p" + tp + "->p" + me.getPlane() + " obj=" + oid);
            step++; stepStartTick = t; interactTries = 0; lastWalkTick = -1; stuckTicks = 0;
            return;
        }
        // 发交互包
        if (lastInteractTick < 0 || t - lastInteractTick >= INTERACT_GAP) {
            lastInteractTick = t;
            interactTries++;
            sendObjectPacket(oid, tx, ty, act, interactTries);
            ctx.log("[导航] ↑交互#"+interactTries+" obj="+oid+" "+act+" @("+tx+","+ty+") me="+me.getX()+","+me.getY()+",p"+me.getPlane());
        }
    }

    /**
     * 按 Ghost rev242 send240 真值构造 object 交互包。
     * 单动作楼梯(op1): opcode 88, 9字节 [xX,hiX,hiY,loY,0,loId,hiId,b2Id,0]
     * 多动作楼梯(op2/op3): 用旧格式 opcode 103, 8字节 [hiX,xX,hiY,xY,xId,hiId,opt,0x80]
     */
    private void sendObjectPacket(int oid, int x, int y, String act, int tryNo) {
        int lo = oid & 0xff, hi = (oid >> 8) & 0xff, b2 = (oid >> 16) & 0xff;
        int X = x & 0xffff, Y = y & 0xffff;
        int xX = (X & 0xff) ^ 0x80, hiX = (X >> 8) & 0xff;
        int hiY = (Y >> 8) & 0xff, loY = Y & 0xff, xY = (Y & 0xff) ^ 0x80;
        // 16672 是多动作楼梯：CU=slot1(op2), CD=slot2(op3)
        boolean multi = (oid == 16672);
        if (multi) {
            int opt = act.equals("CD") ? 2 : 1;
            byte[] p = new byte[]{(byte)hiX,(byte)xX,(byte)hiY,(byte)xY,(byte)(lo^0x80),(byte)hi,(byte)opt,(byte)0x80};
            client.sendPacket(103, p);
        } else {
            // 单动作楼梯 op1
            byte[] p = new byte[]{(byte)xX,(byte)hiX,(byte)hiY,(byte)loY,0,(byte)lo,(byte)hi,(byte)b2,0};
            client.sendPacket(88, p);
        }
    }
}
