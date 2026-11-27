package myplugin;

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

import com.astrastudio.api.model.Client;
import com.astrastudio.api.model.WorldPoint;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * MyPlugin —— 杀鸡脚本（AstraStudio Plugin 骨架，云端加载器可加载）。
 *
 * 之前版本继承 com.ghost.api.LoopedPlugin（实现 ghost 包的 Plugin），
 * 而云端加载器 PluginManager 要求 com.astrastudio.api.Plugin + @PluginDescriptor，
 * 接口包名不同 -> 被"框架拒绝加载"，脚本从未运行，自然走不到鸡区。
 * 本版改成 implements com.astrastudio.api.Plugin + @PluginDescriptor + @Subscribe GameTick，
 * 与已验证可加载的 fish.Fish 同构；寻路改用 APK 内 OfficialNav（真跨区寻路）。
 *
 * 启停: echo stop  > /data/local/tmp/myplugin.txt  停 / echo start 启 / echo stat 状态
 * 日志: adb logcat -d -s System.out:I | grep -E "MyPlugin|qqqqq|dddd"
 */
@PluginDescriptor(
        name = "MyPlugin(杀鸡)",
        description = "Ghost API 真值门面 + 官方 astar3 寻路：走到鸡区 → 找 Chicken → Attack",
        tags = {"战斗", "Ghost API", "官方寻路"},
        enabledByDefault = true
)
public final class MyPlugin implements Plugin {
    private PluginContext ctx;
    private Client client;
    // 寻路走 APK 门面 Walker.walkTo -> AutoNavPlugin(NavBot 2.83 完整寻路)

    private static final String CMD_FILE = "/data/local/tmp/myplugin.txt";
    private static final int EVERY = 30;   // 20ms/tick -> 约600ms, 对齐 Ghost onLoop
    private static final int MINE_X = 3178, MINE_Y = 3296, MINE_PLANE = 0;

    private long loops = 0;
    private boolean active = true;
    private String lastCmd = "";
    private int poll = 0;
    private int restLeft = 0;
    private long lastDirectWalk = -1;
    private Coord lastPos = null;
    private int stuckTicks = 0;

    @Override
    public void configure(PluginContext context) {
        this.ctx = context;
        this.client = context.getClient();
    }

    @Override
    public void startUp() {
        log("启动。启停: echo stop > " + CMD_FILE + " 停 / echo start 启 / echo stat 状态");
        // ★整套移植: 寻路交给 APK 常驻 AutoNavPlugin, 这里不再自建寻路器。
        //   到鸡区的目标在 onTick 里用 Walker.walkTo(MINE_X,MINE_Y) 设置(幂等)。
    }

    @Override
    public void shutDown() {
        active = false;
        log("已停止，共 " + loops + " tick");
    }

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
        ctx.log("[MyPlugin] " + s);
    }

    @Subscribe
    public void onTick(GameTick tick) {
        pollCommand();
        if (!active) return;

        Optional<Player> meOpt = Players.getLocal();
        if (!meOpt.isPresent()) {
            if (loops <= 3 || loops % 60 == 0) {
                log("[诊断] 未获取到本地玩家(账号未登录/桥未连上/切片未就绪), 跳过本轮");
            }
            return;
        }
        Player me = meOpt.get();
        Coord here = me.coord();
        if (here == null) return;
        loops++;

        final Place mine = Place.fromCorners("杀鸡",
                new Coord(3173, 3289, 0), new Coord(3184, 3303, 0));

        // ===== 不在鸡区：官方 astar3 寻路走过去 =====
        if (!mine.contains(here)) {
            // ★本脚本 onTick 每 tick 调门面 -> NavBot.step() 推进一步(APK 内无常驻 onTick)。
            Walker.walkTo(MINE_X, MINE_Y, MINE_PLANE);
            return;
        }

        // ===== 已在鸡区：杀鸡流程 =====
        if (tick.getTick() % EVERY != 0) return;
        if (restLeft > 0) { restLeft--; return; }

        Integer style = Combat.getAttackStyle();
        if (style != null) {
            int attack = Skills.getLevel(Skill.ATTACK);
            int strength = Skills.getLevel(Skill.STRENGTH);
            int defence = Skills.getLevel(Skill.DEFENCE);
            int want;
            if (attack <= strength || attack <= defence) {
                want = 0;
            } else if (strength <= defence) {
                want = 1;
            } else {
                want = 3;
            }
            if (Dialog.isOpen()) continueDialog();
            if (style != want) Combat.setAttackStyle(want);
        }

        if (lastPos != null && here.x() == lastPos.x() && here.y() == lastPos.y()
                && Walker.isWalking()) stuckTicks++;
        else stuckTicks = 0;
        lastPos = here;

        log("qqqqq : targeting=" + me.isTargetingNpc() + " , moving=" + me.isMoving()
                + " , idle=" + me.isIdle() + " , stuck=" + stuckTicks + " , here=" + here);
        if (me.isTargetingNpc() || me.isMoving() || !me.isIdle()) return;

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
            restLeft = 4;
        }
    }

    private void continueDialog() {
        log("continueDialog (shim 空操作)");
    }
}
