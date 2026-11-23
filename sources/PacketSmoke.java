package smoke;

import com.astrastudio.api.Plugin;
import com.astrastudio.api.annotation.PluginDescriptor;
import com.astrastudio.api.annotation.Subscribe;
import com.astrastudio.api.event.GameTick;
import com.astrastudio.api.model.Client;
import com.astrastudio.api.model.NPC;
import com.astrastudio.api.model.WorldPoint;
import com.astrastudio.runtime.internal.NativeBridge;

/**
 * 发包端到端冒烟测试：验证 walk/widget/bank/shop/GE/raw opcode 包
 * 发出后服务器是否接受（连接保持、状态变化）。
 *
 * 策略：
 *  - 未登录时待命，每 150 tick 报一次 gameState；
 *  - 登录进世界后自动跑一串发包，每步间隔 12 tick；
 *  - 每步发包记录：返回值 + serverTick/gameState/坐标/背包/widget文本/varp 快照；
 *  - 底层证据由 native 的 `sendPacket:` logcat 回显提供（sent=n 即发出）。
 *
 * 判断"服务器执行"的信号：
 *  1) native sendPacket sent=n（包已加密发出）
 *  2) 发包后 serverTick 持续增长、gameState 保持 30（服务器没判非法踢线）
 *  3) 背包/widget 文本/varp 相对基线发生变化（动作被真正执行）
 */
@PluginDescriptor(
    name = "发包冒烟测试",
    description = "端到端验证 bank/shop/GE/widget/raw 发包是否被服务器执行",
    tags = {"诊断", "发包"},
    enabledByDefault = false
)
public final class PacketSmoke implements Plugin {
    private Plugin.PluginContext ctx;
    private Client client;
    private long tick = 0, nextAt = 0;
    private boolean started = false;
    private int step = 0;
    private static final int GAP = 12;
    private static final int TOTAL = 11;

    public void configure(Plugin.PluginContext c) {
        this.ctx = c;
        this.client = c.getClient();
    }

    public void startUp() {
        ctx.log("[SMOKE] 发包冒烟启动，等待登录进世界...");
    }

    public void shutDown() {
        ctx.log("[SMOKE] 停止");
    }

    @Subscribe
    public void onTick(GameTick t) {
        tick++;
        int gs = -1;
        try { gs = client.getGameState().getId(); } catch (Throwable ignored) {}
        if (gs != 30) {
            if (tick % 150 == 0) ctx.log("[SMOKE] 等待登录 gameState=" + gs + " tick=" + tick);
            return;
        }
        WorldPoint me = null;
        try { me = client.getLocalPlayerLocation(); } catch (Throwable ignored) {}
        if (me == null) {
            if (tick % 150 == 0) ctx.log("[SMOKE] 坐标空，等待世界加载 tick=" + tick);
            return;
        }
        if (!started) {
            started = true;
            nextAt = tick + 2;
            ctx.log("[SMOKE] === 已登录 coord=" + me.getX() + "," + me.getY() + "," + me.getPlane()
                    + " serverTick=" + safeTick() + " ===");
            ctx.log("[SMOKE] 发包通道 socketFd=" + fd() + " isaacIdx=" + isaac() + " (native 直发)");
            ctx.log("[SMOKE] 基线 " + snap());
            return;
        }
        if (tick < nextAt) return;
        nextAt = tick + GAP;
        try {
            runStep(step);
        } catch (Throwable e) {
            ctx.log("[SMOKE] step" + step + " 异常: " + e);
        }
        step++;
        if (step >= TOTAL) {
            ctx.log("[SMOKE] === 序列完成，最终 " + snap() + " ===");
            ctx.log("[SMOKE] === 请抓 logcat 'sendPacket:' 核对每包 sent=n ===");
            step = TOTAL; // 停在此处，不重复发包
        }
    }

    private void runStep(int s) {
        switch (s) {
            case 0: {
                boolean r = client.bankClose();
                ctx.log("[SMOKE][0] bankClose(op52,w786434) ret=" + r + " | " + snap());
                break;
            }
            case 1: {
                boolean r = client.shopClose();
                ctx.log("[SMOKE][1] shopClose(op52,w19660801) ret=" + r + " | " + snap());
                break;
            }
            case 2: {
                boolean r = client.closeInterface();
                ctx.log("[SMOKE][2] closeInterface(op30) ret=" + r + " | " + snap());
                break;
            }
            case 3: {
                boolean r = client.bankDepositAll();
                ctx.log("[SMOKE][3] bankDepositAll(op52,w786479) ret=" + r + " | " + snap());
                break;
            }
            case 4: {
                client.sendPacket(Client.OP_KEEPALIVE, new byte[]{0});
                ctx.log("[SMOKE][4] keepalive(op68) sent | " + snap());
                break;
            }
            case 5: {
                client.sendPacket(Client.OP_COUNT, new byte[]{5});
                ctx.log("[SMOKE][5] count(op15,n=5) sent | " + snap());
                break;
            }
            case 6: {
                boolean r = client.resumeDialog(-1, 0, 1);
                ctx.log("[SMOKE][6] resume(op45) ret=" + r + " | " + snap());
                break;
            }
            case 7: {
                probeBanker();
                break;
            }
            case 8: {
                boolean r = client.bankWithdraw(0, -1, 1);
                ctx.log("[SMOKE][8] bankWithdraw(op52,w786444,slot0) ret=" + r + " | " + snap());
                break;
            }
            case 9: {
                boolean r = client.geAction(0, 0, -1, 1);
                ctx.log("[SMOKE][9] geAction(op52,w" + (Client.GE_WIDGET_GROUP << 16) + ") ret=" + r + " | " + snap());
                break;
            }
            case 10: {
                ctx.log("[SMOKE][10] 终态 " + snap());
                break;
            }
            default: break;
        }
    }

    /** 找最近的 banker NPC 并尝试交互打开银行界面 */
    private void probeBanker() {
        try {
            java.util.List<NPC> npcs = client.getNpcs();
            WorldPoint me = client.getLocalPlayerLocation();
            NPC best = null;
            int bestD = 1 << 20;
            if (npcs != null && me != null) {
                for (NPC n : npcs) {
                    String nm = n.getName();
                    if (nm == null) continue;
                    String lower = nm.toLowerCase();
                    if (lower.contains("bank") || lower.contains("banker")) {
                        WorldPoint p = n.getWorldLocation();
                        if (p == null) continue;
                        int d = me.chebyshevTo(p);
                        if (d < bestD) { bestD = d; best = n; }
                    }
                }
            }
            if (best == null) {
                ctx.log("[SMOKE][7] 附近无 banker NPC（场景未加载银行）| " + snap());
                return;
            }
            ctx.log("[SMOKE][7] 找到 banker name=" + best.getName() + " idx=" + best.getIndex()
                    + " dist=" + bestD + " -> interact");
            client.interactNpc(best, "Bank");
            ctx.log("[SMOKE][7] interact 已调用 | " + snap());
        } catch (Throwable e) {
            ctx.log("[SMOKE][7] probeBanker 异常: " + e);
        }
    }

    /** 状态快照：观测服务器是否执行（varp/背包/widget文本/连接） */
    private String snap() {
        StringBuilder b = new StringBuilder();
        b.append("st=").append(safeTick());
        try { b.append(" gs=").append(client.getGameState().getId()); } catch (Throwable e) { b.append(" gs=?"); }
        try {
            WorldPoint p = client.getLocalPlayerLocation();
            b.append(" pos=").append(p == null ? "null" : p.getX() + "," + p.getY());
        } catch (Throwable e) { b.append(" pos=?"); }
        try {
            b.append(" inv=").append(client.getInventory().size());
        } catch (Throwable e) { b.append(" inv=?"); }
        try {
            int g = client.getVarp(1742); // 常见金币/银行相关 varp 之一
            b.append(" varp1742=").append(g);
        } catch (Throwable e) { b.append(" varp=?"); }
        try {
            String w = client.getWidgetText(Client.BANK_WIDGET);
            b.append(" bankTxt=").append(abbr(w));
        } catch (Throwable e) { b.append(" bankTxt=?"); }
        return b.toString();
    }

    private String abbr(String s) {
        if (s == null) return "null";
        s = s.replace("\n", " ").trim();
        return s.length() > 60 ? s.substring(0, 60) + ".." : s;
    }

    private long safeTick() {
        try { return client.getServerTick(); } catch (Throwable e) { return -1; }
    }

    private int fd() {
        try { return NativeBridge.nativeSocketFd(); } catch (Throwable e) { return -1; }
    }

    private int isaac() {
        try { return NativeBridge.nativeIsaacIndex(); } catch (Throwable e) { return -1; }
    }
}
