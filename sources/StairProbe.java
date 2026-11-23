package stairprobe;

import com.astrastudio.api.Plugin;
import com.astrastudio.api.annotation.PluginDescriptor;
import com.astrastudio.api.annotation.Subscribe;
import com.astrastudio.api.event.GameTick;
import com.astrastudio.api.model.Client;
import com.astrastudio.api.model.WorldPoint;

/** 楼梯发包暴力探针：obj × 坐标 × payload格式，找到能让plane变化的组合。 */
@PluginDescriptor(name="楼梯探针",description="暴力定位楼梯发包真值",enabledByDefault=true)
public final class StairProbe implements Plugin {
    private PluginContext ctx; private Client client;
    private int[] OBJS={16671,16672,56230};
    private int[][] offs={{0,0},{0,1},{0,-1},{1,0},{-1,0}};
    private int[] FMT={88,103,103};       // 格式编号
    private int[] OPT={0,1,0};            // 103时带的opt
    private int idx=0, lastPlane=-1, total;
    private long sentTick=-1;
    private boolean done=false;

    public void configure(PluginContext c){ctx=c;client=c.getClient();}
    public void startUp(){
        idx=0;lastPlane=-1;total=OBJS.length*offs.length*FMT.length;
        ctx.log("[楼梯探针] 启动 共"+total+"组合");
    }
    public void shutDown(){}

    @Subscribe public void onTick(GameTick t){
        if(done||!client.isLoggedIn())return;
        WorldPoint me=client.getLocalPlayerLocation(); if(me==null)return;
        if(lastPlane<0)lastPlane=me.getPlane();
        if(me.getPlane()!=lastPlane){
            ctx.log("[楼梯探针] ★★★plane变化! "+lastPlane+"->"+me.getPlane()
                +" 命中组合idx="+idx+" (obj="+OBJS[(idx-1)/(offs.length*FMT.length)]+")");
            done=true;return;
        }
        long tc=client.getTickCount();
        if(sentTick>=0&&tc-sentTick<15)return;   // 300ms一个
        if(idx>=total){ctx.log("[楼梯探针] 全部试完 未变plane");done=true;return;}
        int oi=idx/(offs.length*FMT.length);
        int rem=idx%(offs.length*FMT.length);
        int ofi=rem/FMT.length, fi=rem%FMT.length;
        int oid=OBJS[oi], ox=offs[ofi][0], oy=offs[ofi][1];
        int x=me.getX()+ox, y=me.getY()+oy;
        sendObj(oid,x,y,FMT[fi],OPT[fi]);
        ctx.log("[楼梯探针] #"+idx+" fmt="+FMT[fi]+" opt="+OPT[fi]+" obj="+oid
            +" @("+x+","+y+") me="+me.getX()+","+me.getY()+",p"+me.getPlane());
        idx++; sentTick=tc;
    }
    private void sendObj(int oid,int x,int y,int fmt,int opt){
        int lo=oid&0xff,hi=(oid>>8)&0xff,b2=(oid>>16)&0xff;
        int X=x&0xffff,Y=y&0xffff;
        int xX=(X&0xff)^0x80,hiX=(X>>8)&0xff;
        int hiY=(Y>>8)&0xff,loY=Y&0xff,xY=(Y&0xff)^0x80;
        if(fmt==88){
            byte[] p={(byte)xX,(byte)hiX,(byte)hiY,(byte)loY,0,(byte)lo,(byte)hi,(byte)b2,0};
            client.sendPacket(88,p);
        } else {
            // rev240/241 object: [(X>>8),(X^0x80),(Y>>8),(Y^0x80),(id^0x80),(id>>8),opt,0x80]
            byte[] p={(byte)hiX,(byte)xX,(byte)hiY,(byte)xY,(byte)(lo^0x80),(byte)hi,(byte)opt,(byte)0x80};
            client.sendPacket(103,p);
        }
    }
}
