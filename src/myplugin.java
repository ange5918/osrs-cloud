package src.myplugin;

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
    private int lastAttackTick = -100;   // 攻击节流: tick 计数, 不用 Wait/lambda (Android9 dex 兼容)

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
        if (!meOpt.isPresent()) {
            if (tick <= 3 || tick % 60 == 0) {
                log("[诊断 tick=" + tick + "] 未获取到本地玩家（账号未登录/桥未连上/切片未就绪），跳过本轮");
            }
            return;
        }
        Player me = meOpt.get();
        Coord here = me.coord();

        // 攻击样式切换
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
            if (Dialog.isOpen()) {
                Dialog.continueDialog();
                return;
            }
            if (style != want) {
                Combat.setAttackStyle(want);
            }
        }

        log("FFF : targeting=" + me.isTargetingNpc() + " , moving=" + me.isMoving() + " , idle=" + me.isIdle());

        Place mine = placeFromBottomLeft("杀鸡", new Coord(3173, 3289, 0), 12, 15);
        if (!mine.contains(here)) {
            if (tick % 20 == 0) log("走回鸡区 " + here + " -> " + mine.centre());
            walkto(mine.centre());
            return;
        }

        // 攻击节流: 每 5 tick 才找一次鸡 (约 3 秒, 等价原 Wait.ticks(4,5))
        if (tick - lastAttackTick < 5) return;

        // 遍历找最近的鸡 (不用 lambda, 避免 invokedynamic bootstrap 失败)
        NPC best = null;
        int bestDist = Integer.MAX_VALUE;
        for (NPC n : NPCs.getAll()) {
            if (n == null) continue;
            String nm = n.name().orElse("");
            if (nm.toLowerCase().indexOf("chicken") < 0) continue;
            Coord c = n.coord();
            if (!mine.contains(c)) continue;
            int d = Math.abs(c.x() - here.x()) + Math.abs(c.y() - here.y());
            if (d < bestDist) { bestDist = d; best = n; }
        }
        if (best == null) {
            if (tick % 10 == 0) log("鸡区内暂无可攻击的鸡");
            return;
        }
        log("dddd  ：" + best.name().orElse("?") + " idx=" + best.index() + " dist=" + bestDist);
        best.attack();
        lastAttackTick = tick;
    }

    private void walkto(Coord target) {
        Walker.walkTo(target);
    }
    @Override public void onStop() {
        info("MYPLUGIN", "已停止，共运行 " + tick + " tick");
    }
}
