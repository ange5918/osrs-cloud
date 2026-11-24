package fish;

import com.astrastudio.api.Plugin;
import com.astrastudio.api.annotation.PluginDescriptor;
import com.astrastudio.api.annotation.Subscribe;
import com.astrastudio.api.event.GameTick;
import com.astrastudio.api.model.Client;
import com.astrastudio.api.model.NPC;
import com.astrastudio.api.model.Player;
import com.astrastudio.api.model.WorldPoint;

import java.util.function.Predicate;

/**
 * Fish —— Ghost 版杀鸡脚本翻译成 AstraStudio API 版。
 *
 * 【保留用法和方法】Ghost 版的方法名和调用流程一字未改，只是底层实现换成自研 Client：
 *   Players.getLocal()            -> localPlayer()          (client.getLocalPlayer)
 *   me.coord()                    -> coordOf(me)            (getLocalPlayerLocation)
 *   Combat.getAttackStyle()       -> getAttackStyle()       (见方法注释)
 *   Skills.getLevel(Skill.ATTACK) -> getLevel("Attack")     (client.getSkills)
 *   Dialog.isOpen/continueDialog  -> isDialogOpen/continueDialog
 *   Player.isTargetingNpc/isMoving/isIdle -> 同名 helper（用坐标变化+休息计数近似）
 *   Coord / Place.fromCorners     -> 内部 Coord / Place（结构一致）
 *   NPCs.getNearest(predicate)    -> getNearest(predicate)  (遍历 client.getNpcs)
 *   target.interact("Attack")     -> client.interactNpc(target, "Attack")
 *   Walker.walkTo(mine.centre())  -> walkTo(mine.centre())  (client.walkTo)
 *   Display.log()                 -> log()                  (ctx.log)
 *
 * 怎么用：
 *   1. 改完: python tools/cloud/build_fish.py  (读 sources/fish/Fish.java, javac+d8 -> dex/fish.dex)
 *   2. 推送: cd tools/cloud/site && git add -A && git commit && git push origin gh-pages
 *   3. 端上: echo reload > /data/local/tmp/astra_cloud.txt
 *           echo load fish > /data/local/tmp/astra_cloud.txt
 *   4. 看日志: adb logcat -d | grep Fish
 *
 * 启停: echo stop > /data/local/tmp/fish.txt  停 / echo start 启 / echo stat 看状态
 *
 * 注意：public 类名必须和文件名一致（Fish -> Fish.java），否则编辑器标红、javac 报错。
 *       源码保持 Java 8 语法（不用 var / switch 表达式），编译用 -source 8。
 */
@PluginDescriptor(
        name = "Fish(杀鸡·AstraStudio版)",
        description = "Ghost 版杀鸡脚本翻译：保留原方法/流程，底层走自研 API",
        tags = {"战斗", "脚本搬迁"},
        enabledByDefault = true
)
public final class Fish implements Plugin {
    private PluginContext ctx;
    private Client client;
    private OfficialNav nav;                 // 官方 astar3 寻路（人不在鸡区时走过去）

    private static final String CMD_FILE = "/data/local/tmp/fish.txt";
    private static final int EVERY = 30;   // 20ms/tick -> 约 600ms 执行一次
    // 杀鸡区：placeFromBottomLeft 的同样区域，官方寻路目标取中心点
    private static final int MINE_X = 3178, MINE_Y = 3296, MINE_PLANE = 0;

    private long loops = 0;
    private boolean active = true;
    private String lastCmd = "";
    private int poll = 0;

    // 攻击后休息 4~5 tick（约 2.4~3 秒）——对应 Ghost 的 Wait.ticks(4,5)
    private int restLeft = 0;
    private boolean resting = false;
    private Coord lastCoord = null;          // 用于近似 isMoving()

    @Override
    public void configure(PluginContext context) {
        this.ctx = context;
        this.client = context.getClient();
    }

    @Override
    public void startUp() {
        ctx.log("[Fish] 启动(AstraStudio版)。启停: echo stop > " + CMD_FILE + " 停 / echo start 启 / echo stat 状态");
        nav = new OfficialNav(client, ctx);
        nav.init();
        nav.setGoal(MINE_X, MINE_Y, MINE_PLANE, "鸡区");
    }

    @Override
    public void shutDown() {
        active = false;
        ctx.log("[Fish] 已停止，共 " + loops + " tick");
    }

    /** 每 10 tick 读一次指令文件，支持 stop/start/stat。 */
    private void pollCommand() {
        if (++poll < 10) return;
        poll = 0;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(CMD_FILE))) {
            String line = r.readLine();
            if (line == null) return;
            line = line.trim();
            if (line.isEmpty() || line.equals(lastCmd)) return;
            lastCmd = line;
            if (line.equalsIgnoreCase("stop")) {
                active = false;
                ctx.log("[Fish] 收到 stop -> 已停止");
            } else if (line.equalsIgnoreCase("start")) {
                active = true;
                ctx.log("[Fish] 收到 start -> 已启动");
            } else if (line.equalsIgnoreCase("stat")) {
                ctx.log("[Fish] 状态: " + (active ? "运行中" : "已停") + ", tick=" + loops);
            }
        } catch (java.io.FileNotFoundException e) {
            // 无指令文件，静默
        } catch (Exception e) {
            ctx.log("[Fish] 读指令异常: " + e);
        }
    }

    // ==================== Ghost 风格轻量兼容层（保留原用法/方法名） ====================

    /** 对应 Ghost com.ghost.api.movement.Coord（只保留 x/y/floor 三元组）。 */
    private static final class Coord {
        final int x, y, floor;
        Coord(int x, int y, int floor) { this.x = x; this.y = y; this.floor = floor; }
        @Override public String toString() { return "(" + x + "," + y + "," + floor + ")"; }
    }

    /** 对应 Ghost com.ghost.api.movement.Place —— 矩形区域 + 包含判定 + 中心点。 */
    private static final class Place {
        final String name;
        final Coord bottomLeft, topRight;
        Place(String name, Coord bottomLeft, Coord topRight) {
            this.name = name; this.bottomLeft = bottomLeft; this.topRight = topRight;
        }
        boolean contains(Coord c) {
            return c.floor == bottomLeft.floor
                    && c.x >= bottomLeft.x && c.x <= topRight.x
                    && c.y >= bottomLeft.y && c.y <= topRight.y;
        }
        Coord centre() {
            return new Coord((bottomLeft.x + topRight.x) / 2,
                    (bottomLeft.y + topRight.y) / 2, bottomLeft.floor);
        }
    }

    /** 对应 Ghost Place.fromCorners 的 placeFromBottomLeft 辅助（原方法保留）。 */
    private static Place placeFromBottomLeft(String name, Coord bottomLeft, int width, int height) {
        Coord topRight = new Coord(bottomLeft.x + width - 1,
                bottomLeft.y + height - 1,
                bottomLeft.floor);
        return new Place(name, bottomLeft, topRight);
    }

    /** Players.getLocal() —— 返回本地玩家，未登录/未就绪返回 null。 */
    private Player localPlayer() {
        return client != null ? client.getLocalPlayer() : null;
    }

    /** me.coord() / entity.coord() —— 用 AstraStudio 的 WorldPoint 转 Coord。 */
    private static Coord coordOf(WorldPoint wp) {
        return wp == null ? null : new Coord(wp.getX(), wp.getY(), wp.getPlane());
    }
    private static Coord coordOf(Player p) {
        return p == null ? null : coordOf(p.getWorldLocation());
    }
    private static Coord coordOf(NPC n) {
        return n == null ? null : coordOf(n.getWorldLocation());
    }

    /**
     * Combat.getAttackStyle() —— Ghost 版在自研桥上恒返回 null（无 vars 切片），
     * 所以原脚本里 style != null 整段实际空转。这里保留同样的 null 语义：
     * 自研 Client 没有专门攻击样式读数，若将来接入 varp 可在此改 client.getVarp(idx)。
     */
    private Integer getAttackStyle() {
        return null;
    }

    /** Skills.getLevel(Skill.ATTACK/STRENGTH/DEFENCE) —— 从 client.getSkills() 按名字取基础等级。 */
    private int getLevel(String skillName) {
        java.util.List<com.astrastudio.api.model.Skill> skills = client.getSkills();
        if (skills == null) return -1;
        for (com.astrastudio.api.model.Skill s : skills) {
            if (s != null && skillName.equalsIgnoreCase(s.getName())) return s.getBase();
        }
        return -1;
    }

    /** Dialog.isOpen() —— 自研无对话状态读取，保留 Ghost 恒 false 的安全语义。 */
    private boolean isDialogOpen() {
        return false;
    }

    /** Dialog.continueDialog() —— 保留调用点；用 resumeDialog 尝试继续，失败则关界面。 */
    private void continueDialog() {
        client.resumeDialog(0, 0, 0);
    }

    /** Combat.setAttackStyle(want) —— 自研桥无攻击样式切换，空操作+日志（原版即空操作）。 */
    private void setAttackStyle(int want) {
        ctx.log("[Fish] setAttackStyle(" + want + ") 自研桥暂不支持，跳过");
    }

    /**
     * Player.isTargetingNpc() —— 用"刚攻击过(休息中)"近似，避免对同一只重复点击。
     */
    private boolean isTargetingNpc() {
        return resting;
    }

    /** Player.isMoving() —— 用相邻 tick 坐标是否变化近似。 */
    private boolean isMoving(Coord here) {
        boolean moving = lastCoord != null && (lastCoord.x != here.x || lastCoord.y != here.y);
        lastCoord = here;
        return moving;
    }

    /** Player.isIdle() —— 不在休息即视为空闲。 */
    private boolean isIdle() {
        return !resting;
    }

    /** NPCs.getNearest(predicate) —— 遍历 client.getNpcs()，按曼哈顿距离取最近。 */
    private NPC getNearest(Predicate<NPC> predicate) {
        NPC best = null;
        int bestDist = Integer.MAX_VALUE;
        Player me = localPlayer();
        Coord here = coordOf(me);
        if (here == null) return null;
        for (NPC n : client.getNpcs()) {
            if (n == null || !predicate.test(n)) continue;
            Coord c = coordOf(n);
            if (c == null) continue;
            int d = Math.abs(c.x - here.x) + Math.abs(c.y - here.y);
            if (d < bestDist) { bestDist = d; best = n; }
        }
        return best;
    }

    /** Display.log() */
    private void log(String s) {
        ctx.log("[Fish] " + s);
    }

    /** Walker.walkTo(mine.centre()) */
    private void walkTo(Coord c) {
        if (c != null) client.walkTo(c.x, c.y);
    }

    // ==================== 主循环（流程与 Ghost 版一致） ====================

    @Subscribe
    public void onTick(GameTick tick) {
        pollCommand();
        if (!active) return;
        if (client == null || !client.isLoggedIn()) return;
        loops++;

        Player me = localPlayer();
        Coord here = coordOf(me);
        if (here == null) return;
        WorldPoint meWp = me.getWorldLocation();

        final Place mine = placeFromBottomLeft("杀鸡", new Coord(3173, 3289, 0), 12, 15);

        // ===== 不在鸡区：交给官方 astar3 寻路走过去（逐 tick 驱动，含楼梯/跨层交互）=====
        if (!mine.contains(here)) {
            if (nav != null) nav.tick(meWp);
            return;
        }

        // ===== 已在鸡区：官方寻路任务结束，执行杀鸡（沿用 Ghost 原流程与调试点）=====
        // 攻击按 EVERY 节流，避免每 tick 重复点击刷屏
        if (tick.getTick() % EVERY != 0) return;

        // 休息倒计时：攻击后跳过若干轮
        if (restLeft > 0) {
            restLeft--;
            if (restLeft == 0) resting = false;
            return;
        }

        // ★ 原版：getAttackStyle() 返回 Integer 可空，必须先判 null 再比较，绝不空拆箱。
        Integer style = getAttackStyle();
        if (style != null) {
            int attack = getLevel("Attack");
            int strength = getLevel("Strength");
            int defence = getLevel("Defence");
            int want;
            if (attack <= strength || attack <= defence) {
                want = 0;   // Accurate
            } else if (strength <= defence) {
                want = 1;   // Aggressive
            } else {
                want = 3;   // Defensive
            }
            if (isDialogOpen()) {
                continueDialog();
                return;
            }
            if (style != want) {
                setAttackStyle(want);
            }
        }

        if (isTargetingNpc() || isMoving(here) || !isIdle()) {
            return;
        }
        log("ccc —— 已到鸡区，开始找鸡");
        // 原版这里用 var target；Java 8 不支持 var，改为显式类型 NPC。
        // 原版判定 x.interacting().isEmpty()（自研 NPC 恒 empty）；改用 getHealth()>0 保证存活。
        NPC target = getNearest(new Predicate<NPC>() {
            @Override public boolean test(NPC n) {
                return n != null
                        && n.getName() != null
                        && n.getName().contains("Chicken")
                        && n.getHealth() > 0
                        && mine.contains(coordOf(n));
            }
        });
        if (target != null) {
            log("dddd ：" + target.getName());
            client.interactNpc(target, "Attack");
            resting = true;
            restLeft = 4;
        }
    }
}
