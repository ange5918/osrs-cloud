package myplugin;

import com.ghost.api.Declaration;
import com.ghost.api.Kind;
import com.ghost.api.LoopedPlugin;
import com.ghost.api.Wait;
import com.ghost.api.entities.*;
import com.ghost.api.movement.Coord;
import com.ghost.api.movement.Place;
import com.ghost.api.movement.Walker;
import com.ghost.api.skills.Combat;
import com.ghost.api.skills.Skill;
import com.ghost.api.skills.Skills;
import com.ghost.api.widgets.Dialog;
import osrs4j.Npc;

import java.util.Optional;

/**
 * Ghost jar 脚本模板 —— 最小可跑的 LoopedPlugin。
 *
 * 写你自己的脚本：照着这个类改 onLoop() 里的逻辑，或复制本文件换个类名/包名。
 * 每一个 ghost jar 脚本必须有三样东西：
 *   1) 继承 LoopedPlugin
 *   2) declare() 声明要读哪些数据切片（不声明就读不到）
 *   3) onLoop() 每 tick(~600ms) 调用一次，写你的状态机
 *
 * 编译/打包/运行见工程根目录的 build.bat 和 README.md。
 */
public final class MyPlugin extends LoopedPlugin {

    // ===== 插件元信息 =====
    @Override public Kind kind() { return Kind.MAIN; }
    @Override public String name() { return "Fish"; }
    @Override public String description() { return "Ghost jar 脚本模板：打印玩家坐标"; }
    @Override public String version() { return "1.0"; }

    /**
     * 声明数据切片。需要什么加什么，别全加（多余切片浪费性能）。
     * 常用：withObjects / withInventory / withSkills / withBank / withReachable
     */
    @Override public Declaration declare() {
        return Declaration.none()
                .withObjects()    // → TileObjects 域
                .withNpcs()       // → NPCs 域
                .withInventory()  // → Inventory 域
                .withBank()       // → Bank 域
                .withSkills()     // → Skills 域
                .withReachable()  // → Tiles 可达性判定
                .withWidgets()    // → Widgets 域
                .withVars() .withWorld() .withGround() .withScreen() .withCamera() .withDefinitions();
    }

    private int tick = 0;

    // ===== 攻击后休息：Ghost 延时的最短正确写法 =====
    // Wait 是不可变计时器：必须"成员持有 + 每 tick 调 pending()"才会走时。
    // 单独写 Wait.ticks(4,5); 丢掉对象 = 完全没用；它也不是 sleep，不会阻塞线程。
    private final Wait restWait = Wait.ticks(4, 5);  // 攻击后休息 4~5 tick（约 2.4~3 秒）
    private boolean resting = false;                 // true=正在休息，不去找下一只

    private static Place placeFromBottomLeft(String name, Coord bottomLeft, int width, int height) {
        Coord topRight = new Coord(bottomLeft.x() + width - 1,
                bottomLeft.y() + height - 1,
                bottomLeft.floor());
        return Place.fromCorners(name, bottomLeft, topRight);
    }

    private static boolean inRect(Coord c, Place p) {
        if (c.floor() != p.centre().floor()) return false;
        int minX = p.centre().x() - (p.width() - 1) / 2;
        int minY = p.centre().y() - (p.height() - 1) / 2;
        return c.x() >= minX && c.x() < minX + p.width()
                && c.y() >= minY && c.y() < minY + p.height();
    }

    @Override public void onLoop() {
        tick++;
        Optional<Player> meOpt = Players.getLocal();
        if (meOpt.isEmpty()) {
            // 前 3 次、之后每 60 tick 打印一次，避免刷屏；让"拿不到玩家"可见
            if (tick <= 3 || tick % 60 == 0) {
                log("[诊断 tick=" + tick + "] 未获取到本地玩家（账号未登录/桥未连上/切片未就绪），跳过本轮");
            }
            return;
        }
        Player me = meOpt.get();
        Coord here = me.coord();
        // ★ getAttackStyle() 返回 Integer（可为 null）：bridge 模式若没喂 vars
        //   切片它就是 null，直接 != 0 会自动拆箱抛 NPE 刷爆 stderr。
        //   必须先判 null；取不到时跳过切换，绝不空拆箱。
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
            if (Dialog.isOpen()){
                Dialog.continueDialog();return;
            }
            if (style != want) {
                Combat.setAttackStyle(want);
            }
        }
        if (lastPos != null && here.x() == lastPos.x() && here.y() == lastPos.y()
                && Walker.isWalking()) stuckTicks++;
        else stuckTicks = 0;
        lastPos = here;
        // 休息中：pending() 每 tick 推进计时，没到时间就直接返回，不找下一只
        if (resting) {
            if (restWait.pending(() -> false)) return;
            resting = false;   // 时间到，往下走
        }

        log("BBBB : targeting=" + me.isTargetingNpc() + " , moving=" + me.isMoving() + " , idle=" + me.isIdle());
        if (me.isTargetingNpc() || me.isMoving() || !me.isIdle()) {
            return;
        }
        Place mine = placeFromBottomLeft("杀鸡", new Coord(3173, 3289, 0), 12, 15);
        if (mine.contains(here)) {
            NPC target = NPCs.getNearest(x -> x != null
                    && x.name().isPresent()          // 无名 NPC 跳过，避免 Optional.get() 抛 NoSuchElementException
                    && x.name().get().contains("Chicken")
                    && x.interacting().isEmpty()
                    && mine.contains(x.coord())).orElse(null);
            if (target!=null) {

                log("dddd  ："+target.coord()+" , "+target.isMoving());
                target.interact("Attack");
                resting = true;      // 攻击后进入休息
                restWait.reset();    // 重新开始计时 4~5 tick
            }
        } else {
            walkto(mine.centre());
        }
    }

    private Coord lastPos = null;
    private int stuckTicks = 0;
    private void walkto(Coord target) {
        Walker.walkTo(target);
    }
    @Override public void onStop() {
        info("MYPLUGIN", "已停止，共运行 " + tick + " tick");
    }
}
