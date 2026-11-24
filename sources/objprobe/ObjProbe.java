package objprobe;

import com.astrastudio.api.Plugin;
import com.astrastudio.api.annotation.PluginDescriptor;
import com.astrastudio.api.annotation.Subscribe;
import com.astrastudio.api.event.GameTick;
import com.astrastudio.api.model.Client;
import com.astrastudio.api.model.WorldPoint;
import com.ghost.api.shim.GhostRuntime;

import java.util.List;
import java.util.Map;

/**
 * ObjProbe —— 独立受控探针: 只做"点击附近 object + 观察状态变化", 不依赖 e2e/寻路。
 *
 * 控制: /data/local/tmp/objprobe.txt
 *   scan              -> 列出玩家 15 格内所有 object(id/x/y/name/actions)
 *   click <oid>       -> 点该 object 的 slot0, 点击前后 dump actions + 玩家坐标
 *   click <oid> <slot>-> 指定 slot(0-based)
 * 日志: adb logcat -d | grep ObjProbe
 */
@PluginDescriptor(
        name = "ObjProbe(object点击受控探针)",
        description = "受控测试: scan附近object / click指定oid+slot, 观察门状态翻转",
        tags = {"探针", "object交互"},
        enabledByDefault = true
)
public final class ObjProbe implements Plugin {
    private PluginContext ctx;
    private Client client;
    private static final String CMD = "/data/local/tmp/objprobe.txt";
    private static final String OUT = "/data/local/tmp/objprobe_out.txt";
    private String lastCmd = "";
    private int poll = 0;
    private long observeAtTick = -1;
    private int watchOid = -1;

    @Override public void configure(PluginContext c) { this.ctx = c; this.client = c.getClient(); }
    @Override public void startUp() {
        ctx.log("[ObjProbe] 启动. 控制: " + CMD + " (scan / click <oid> [slot])");
        out("START " + pos());
    }
    @Override public void shutDown() { ctx.log("[ObjProbe] 停止"); }
    private void log(String s) { ctx.log("[ObjProbe] " + s); out(s); }
    /** 把结果写到 shell 可读文件(覆盖式, 每次命令一批), 避免面板日志被坐标刷屏淹没。 */
    private void out(String s) {
        try (java.io.FileWriter w = new java.io.FileWriter(OUT, true)) {
            w.write(s + "\n");
        } catch (Throwable ignore) {}
    }
    private void outReset() {
        try (java.io.FileWriter w = new java.io.FileWriter(OUT, false)) { w.write(""); }
        catch (Throwable ignore) {}
    }

    private List<Map<String,Object>> objs() {
        try { return GhostRuntime.api().objects(); } catch (Throwable e) { log("objects()异常 " + e); return null; }
    }
    private Map<String,Object> findObj(int oid) {
        List<Map<String,Object>> all = objs();
        if (all == null) return null;
        for (Map<String,Object> m : all) {
            int id = m.get("id") instanceof Number ? ((Number)m.get("id")).intValue() : 0;
            if (id == oid) return m;
        }
        return null;
    }
    private String desc(Map<String,Object> m) {
        if (m == null) return "null";
        int oid = m.get("id") instanceof Number ? ((Number)m.get("id")).intValue() : 0;
        int x = m.get("x") instanceof Number ? ((Number)m.get("x")).intValue() : 0;
        int y = m.get("y") instanceof Number ? ((Number)m.get("y")).intValue() : 0;
        int pl = m.get("plane") instanceof Number ? ((Number)m.get("plane")).intValue() : 0;
        String nm = String.valueOf(m.getOrDefault("name",""));
        StringBuilder ab = new StringBuilder();
        Object raw = m.get("actions");
        if (raw instanceof List) for (Object a : (List<?>)raw) if (a != null) ab.append(a).append('|');
        return "id=" + oid + " @(" + x + "," + y + ",p" + pl + ") N=" + nm + " A=" + ab;
    }
    private String pos() {
        WorldPoint me = client.getLocalPlayerLocation();
        return me == null ? "?" : ("(" + me.getX() + "," + me.getY() + ",p" + me.getPlane() + ")");
    }

    private void pollCmd(long tick) {
        if (++poll < 5) return;
        poll = 0;
        String line = null;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(CMD))) {
            line = r.readLine();
        } catch (java.io.FileNotFoundException e) { return; }
        catch (Exception e) { log("读指令异常 " + e); return; }
        if (line == null) return;
        line = line.trim();
        if (line.isEmpty() || line.equals(lastCmd)) return;
        lastCmd = line;
        String[] tok = line.split("\\s+");
        if (tok[0].equalsIgnoreCase("scan")) {
            outReset();
            WorldPoint me = client.getLocalPlayerLocation();
            if (me == null) { log("scan: 读不到坐标"); return; }
            List<Map<String,Object>> all = objs();
            log("scan 玩家=" + pos() + " 附近object(<=15格):");
            if (all == null) return;
            int n = 0;
            for (Map<String,Object> m : all) {
                int x = m.get("x") instanceof Number ? ((Number)m.get("x")).intValue() : 0;
                int y = m.get("y") instanceof Number ? ((Number)m.get("y")).intValue() : 0;
                int d = Math.abs(x - me.getX()) + Math.abs(y - me.getY());
                if (d <= 15) { log("  " + desc(m) + " dist=" + d); n++; }
            }
            log("scan 共 " + n + " 个");
        } else if (tok[0].equalsIgnoreCase("clicknear") && tok.length >= 2) {
            // clicknear <名字关键词> [slot]: 找名字含关键词、离玩家最近的 object, 用它的实时坐标发包
            outReset();
            String kw = tok[1].toLowerCase();
            int slot = tok.length >= 3 ? Integer.parseInt(tok[2]) : 0;
            WorldPoint me = client.getLocalPlayerLocation();
            List<Map<String,Object>> all = objs();
            if (me == null || all == null) { log("clicknear 读不到坐标/object"); return; }
            Map<String,Object> best = null; int bestD = 9999;
            for (Map<String,Object> m : all) {
                String nm = String.valueOf(m.getOrDefault("name","")).toLowerCase();
                if (!nm.contains(kw)) continue;
                int x = m.get("x") instanceof Number ? ((Number)m.get("x")).intValue() : 0;
                int y = m.get("y") instanceof Number ? ((Number)m.get("y")).intValue() : 0;
                int d = Math.abs(x-me.getX()) + Math.abs(y-me.getY());
                if (d < bestD) { bestD = d; best = m; }
            }
            if (best == null) { log("clicknear 找不到名字含 '" + kw + "' 的object"); return; }
            int oid = best.get("id") instanceof Number ? ((Number)best.get("id")).intValue() : 0;
            int x = best.get("x") instanceof Number ? ((Number)best.get("x")).intValue() : 0;
            int y = best.get("y") instanceof Number ? ((Number)best.get("y")).intValue() : 0;
            log("clicknear kw=" + kw + " pos=" + pos() + " -> " + desc(best) + " slot=" + slot);
            client.interactObjectSlot(oid, x, y, slot);
            log("clicknear 已发 interactObjectSlot(" + oid + "," + x + "," + y + "," + slot + ")");
            watchOid = oid;
            observeAtTick = tick + 40;
        } else if (tok[0].equalsIgnoreCase("click") && tok.length >= 2) {
            outReset();
            try {
                int oid = Integer.parseInt(tok[1]);
                int slot = tok.length >= 3 ? Integer.parseInt(tok[2]) : 0;
                // 找所有同 id object 里离玩家最近的一个(避免找到远处同 id 的门)
                WorldPoint me = client.getLocalPlayerLocation();
                List<Map<String,Object>> all = objs();
                Map<String,Object> m = null; int bestD = 9999;
                if (all != null && me != null) {
                    for (Map<String,Object> c : all) {
                        int cid = c.get("id") instanceof Number ? ((Number)c.get("id")).intValue() : 0;
                        if (cid != oid) continue;
                        int cx = c.get("x") instanceof Number ? ((Number)c.get("x")).intValue() : 0;
                        int cy = c.get("y") instanceof Number ? ((Number)c.get("y")).intValue() : 0;
                        int d = Math.abs(cx-me.getX()) + Math.abs(cy-me.getY());
                        if (d < bestD) { bestD = d; m = c; }
                    }
                }
                log("click BEFORE oid=" + oid + " slot=" + slot + " pos=" + pos() + " dist=" + bestD + " obj=" + desc(m));
                if (m == null) { log("click 找不到 oid=" + oid); return; }
                int x = m.get("x") instanceof Number ? ((Number)m.get("x")).intValue() : 0;
                int y = m.get("y") instanceof Number ? ((Number)m.get("y")).intValue() : 0;
                client.interactObjectSlot(oid, x, y, slot);
                log("click 已发 interactObjectSlot(" + oid + "," + x + "," + y + "," + slot + ")");
                watchOid = oid;
                observeAtTick = tick + 40;   // ~几秒后观察
            } catch (Exception e) { log("click异常 " + e); }
        }
    }

    @Subscribe public void onTick(GameTick tick) {
        pollCmd(tick.getTick());
        if (observeAtTick > 0 && tick.getTick() >= observeAtTick) {
            observeAtTick = -1;
            int oid = watchOid;
            log("click AFTER oid=" + oid + " pos=" + pos() + " obj=" + desc(findObj(oid)));
        }
    }
}
