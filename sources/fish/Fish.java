package fish;

import com.astrastudio.api.Plugin;
import com.astrastudio.api.annotation.PluginDescriptor;
import com.astrastudio.api.annotation.Subscribe;
import com.astrastudio.api.event.GameTick;

import com.ghost.api.entities.NPC;
import com.ghost.api.entities.Player;
import com.ghost.api.entities.Players;
import com.ghost.api.entities.NPCs;
import com.ghost.api.movement.Coord;
import com.ghost.api.movement.Place;
import com.ghost.api.movement.Walker;
import com.ghost.api.skills.Combat;
import com.ghost.api.skills.Skill;
import com.ghost.api.skills.Skills;
import com.ghost.api.widgets.Dialog;

import com.astrastudio.nav.OfficialNav;
import com.astrastudio.api.model.Client;
import com.astrastudio.api.model.WorldPoint;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Fish —— Ghost API 版杀鸡脚本（现在 Ghost API 全套已打包进 APK，直接用真值门面）。
 *
 * 与原 Ghost 版写法完全一致，只是套了一层 AstraStudio Plugin 骨架给云端加载器识别：
 *   Players.getLocal()            真值（AndroidOSRS -> client.getLocalPlayerLocation）
 *   me.coord()                    Ghost Coord
 *   Combat.getAttackStyle()       真值（varp 切片）
 *   Skills.getLevel(Skill.ATTACK) 真值（skillsMap）
 *   Dialog.isOpen()               真值
 *   Player.isTargetingNpc()/isMoving()/isIdle()  真值（GhostRuntime 状态）
 *   NPCs.getNearest(predicate)    真值（client.getNpcs）
 *   target.interact("Attack")     真值（attackOfficial 官方链路）
 *   Walker.walkTo()               真值（client.walkTo）
 *   Place.fromCorners / Coord     原版结构
 *
 * 官方寻路：直接用 APK 里已打包的 com.astrastudio.nav.OfficialNav（astar3 图+算法），
 * 不再随 dex 携带 8MB 图数据；人不在鸡区时走过去。
 *
 * 用法:
 *   1. 改完: python tools/cloud/build_fish.py
 *   2. 推送: tools/cloud/site git commit/push (或本地 server)
 *   3. 端上: echo reload > /data/local/tmp/astra_cloud.txt
 *           echo load fish > /data/local/tmp/astra_cloud.txt
 *   4. 日志: adb logcat -d | grep -E "Fish|Ghost"
 *   启停: echo stop > /data/local/tmp/fish.txt  停 / start 启 / stat 状态
 *
 * 注意: public 类名必须 == 文件名 (Fish -> Fish.java); 保持 Java 8 语法。
 */
@PluginDescriptor(
        name = "Fish(杀鸡·Ghost API版)",
        description = "Ghost API 真值门面 + 官方 astar3 寻路：走到鸡区 → 找 Chicken → Attack",
        tags = {"战斗", "Ghost API", "官方寻路"},
        enabledByDefault = true
)
public final class Fish implements Plugin {
    private PluginContext ctx;
    private Client client;                  // 只为喂给 APK 内的 OfficialNav（真值来自 Ghost 门面）
    private OfficialNav nav;

    private static final String CMD_FILE = "/data/local/tmp/fish.txt";
    private static final int EVERY = 30;     // 20ms/tick -> 约 600ms 一次, 对齐 Ghost onLoop
    private static final int MINE_X = 3178, MINE_Y = 3296, MINE_PLANE = 0;

    private long loops = 0;
    private boolean active = true;
    private String lastCmd = "";
    private int poll = 0;
    private int restLeft = 0;                // 对应 Ghost Wait.ticks(4,5) 的攻击后休息
    // 官方寻路图空洞区(如 GE)规划失败时的兜底: Ghost Walker.walkTo 低频重发
    private long lastDirectWalk = -1;
    private Coord lastNavPos = null;
    private int navFailTicks = 0;

    @Override
    public void configure(PluginContext context) {
        this.ctx = context;
        this.client = context.getClient();
    }

    @Override
    public void startUp() {
        log("启动(Ghost API版)。启停: echo stop > " + CMD_FILE + " 停 / echo start 启 / echo stat 状态");
        // 官方寻路类由 APK 提供, 这里只 new + 设目标, 不携带图数据
        nav = new OfficialNav(client, ctx);
        nav.init();
        nav.setGoal(MINE_X, MINE_Y, MINE_PLANE, "鸡区");
    }

    @Override
    public void shutDown() {
        active = false;
        log("已停止，共 " + loops + " tick");
    }

    /** 每 10 tick 读一次指令文件: stop/start/stat。 */
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
                log("收到 stop -> 已停止");
            } else if (line.equalsIgnoreCase("start")) {
                active = true;
                log("收到 start -> 已启动");
            } else if (line.equalsIgnoreCase("stat")) {
                log("状态: " + (active ? "运行中" : "已停") + ", tick=" + loops);
            }
        } catch (java.io.FileNotFoundException e) {
            // 无指令文件, 静默
        } catch (Exception e) {
            log("读指令异常: " + e);
        }
    }

    private void log(String s) {
        ctx.log("[Fish] " + s);
    }

    @Subscribe
    public void onTick(GameTick tick) {
        pollCommand();
        if (!active) return;

        // ===== 本机玩家（Ghost 真值门面）=====
        Optional<Player> meOpt = Players.getLocal();
        if (!meOpt.isPresent()) return;
        Player me = meOpt.get();
        Coord here = me.coord();
        if (here == null) return;
        loops++;

        // 杀鸡区：Ghost Place.fromCorners, 与原版 placeFromBottomLeft(3173,3289,0,12,15) 同区域
        final Place mine = Place.fromCorners("杀鸡",
                new Coord(3173, 3289, 0), new Coord(3184, 3303, 0));

        // ===== 不在鸡区：官方 astar3 寻路走过去 =====
        if (!mine.contains(here)) {
            WorldPoint meWp = client.getLocalPlayerLocation();
            if (nav != null && meWp != null) nav.tick(meWp);
            // 图空洞区(GE等)规划失败 -> Ghost Walker 兜底直发终点, 低频重发避免 flood
            if (nav == null || nav.lastPlanFailed) {
                boolean moved = lastNavPos == null || lastNavPos.x() != here.x() || lastNavPos.y() != here.y();
                navFailTicks = moved ? 0 : navFailTicks + 1;
                lastNavPos = here;
                long t = tick.getTick();
                if (navFailTicks >= 5 && (lastDirectWalk < 0 || t - lastDirectWalk >= 30)) {
                    lastDirectWalk = t;
                    Walker.walkTo(MINE_X, MINE_Y);
                    log("官方寻路无覆盖 -> Ghost Walker 兜底走向鸡区 (" + MINE_X + "," + MINE_Y + ") 当前 " + here);
                }
            }
            return;
        }

        // ===== 已在鸡区：Ghost 原版杀鸡流程 =====
        if (tick.getTick() % EVERY != 0) return;

        // 攻击后休息倒计时
        if (restLeft > 0) { restLeft--; return; }

        // ★ 原版: getAttackStyle() 返回 Integer 可空, 先判 null 再比较, 绝不空拆箱
        Integer style = Combat.getAttackStyle();
        if (style != null) {
            int attack = Skills.getLevel(Skill.ATTACK);
            int strength = Skills.getLevel(Skill.STRENGTH);
            int defence = Skills.getLevel(Skill.DEFENCE);
            int want;
            if (attack <= strength || attack <= defence) {
                want = 0;   // Accurate
            } else if (strength <= defence) {
                want = 1;   // Aggressive
            } else {
                want = 3;   // Defensive
            }
            if (Dialog.isOpen()) continueDialog();
            if (style != want) Combat.setAttackStyle(want);
        }

        // 真值: isTargetingNpc / isMoving / isIdle 全部走 GhostRuntime 状态
        if (me.isTargetingNpc() || me.isMoving() || !me.isIdle()) return;

        log("ccc —— 已到鸡区，开始找鸡");
        Optional<NPC> target = NPCs.getNearest(new Predicate<NPC>() {
            @Override public boolean test(NPC n) {
                return n != null
                        && n.name().isPresent()
                        && n.name().get().contains("Chicken")
                        && n.coord() != null
                        && mine.contains(n.coord());
            }
        });
        if (target.isPresent()) {
            NPC t = target.get();
            log("dddd ：" + t.name().orElse("Chicken") + " @ " + t.coord());
            t.interact("Attack");
            restLeft = 4;   // Wait.ticks(4,5)
        }
    }

    private void continueDialog() {
        // Ghost shim 的 Dialog.isOpen() 恒 false, 此分支实际不会触发;
        // 保留原版调用点, 用安全空操作避免未知方法报错。
        log("continueDialog (shim 空操作)");
    }
}
