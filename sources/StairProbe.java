package stairprobe;

import com.astrastudio.api.Plugin;
import com.astrastudio.api.annotation.PluginDescriptor;
import com.astrastudio.api.annotation.Subscribe;
import com.astrastudio.api.event.GameTick;
import com.astrastudio.api.model.Client;
import com.astrastudio.api.model.WorldPoint;

/** 楼梯发包暴力探针：对当前位置周围格 × 候选obj 逐个发object包，找能上楼的坐标组合。 */
@PluginDescriptor(name="楼梯探针",description="暴力定位楼梯发包真值",enabledByDefault=true)
public final class StairProbe implements Plugin {
    private PluginContext ctx; private Client client;
    private int[] OBJS={16671,16672,56230,56231};
    private int idx=0, lastPlane=-1; private long sentTick=-1;
    private boolean done=false;

    public void configure(PluginContext c){ctx=c;client=c.getClient();}
    public void startUp(){idx=0;lastPlane=-1;ctx.log("[楼梯探针] 启动");}
    public void shutDown(){}

    @Subscribe public void onTick(GameTick t){
        if(done||!client.isLoggedIn())return;
        WorldPoint me=client.getLocalPlayerLocation(); if(me==null)return;
        if(lastPlane<0)lastPlane=me.getPlane();
        if(me.getPlane()>lastPlane){ctx.log("[楼梯探针] ★★★上楼成功! plane="+lastPlane+"->"+me.getPlane()+" 组合idx="+idx);done=true;return;}
        long tc=client.getTickCount();
        if(sentTick>=0&&tc-sentTick<25)return;
        // 候选坐标：当前格 + 四周
        int[][] offs={{0,0},{0,1},{0,-1},{1,0},{-1,0},{-1,1},{1,1},{-1,-1},{1,-1}};
        if(idx>=OBJS.length*offs.length){ctx.log("[楼梯探针] 全部试完 未上楼");done=true;return;}
        int oid=OBJS[idx/offs.length];
        int[] o=offs[idx%offs.length];
        int x=me.getX()+o[0], y=me.getY()+o[1];
        sendObj(oid,x,y);
        ctx.log("[楼梯探针] #"+idx+" obj="+oid+" @("+x+","+y+") me="+me.getX()+","+me.getY()+",p"+me.getPlane());
        idx++; sentTick=tc;
    }
    private void sendObj(int oid,int x,int y){
        int lo=oid&0xff,hi=(oid>>8)&0xff,b2=(oid>>16)&0xff;
        int X=x&0xffff,Y=y&0xffff;
        int xX=(X&0xff)^0x80,hiX=(X>>8)&0xff;
        int hiY=(Y>>8)&0xff,loY=Y&0xff;
        byte[] p={(byte)xX,(byte)hiX,(byte)hiY,(byte)loY,0,(byte)lo,(byte)hi,(byte)b2,0};
        client.sendPacket(88,p);
    }
}
