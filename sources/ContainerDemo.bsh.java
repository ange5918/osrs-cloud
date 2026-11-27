// @desc 源码容器演示: 每10tick输出BBBB, 读me/moving/查询真值
// @version 1.0
// 说明: 这是 APK 内置 BeanShell 容器直接解释执行的 Java 源码。
//       不需要 javac+d8；悬浮窗点启动 -> 游戏容器 src ContainerDemo 即跑。
//       可用绑定: ctx / client / api / log() / print() / vars / name / version / tick

int hits = 0;

void onLoad() {
    log("BBBB : 源码容器已加载 " + name + " v" + version);
    log("BBBB : sha 见容器日志, tick=" + tick);
}

void onTick() {
    hits = hits + 1;
    if (hits % 10 != 0) return;

    String p = api.me();
    boolean moving = api.moving();
    int interacting = api.query("F_INTERACTING");
    int idle = api.query("F_IDLE");
    int pose = api.query("ACTOR_POSE_ANIMATION");

    log("BBBB : targeting=" + (interacting > 0)
        + " , moving=" + moving
        + " , idle=" + (idle > 0)
        + " , pose=" + pose
        + " , me=" + p
        + " , tick=" + tick);
}

void onUnload() {
    log("BBBB : 源码容器已卸载 " + name);
}
