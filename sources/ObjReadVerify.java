package objread;

import com.astrastudio.api.Plugin;
import com.astrastudio.api.annotation.PluginDescriptor;
import com.astrastudio.api.annotation.Subscribe;
import com.astrastudio.api.event.GameTick;
import com.astrastudio.api.model.Client;
import com.astrastudio.api.model.TileObject;
import com.astrastudio.api.model.WorldPoint;

/** 验证 native object 读取偏移修复: 打印 getTileObjects() 结果 + 坐标 + actions 查询。 */
@PluginDescriptor(name="Object读取验证",description="验证native listObjects偏移修复",enabledByDefault=true)
public final class ObjReadVerify implements Plugin {
    private PluginContext ctx; private Client client;
    private boolean done=false;
    public void configure(PluginContext c){ctx=c;client=c.getClient();}
    public void startUp(){ctx.log("[ObjRead] ★ 启动 - 验证native object读取偏移"); done=false;}
    public void shutDown(){}
    @Subscribe public void onTick(GameTick t){
        try{
            if(done)return;
            WorldPoint me=client.getLocalPlayerLocation();
            if(me==null||!client.isLoggedIn())return;
            if(client.getTickCount()<25)return;
            done=true;
            ctx.log("[ObjRead] ★★★ me="+me.getX()+","+me.getY()+",p"+me.getPlane());
            java.util.List<TileObject> objs=client.getTileObjects();
            ctx.log("[ObjRead] 场景object总数="+objs.size());
            int shown=0;
            for(TileObject o:objs){
                WorldPoint p=o.getWorldLocation(); if(p==null)continue;
                int d=Math.max(Math.abs(p.getX()-me.getX()),Math.abs(p.getY()-me.getY()));
                if(d>12)continue;
                if(shown++<50){
                    ctx.log("[ObjRead] id="+o.getId()+" "+o.getName()+" @("+p.getX()+","+p.getY()+") d="+d);
                }
            }
            ctx.log("[ObjRead] === DONE (object读取验证) ===");
        }catch(Exception e){ctx.log("[ObjRead] ERR="+e); done=true;}
    }
}
