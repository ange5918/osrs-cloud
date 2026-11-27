package myplugin;

import com.astrastudio.api.Plugin;
import com.astrastudio.api.annotation.PluginDescriptor;
import com.astrastudio.api.annotation.Subscribe;
import com.astrastudio.api.event.GameTick;

import com.ghost.api.cmd.GhostCommand;
import com.ghost.api.skills.Combat;
import com.ghost.api.skills.Skill;
import com.ghost.api.skills.Skills;
import com.ghost.api.widgets.Dialog;

import org.json.JSONObject;

/**
 * MyPlugin —— 杀鸡脚本（决策全在脚本，APK ghost 只执行/查询）。
 *
 * 架构：
 *   myplugin onTick  = 自己查询真值、自己判断在不在鸡区、自己选鸡、自己决定攻击节奏；
 *   APK GhostCommand = 收到一条命令才查一次数据或发一次包，不自设目标、不自开 tick。
 *
 * 启停: echo stop  > /data/local/tmp/myplugin.txt  停 / echo start 启 / echo stat 状态
 * 日志: adb logcat -d -s System.out:I | grep -E "MyPlugin|GhostCMD"
 */
@PluginDescriptor(
        name = "MyPlugin(杀鸡)",
        description = "脚本自主决策 + APK GhostCommand 查询/发包：走到鸡区 → 找 Chicken → Attack",
        tags = {"战斗", "Ghost API", "命令层"},
        enabledByDefault = true
)
public final class MyPlugin implements Plugin {
    private PluginContext ctx;

    private static final String CMD_FILE = "/data/local/tmp/myplugin.txt";
    private static final int EVERY = 30;
    private static final int MINE_X1 = 3173, MINE_Y1 = 3289;
    private static final int MINE_X2 = 3184, MINE_Y2 = 3303;
    private static final int GOAL_X = 3178, GOAL_Y = 3296, GOAL_P = 0;

    private long loops = 0;
    private boolean active = true;
    private String lastCmd = "";
    private int poll = 0;
    private int restLeft = 0;
    private int lastX = -1, lastY = -1;
    private int stuckTicks = 0;

    @Override
    public void configure(PluginContext context) {
        this.ctx = context;
    }

    @Override
    public void startUp() {
        log("启动。启停: echo stop > " + CMD_FILE + " 停 / echo start 启 / echo stat 状态");
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
            // 无指令文件，静默
        } catch (Exception e) {
            log("读指令异常: " + e);
        }
    }

    private void log(String s) {
        ctx.log("[MyPlugin] " + s);
    }

    private static boolean inMine(int x, int y) {
        return x >= MINE_X1 && x <= MINE_X2 && y >= MINE_Y1 && y <= MINE_Y2;
    }

    @Subscribe
    public void onTick(GameTick tick) {
        pollCommand();
        if (!active) return;

        // ---- 命令1：查玩家真值（APK 只返回信息，不做决策） ----
        JSONObject me;
        try {
            me = new JSONObject(GhostCommand.player());
        } catch (Throwable e) {
            if (loops <= 3 || loops % 60 == 0) log("[诊断] player 命令异常: " + e);
            return;
        }
        if (!me.optBoolean("ok", false)) {
            if (loops <= 3 || loops % 60 == 0) log("[诊断] 未获取到本地玩家: " + me.optString("error"));
            return;
        }
        int x = me.optInt("x"), y = me.optInt("y"), plane = me.optInt("plane");
        loops++;

        // ---- 不在鸡区：命令 APK 朝目标走一步；走几步、何时停由脚本决定 ----
        if (!inMine(x, y)) {
            GhostCommand.walkTo(GOAL_X, GOAL_Y, GOAL_P);
            return;
        }

        // ---- 已在鸡区：脚本自己的战斗节奏 ----
        if (tick.getTick() % EVERY != 0) return;
        if (restLeft > 0) { restLeft--; return; }

        Integer style = Combat.getAttackStyle();
        if (style != null) {
            int attack = Skills.getLevel(Skill.ATTACK);
            int strength = Skills.getLevel(Skill.STRENGTH);
            int defence = Skills.getLevel(Skill.DEFENCE);
            int want;
            if (attack <= strength || attack <= defence) want = 0;
            else if (strength <= defence) want = 1;
            else want = 3;
            if (Dialog.isOpen()) continueDialog();
            if (style != want) Combat.setAttackStyle(want);
        }

        if (x == lastX && y == lastY && me.optBoolean("moving", false)) stuckTicks++;
        else stuckTicks = 0;
        lastX = x; lastY = y;

        log("qqqqq : targeting=" + me.optBoolean("interacting")
                + " , moving=" + me.optBoolean("moving")
                + " , idle=" + me.optBoolean("idle")
                + " , stuck=" + stuckTicks + " , here=" + x + "," + y);

        if (me.optBoolean("interacting") || me.optBoolean("moving") || !me.optBoolean("idle")) return;

        // ---- 命令2：查 NPC 真值；脚本自己筛选目标鸡 ----
        JSONObject near;
        try {
            near = new JSONObject(GhostCommand.npcNearest("Chicken"));
        } catch (Throwable e) {
            return;
        }
        if (!near.optBoolean("ok", false)) return;
        int nx = near.optInt("x", -1), ny = near.optInt("y", -1);
        if (!inMine(nx, ny)) return;

        // ---- 命令3：让 APK 立刻发交互包 ----
        String name = near.optString("name", "Chicken");
        String r = GhostCommand.npcInteract(name, "Attack");
        log("dddd ：" + name + " @ " + nx + "," + ny + " -> " + r);
        restLeft = 4;
    }

    private void continueDialog() {
        log("continueDialog (shim 空操作)");
    }
}
