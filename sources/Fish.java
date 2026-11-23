package fish;

import com.astrastudio.api.Plugin;
import com.astrastudio.api.annotation.PluginDescriptor;
import com.astrastudio.api.annotation.Subscribe;
import com.astrastudio.api.event.GameTick;
import com.astrastudio.api.model.Client;
import com.astrastudio.api.model.WorldPoint;
import com.astrastudio.api.model.NPC;
import com.astrastudio.api.model.TileObject;

/**
 * Fish —— 你的脚本工程模板。
 *
 * 怎么用：
 *   1. 只在 onTick() 里 "写你自己的逻辑" 那一段改代码，其余不用动。
 *   2. 改完: python tools/cloud/build_fish.py  (javac + d8 -> dex/fish.dex, 自动更 manifest)
 *   3. 推送: cd tools/cloud/site && git add -A && git commit && git push origin gh-pages
 *   4. 端上: echo reload > /data/local/tmp/astra_cloud.txt
 *           echo load fish > /data/local/tmp/astra_cloud.txt
 *   5. 看日志: adb logcat -d | grep Fish
 *
 * 启停: echo stop > /data/local/tmp/fish.txt  停 / echo start 启 / echo stat 看状态
 *
 * 注意：public 类名必须和文件名一致（Fish -> Fish.java），否则编辑器标红、javac 报错。
 */
@PluginDescriptor(
        name = "Fish(模板)",
        description = "脚本模板：读坐标/周围实体 + 文件启停。在这里写你自己的逻辑",
        tags = {"模板", "脚本"},
        enabledByDefault = true
)
public final class Fish implements Plugin {
    private PluginContext ctx;
    private Client client;

    private static final String CMD_FILE = "/data/local/tmp/fish.txt";
    private static final int EVERY = 30;   // 20ms/tick -> 约 600ms 执行一次

    private long loops = 0;
    private boolean active = true;
    private String lastCmd = "";
    private int poll = 0;

    @Override
    public void configure(PluginContext context) {
        this.ctx = context;
        this.client = context.getClient();
    }

    @Override
    public void startUp() {
        ctx.log("[Fish] 启动。启停: echo stop > " + CMD_FILE + " 停 / echo start 启 / echo stat 状态");
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

    @Subscribe
    public void onTick(GameTick tick) {
        pollCommand();
        if (!active) return;
        if (!client.isLoggedIn()) return;                 // 必须：未登录坐标是假的(121,2,0)
        if (tick.getTick() % EVERY != 0) return;          // 节流：20ms/tick，别发太快

        loops++;
        WorldPoint me = client.getLocalPlayerLocation();
        if (me == null) return;

        // ===================== 在这里写你自己的逻辑 =====================
        // 示例：打印坐标和周围实体数量，证明读数据正常。删掉换成你的逻辑。
        int npcCount = client.getNpcs().size();
        int objCount = client.getTileObjects().size();
        int groundCount = client.getGroundItems().size();
        ctx.log("[Fish] 坐标=" + me + " | NPC=" + npcCount + " 物体=" + objCount + " 地面=" + groundCount);

        // 常用写法（需要哪个就把注释打开，对应 import 已在文件顶部）：
        //   client.walkTo(3200, 3200);                 // 走路（官方寻路）
        //   for (NPC n : client.getNpcs()) {           // 遍历 NPC
        //       ctx.log("[Fish] npc=" + n.getName() + " hp=" + n.getHealth());
        //   }
        //   for (TileObject o : client.getTileObjects()) { /* 场景物体 */ }
        //   client.interactNpc(npc, "Attack");         // 交互
        //   client.pickupItem(x, y, itemId);           // 捡东西
        //   client.sendPacket(opcode, payload);        // 裸发包
        //   client.bankDepositAll(); client.shopBuy(slot, itemId, 1);
        // ==============================================================
    }
}
