package e2e;

import com.astrastudio.api.Plugin;
import com.astrastudio.api.annotation.PluginDescriptor;
import com.astrastudio.api.annotation.Subscribe;
import com.astrastudio.api.event.GameTick;

import com.ghost.api.entities.NPC;
import com.ghost.api.entities.NPCs;
import com.ghost.api.entities.Player;
import com.ghost.api.entities.Players;
import com.ghost.api.entities.TileObject;
import com.ghost.api.entities.TileObjects;
import com.ghost.api.movement.Coord;
import com.ghost.api.items.Bank;
import com.ghost.api.items.Inventory;
import com.ghost.api.items.Item;
import com.ghost.api.items.Shop;
import com.ghost.api.shim.GhostRuntime;
import com.ghost.api.skills.Skill;
import com.ghost.api.skills.Skills;

import com.astrastudio.nav.OfficialNav;
import com.astrastudio.api.model.Client;
import com.astrastudio.api.model.WorldPoint;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * E2E —— 全程 Ghost API 门面: 走到 L2 楼 -> 银行取 5 coins -> 走到商店 -> 买 1 hammer。
 *
 * 状态机(每步都用 Ghost API 真值门面, 不手写 opcode/widget):
 *   WALK_L2     OfficialNav 跨层寻路到 L2 楼银行 (3208,3219,2)
 *   OPEN_BANK   TileObjects 找柜台 18491 -> invoke_object (Ghost 底层) 开银行, Bank.isOpen() 确认
 *   WITHDRAW    Bank.withdraw("Coins",5) -> Inventory 验证金币 +5
 *   WALK_SHOP   OfficialNav 寻路到 Lumbridge General Store (3212,3246,0)
 *   OPEN_SHOP   NPCs.getNearest("Shop assistant"/"Shop keeper") -> npc.interact("Trade"), Shop.isOpen() 确认
 *   BUY         Shop.buy("Hammer",1) -> Inventory 验证 hammer +1
 *   DONE
 *
 * 控制: echo stop > /data/local/tmp/e2e.txt  停 / start 启 / stat 状态 / rerun 重新跑
 * 日志: adb logcat -d | grep -E "E2E|官方寻路"
 */
@PluginDescriptor(
        name = "E2E(取5coins买1hammer)",
        description = "全程 Ghost API 门面: L2取钱 -> 商店买锤, 官方astar3寻路",
        tags = {"Ghost API", "E2E", "官方寻路"},
        enabledByDefault = true
)
public final class E2E implements Plugin {
    private PluginContext ctx;
    private Client client;                 // 只为喂给 APK 内的 OfficialNav
    private OfficialNav nav;

    private static final String CMD_FILE = "/data/local/tmp/e2e.txt";

    // 目标真值
    private static final int L2_X = 3208, L2_Y = 3219, L2_PLANE = 2;          // L 城 2 楼银行
    private static final int BANK_OID = 18491, BANK_X = 3208, BANK_Y = 3221;  // 银行柜台
    private static final int SHOP_X = 3212, SHOP_Y = 3246, SHOP_PLANE = 0;    // Lumbridge General Store

    // 状态
    private enum Stage { WALK_L2, OPEN_BANK, WITHDRAW, WALK_SHOP, OPEN_SHOP, BUY, DONE }
    private Stage stage = Stage.WALK_L2;

    private long loops = 0, stageLoops = 0;
    private boolean active = true, inited = false;
    private int poll = 0, lastCmdPoll = 0;
    private String lastCmd = "";

    private int coinsBefore = -1, hammerBefore = -1;
    private int actTry = 0;
    private long lastActionLog = -1;
    private boolean navGoalSet = false;

    @Override public void configure(Plugin.PluginContext context) {
        this.ctx = context;
        this.client = context.getClient();
    }

    @Override public void startUp() {
        log("启动(全程 Ghost API)。控制: echo stop/start/stat/rerun > " + CMD_FILE);
        nav = new OfficialNav(client, ctx);
        nav.init();
    }

    @Override public void shutDown() {
        active = false;
        log("已停止, 共 " + loops + " tick, 阶段=" + stage);
    }

    private void log(String s) { ctx.log("[E2E] " + s); }

    /** 每 10 tick 读一次指令: stop / start / stat / rerun。 */
    private void pollCommand() {
        if (++poll < 10) return;
        poll = 0;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(CMD_FILE))) {
            String line = r.readLine();
            if (line == null) return;
            line = line.trim();
            if (line.isEmpty() || line.equals(lastCmd)) return;
            lastCmd = line;
            if (line.equalsIgnoreCase("stop")) { active = false; log("收到 stop -> 已停止"); }
            else if (line.equalsIgnoreCase("start")) { active = true; log("收到 start -> 已启动"); }
            else if (line.equalsIgnoreCase("stat")) { log("状态: " + (active ? "运行中" : "已停") + " 阶段=" + stage + " tick=" + loops + " 金币=" + coins() + " 锤=" + hammers()); }
            else if (line.equalsIgnoreCase("rerun")) { reset(); active = true; log("收到 rerun -> 重新开始"); }
        } catch (java.io.FileNotFoundException e) {
            // 静默
        } catch (Exception e) { log("读指令异常: " + e); }
    }

    private void reset() {
        stage = Stage.WALK_L2; stageLoops = 0; navGoalSet = false;
        coinsBefore = -1; hammerBefore = -1; actTry = 0;
    }

    private void gotoStage(Stage s) {
        stage = s; stageLoops = 0; actTry = 0; navGoalSet = false;
    }

    // ---------- Ghost API 真值读取 ----------
    private Optional<Player> me() { try { return Players.getLocal(); } catch (Throwable t) { return Optional.empty(); } }
    private Coord coord() { Optional<Player> p = me(); return p.isPresent() ? p.get().coord() : null; }

    private int coins() {
        try {
            Optional<Item> it = Inventory.getFirst(995);
            return it.isPresent() ? it.get().quantity() : 0;
        } catch (Throwable t) { return -1; }
    }
    private int hammers() {
        try {
            Optional<Item> it = Inventory.getFirst(2347);
            return it.isPresent() ? it.get().quantity() : 0;
        } catch (Throwable t) { return -1; }
    }
    private boolean near(Coord c, int x, int y, int plane) {
        return c != null && c.x() == x && c.y() == y && c.floor() == plane;
    }

    // ---------- 主循环 ----------
    @Subscribe public void onTick(GameTick tick) {
        pollCommand();
        if (!active || stage == Stage.DONE) return;

        Optional<Player> meOpt = me();
        if (!meOpt.isPresent()) return;
        Player me = meOpt.get();
        Coord here = me.coord();
        if (here == null) return;
        loops++; stageLoops++;

        WorldPoint wp = client.getLocalPlayerLocation();
        if (wp == null) return;

        switch (stage) {
            case WALK_L2:      doWalk(wp, here, L2_X, L2_Y, L2_PLANE, "L2银行", Stage.OPEN_BANK); break;
            case OPEN_BANK:    doOpenBank(here); break;
            case WITHDRAW:     doWithdraw(); break;
            case WALK_SHOP:    doWalk(wp, here, SHOP_X, SHOP_Y, SHOP_PLANE, "商店", Stage.OPEN_SHOP); break;
            case OPEN_SHOP:    doOpenShop(here); break;
            case BUY:          doBuy(); break;
            default: break;
        }

        // 阶段超时保护: 每 900 tick(~18s) 报一次, 不无限卡死也不自动跳过(交给 rerun)
        if (stageLoops > 0 && stageLoops % 900 == 0) {
            log("阶段 " + stage + " 已等待 " + stageLoops + " tick, 当前 " + here + " 金币=" + coins() + " 锤=" + hammers());
        }
    }

    /** 官方寻路走到目标; 到达后切下一阶段。 */
    private void doWalk(WorldPoint wp, Coord here, int x, int y, int plane, String name, Stage next) {
        if (!navGoalSet) {
            nav.setGoal(x, y, plane, name);
            navGoalSet = true;
            log("→ 寻路到 " + name + " (" + x + "," + y + ",p" + plane + ") 起点=" + here);
        }
        nav.tick(wp);
        if (near(here, x, y, plane)) {
            log("✓ 已到 " + name + " " + here);
            gotoStage(next);
        }
    }

    /** 开银行: Ghost 底层 invoke_object(柜台18491) 开界面, Bank.isOpen() 真值确认。 */
    private void doOpenBank(Coord here) {
        if (Bank.isOpen()) {
            coinsBefore = coins();
            log("✓ 银行已打开, 取钱前金币=" + coinsBefore + " -> 取5coins");
            gotoStage(Stage.WITHDRAW);
            return;
        }
        long t = client.getTickCount();
        if (t - lastActionLog < 20) return;   // 每 20 tick 试一次
        lastActionLog = t;
        actTry++;

        // 主: 按真值柜台 id+坐标 invoke_object slot1 = Bank
        try { GhostRuntime.api().invokeObject(BANK_OID, BANK_X, BANK_Y, 1); }
        catch (Throwable e) { log("invoke_object 异常: " + e); }

        // 兜底1: Ghost TileObjects 找带 Bank 动作的柜台, 用其真实坐标 invoke_object
        try {
            List<TileObject> tos = TileObjects.getNearest(18491).isPresent()
                    ? java.util.Collections.singletonList(TileObjects.getNearest(18491).get())
                    : TileObjects.getAll(new Predicate<TileObject>() {
                          @Override public boolean test(TileObject o) {
                              return o != null && o.options() != null && o.options().contains("Bank");
                          }
                      });
            for (TileObject o : tos) {
                Coord c = o.coord();
                if (c != null) { GhostRuntime.api().invokeObject(o.id(), c.x(), c.y(), 1); break; }
            }
        } catch (Throwable e) { /* 兜底失败不影响主路径 */ }

        // 兜底2: 若有 banker NPC, 用 Ghost NPC.interact("Bank")
        try {
            Optional<NPC> banker = NPCs.getNearest(new Predicate<NPC>() {
                @Override public boolean test(NPC n) {
                    if (n == null || !n.name().isPresent()) return false;
                    String nm = n.name().get().toLowerCase();
                    return nm.contains("bank") || nm.contains("banker");
                }
            });
            if (banker.isPresent()) banker.get().interact("Bank");
        } catch (Throwable e) { /* 静默 */ }

        if (actTry % 3 == 1) log("开银行尝试#" + actTry + " 当前 " + here + " bankOpen=" + Bank.isOpen());
    }

    /** 取 5 coins: Bank.withdraw("Coins",5) -> Withdraw-5, Inventory 验证 +5。 */
    private void doWithdraw() {
        int now = coins();
        if (now >= coinsBefore + 5) {
            log("✓ 取到 5 coins: " + coinsBefore + " -> " + now + " -> 走向商店");
            try { Bank.close(); } catch (Throwable t) {}
            gotoStage(Stage.WALK_SHOP);
            return;
        }
        long t = client.getTickCount();
        if (t - lastActionLog < 20) return;
        lastActionLog = t;
        actTry++;
        try {
            Bank.withdraw("Coins", 5);
        } catch (Throwable e) {
            // 名字找不到时按 id 取银行里的 coins item
            try {
                Optional<Item> c = Bank.getFirst(995);
                if (c.isPresent()) Bank.withdraw(c.get(), 5);
                else log("银行里没找到 Coins(id995), bankOpen=" + Bank.isOpen() + " slots=" + Bank.getAll().size());
            } catch (Throwable e2) { log("withdraw 异常: " + e2); }
        }
        if (actTry % 3 == 1) log("取钱尝试#" + actTry + " 金币=" + now + "/" + coinsBefore + " bankOpen=" + Bank.isOpen());
    }

    /** 开商店: NPCs.getNearest Shop assistant/keeper -> interact("Trade"), Shop.isOpen() 确认。 */
    private void doOpenShop(Coord here) {
        if (Shop.isOpen()) {
            hammerBefore = hammers();
            log("✓ 商店已打开, 买前 hammer=" + hammerBefore + " -> 买1hammer");
            gotoStage(Stage.BUY);
            return;
        }
        long t = client.getTickCount();
        if (t - lastActionLog < 20) return;
        lastActionLog = t;
        actTry++;
        try {
            Optional<NPC> shop = NPCs.getNearest(new Predicate<NPC>() {
                @Override public boolean test(NPC n) {
                    if (n == null || !n.name().isPresent()) return false;
                    String nm = n.name().get().toLowerCase();
                    return nm.contains("shop") || nm.contains("keeper") || nm.contains("grocer");
                }
            });
            if (shop.isPresent()) {
                NPC n = shop.get();
                log("开店尝试#" + actTry + " NPC=" + n.name().orElse("?") + " @ " + n.coord() + " -> Trade");
                n.interact("Trade");
            } else {
                if (actTry % 3 == 1) log("附近无 shop NPC, 当前 " + here);
            }
        } catch (Throwable e) { log("interact Trade 异常: " + e); }
    }

    /** 买 1 hammer: Shop.buy("Hammer",1) -> Buy-1, Inventory 验证 +1。 */
    private void doBuy() {
        int now = hammers();
        if (now >= hammerBefore + 1) {
            log("★★★ 全部完成: 取到5coins 且买到1hammer! 金币=" + coins() + " hammer=" + now);
            stage = Stage.DONE;
            active = false;
            return;
        }
        long t = client.getTickCount();
        if (t - lastActionLog < 20) return;
        lastActionLog = t;
        actTry++;
        try {
            Shop.buy("Hammer", 1);
        } catch (Throwable e) {
            try {
                Optional<Item> h = Shop.getFirst("Hammer");
                if (h.isPresent()) h.get().interact("Buy-1");
                else log("商店没找到 Hammer, shopOpen=" + Shop.isOpen() + " stock=" + Shop.getAll().size());
            } catch (Throwable e2) { log("buy 异常: " + e2); }
        }
        if (actTry % 3 == 1) log("买锤尝试#" + actTry + " hammer=" + now + "/" + hammerBefore + " shopOpen=" + Shop.isOpen());
    }
}
