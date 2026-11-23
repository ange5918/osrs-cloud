package objfix;

import com.astrastudio.api.Plugin;
import com.astrastudio.api.annotation.PluginDescriptor;
import com.astrastudio.api.annotation.Subscribe;
import com.astrastudio.api.event.GameTick;
import com.astrastudio.api.model.Client;
import com.astrastudio.api.model.TileObject;
import com.astrastudio.api.model.WorldPoint;

/** 端到端验证新 native interactObject(object1=88,9字节payload) 能否换层/开门。
 *  阶段1: 打印 revision/fd/坐标/附近object。
 *  阶段2: 对楼梯/门候选逐个发 object1, 监听 plane 变化。 */
@PluginDescriptor(name="Object发包验证",description="验证native object1包换层",enabledByDefault=true)
public final class ObjFixVerify implements Plugin {
    private PluginContext ctx; private Client client;
    private boolean diagDone=false, fired=false;
    private long fireTick=-1;
    private int startPlane=-1;
    private TileObject target=null; private String targetAct="";
    private int candIdx=0;
    private java.util.List<TileObject> cands=new java.util.ArrayList<TileObject>();

    public void configure(PluginContext c){ctx=c;client=c.getClient();}
    public void startUp(){
        ctx.log("[ObjFix] ★ 启动 - 验证新native object1包(88/9字节)");
        diagDone=false;fired=false;startPlane=-1;candIdx=0;
    }
    public void shutDown(){}

    @Subscribe public void onTick(GameTick t){
        try{
            WorldPoint me=client.getLocalPlayerLocation();
            if(me==null||!client.isLoggedIn())return;
            long tc=client.getTickCount();
            if(startPlane<0)startPlane=me.getPlane();
            // 换层检测
            if(fired && me.getPlane()!=startPlane){
                ctx.log("[ObjFix] ★★★换层成功 plane "+startPlane+"->"+me.getPlane()
                    +" pos="+me.getX()+","+me.getY()+" targetId="+target.getId()+" act="+targetAct);
                fired=false;return;
            }
            // 阶段1: 一次性诊断
            if(!diagDone && tc>=30){
                diagDone=true;
                try{ctx.log("[ObjFix] revision="+client.getClass().getName());}catch(Exception e){}
                ctx.log("[ObjFix] me="+me.getX()+","+me.getY()+",p"+me.getPlane());
                java.util.List<TileObject> objs=client.getTileObjects();
                ctx.log("[ObjFix] 场景object总数="+objs.size());
                int shown=0;
                for(TileObject o:objs){
                    WorldPoint p=o.getWorldLocation(); if(p==null)continue;
                    int d=Math.max(Math.abs(p.getX()-me.getX()),Math.abs(p.getY()-me.getY()));
                    String n=o.getName();
                    boolean interesting=(n!=null&&(n.toLowerCase().contains("stair")||n.toLowerCase().contains("ladder")
                        ||n.toLowerCase().contains("door")||n.toLowerCase().contains("gate")||n.toLowerCase().contains("trap")));
                    if(interesting||d<=3){
                        if(shown++<40)ctx.log("[ObjFix] obj id="+o.getId()+" "+n+" @("+p.getX()+","+p.getY()+") d="+d);
                        if(interesting&&d<=6)cands.add(o);
                    }
                }
                ctx.log("[ObjFix] 可交互候选="+cands.size());
            }
            // 阶段2: 逐个候选发 object1 (每30tick=0.6s一个), 观察换层
            if(diagDone && cands.size()>0 && candIdx<cands.size()){
                if(fireTick>=0 && tc-fireTick<30)return;
                target=cands.get(candIdx);
                WorldPoint p=target.getWorldLocation();
                // 选 action: 名字含 stair/ladder -> Climb-down(在p2应该是下来) 或 Climb-up; door/gate -> Open
                String n=(target.getName()==null?"":target.getName()).toLowerCase();
                targetAct = (n.contains("door")||n.contains("gate"))?"Open":"Climb-up";
                ctx.log("[ObjFix] >>> #"+candIdx+" 发 object1 id="+target.getId()+" "+target.getName()
                    +" @("+p.getX()+","+p.getY()+") act="+targetAct+" (nativeInteractObject opt=1)");
                client.interactObject(target,targetAct);
                fireTick=tc; candIdx++; fired=true;
            } else if(diagDone && cands.size()==0 && !fired){
                ctx.log("[ObjFix] 附近无楼梯/门候选, 验证停在诊断阶段");
                fired=true;
            }
        }catch(Exception e){ctx.log("[ObjFix] ERR "+e);}
    }
}
