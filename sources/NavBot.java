package navbot;

import com.astrastudio.api.Plugin;
import com.astrastudio.api.annotation.PluginDescriptor;
import com.astrastudio.api.annotation.Subscribe;
import com.astrastudio.api.event.GameTick;
import com.astrastudio.api.model.Client;
import com.astrastudio.api.model.WorldPoint;

import java.util.*;
import java.util.zip.Inflater;

/**
 * 端上实时寻路 —— 官方 collision.astar3 的 1:1 精确复刻（纯 Java，零 PC 依赖）。
 * 图 = 官方 CollisionMap.steps3() 逐格邻接表(含墙/门/栅栏/单向边/跨层边)，zlib 压缩内嵌。
 * 反编译确认官方算法: h=0(Dijkstra, 贪心等价解唯一), 邻居直接查 steps3(代价全=1),
 * open 堆按 (f,g,tile) 排序 —— 已验证在图上与官方 astar3 逐点完全一致(9/9)。
 * 跨层交互发包仍用 transports 边表里的真实 object id。
 * 路线：当前 -> L2(3208,3219,2) -> GE2(3203,3473,2)。
 */
@PluginDescriptor(name = "实时寻路", description = "端上官方A*实时规划", enabledByDefault = true)
public final class NavBot implements Plugin {
    private PluginContext ctx;
    private Client client;

    // ★官方全图稀疏位图块(来自 OfficialNav.java, 覆盖全 OSRS 含 GE 区)
    private static final int X0=3100, Y0=3200, X1=3300, Y1=3500, PL=4;
    private static final int BS=64, PER=2; // [dirMask][costMask], bit i=方向 i 可走
    private static final int[] DIR_DX={-1,0,1,-1,1,-1,0,1};
    private static final int[] DIR_DY={-1,-1,-1,0,0,1,1,1};
    private static final String[] BLOCK_DATA = {
        "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",
        "V/nN6UxXeTxavezN6D3HP57O7SWeBWV2889ddr3Ffo4/rwUwlTY4/t7c5sLKif9uU/65yq4/y29ZOV+f/17+iZXze/z1mpme6gN+bddP878Lmv371ytTB+Q+",
        "FKPoKjv1TPW/My0QCAQCgUAgEAgEAoFAIBAIBJL/l/y75P//VSvp+z8ne2Cf0pLzOVbhF/7/h7/fLu0pex+7+M/8j872RXmF/0xu/Fv879J/4f80/1RdzceP",
        "8/8Bs84ro3ja7VzLsqM4DKWySpU3rFzs/CspvoYq/6xX/hZ2ngT8kGT5ASHJ9EyH6nvTCZdjy5Z09AA3uMKhB/v8qcCxf67IEc4eBvP8vQ7TsAzD9r/Xbxuv",
        "t27nwE/UIAaJrkrxdbzO4/lPbn+xv08vG/Gt/35+/s30fK8RmiP42v99uGp4rxD+6Mfu/Kxe49ERex9VuNrsvxf+nR3uaLavc0YkseF55eX52fo8M80LjnlF",
        "71f//3X7O+NHZeP8Vy9l+M6R8YVZqqeM7uAb468bxqy2EUs/XoV+79/t503x+tKfMRVGH2YwxRUdAD5cx7RWr3na+L/9dxgtPH+XhPRnyO0b+VwJy+zrfYfs",
        "f/FaewPGrP2VdcTQ8aqva93RaJ3/axdHcIsyFmAtg9SSJoS9+pqf9GN34Jyw88IMFzJ/i1bVoh0SpBx2i4i7VsZrzn7/wp1htzlIsp5Bs6a4KjO40uv8lbUe",
        "cIwDWEO4+2f/7ha/E2g9rdcaOL4gjX1nCqIvuebobafZ7ZN7HNMu8zmOxDy/VWjN9KbJr3NG/7n0WpPsxS3uEmoP02diOwvPbZfO9Pzt4o7VmUWb/TfJ8qzM",
        "AW0ZlvDod8G0rZJG197ndIuSDnNZo9xEtBczuDJn/dVmBXablFuc8O8GtNb5cx9k39L98xr7BCyfQFJIGj+DXWOBJitvBedtD5jCDuG9n/F4eD0MksE+gjVa",
        "DQfWX28zllHf1+hTew4NdkTCN2St56hbMmo69W/WX80x+BpY9lx/5eYhwr4XjJVx3gvpzOLtEpw2rVLRmuTz15nHS6sz+SOhqQJL0d6bGOBxddx5u73H8g/v",
        "qJVWZGSQFUALCvF3HhM8rgQrOPmr3LzuCIIfbA9dr31k4zb3JX6efBu0MInHOMAHICODrAHuL2jvErOyhB05oNer3w8OyW5BI1+79AvvIgH2syXSt4DJaTDG",
        "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",
        "qo9zNm4EOqsP5vV/Pld9hjHmI9CkF+zORCGGWJX9OMdXufq/Qflby9xP1RcvnxvBHdnEkan/l+pvV4ygbpN17Mx4n69zI7Cg309V+g/chfJ3Wf0/ZW9Eo//h",
        "Gvk7pv4/ZPc54vlfI38oP9Gs/wd8fv/LRr1Bs/W44bK4ifdJvQzqCvz69TToNMhZ1HISH9v/Nr4t5Cn0KXzOByxglvwzE0qM4wp82BNWe2aD6757tI1vUM7s",
        "+/gW1Rl/hb/8GF934csCe6/VRVQjswzxpeeOooB/3P7oakUiYeou/e/BL929XM7cDx34rmD5Sk8gsKyOlfF7fOLSwVR5/FIX41H/2z73GP71x/X4R1iaih1W",
        "uCPwPL4udET21f9UfGKHuAR/OJRlmU8wFO4eE+qjWv4s8RHad9XCF0xeNPn9pQvfEn/Qz3JFsWPQdnkZHv98hv4cviz4o3rUKZjOq3P4R/yHJfzXZPc4fxdf",
        "ZlbsCH4ty6gbjH0hPUNcB0xv9b2HIWn27v88I9OyP/oQD6lHDFw+85v4vK1YG10VQ9YZfB7/rL9td/1Q/ip+gn82YlY/wVcdtYX+rqujWS/NPGukta7iUpa3",
        "nuIn32CZrmK3zFfRj9SWzrPczz9/gO8b76ut9c6j5qVKT9Ls34lHos38Wy5KXLufs0D3QinaNMVugXKUcsZTlK5jmZ6xhM9HKVfil+1Ua92uwS/vgKHRf+cu",
        "wzespbiqFlIb83VX/87x+Z6ss7bk/Ixcx3Pg2rbk/IyO19/0xfgS3FWd+zb+ic4483qky46vv1v0JD9bXfN3MjBc/Q/f2+5YO7Bk+KZrL4hqj58GT6B0qLeo",
        "vuZr4Vl3pdmb4v0eK3keji72Cpd7uNv4tbvFNPLuv8RXB/BVpy7eM/wxq//3yD9/5pLt8IWt+WvUdb4e8EnrIYvdh389t9aFHE4NX18aLy3VrhqIrzqetff+",
        "HXic/cufc1+qP59/jZfU33NZwvtYTeyKLN3dejX+HO/mm1CnP5frP9f9X55P6+72+YIcgG5gfQafMo3v4mvQ8ftLfHqNd/Fl9WlX6uP4x57t8Wfgy0xL0yEu",
        "wJfN65U6r/LK/NLdq7D7/6teradQlPTjOnzd9aSXz+H/ev7fwTeFp3vI4jMsrsVvV6GuwzeMRy7hm4r/PoufeMfSxJ877eFx/KFyd+WV+I8f4u9PEg932nwb",
        "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",
        "6PlWvvQ38bPGz3qevzMHX/v2/sui5+MTJgrswPzWDtWw0a9WR3YWOE9XKsPX8RV6n80oZIj82mNJ4//2y08/d6bns/j/p+PvpZdeeumll1566eXfGP9/P/b4",
        "V/j+D635Xnvttdd/Uz2X2fCrnKlRjEpcu+ua/6HmaZY12OPLHqgJnTXSVXwb3+O+NaP018bvDn2b+OyX+cH4W9GnfAI5+am/Pf6z8f/PTul85n/7pZfunC3U",
        "LaZee/03aPHlo3u+scdrabFvZDFexT/WdH+D//0synfwr/+PW5/iq4P4/G/x/U/3/d2n8Nv9x6/qiKx/9Q/g31Bu21/nw1P7e/m6VH5n/2GamcW/HX/Ifti+",
        "LpXPjb+WafN38f9Uevy/l1566aWXXnrp5b9f/gfMeg7xeNrt3EtuqzAUgGHU0ZWYZIQyYyvRXQ2SN5uR18LMDW8bMDZ+gNr+9iANIXzg58FErQrlketP1l/3",
        "cmiqrNqURb+fHF+r/vMlT/uHpdfmDCZtvaUqyvE776KZv1sU/8f9w5LsjyUPfVW0o9jJj/56208WSXz1OeZaazc1YdbB8L1p27B/uC9W/iSbJTEout9t+zd/",
        "ntI3S3ZdDo/iOW4rtfNL71fGNevl0M6vz/nM0pZ/o5l7LXLbVtP6e3V/pT+ZyyizHZe698++LeTwldbal54nZ7s0jnGtLzbHyO/rrWDZ/p63hfpdSfr5eyOy",
        "jJz/7H1L7+2t9TOV2ffPYWkoyfv8YZ6RXhGQPhaki3+6ecblC0fEFOd/Of36M8+6ziAsNR7tT+xGjXpNdH/HtP/qtL+cxdHnPu3vuYkAXf7DqA2pzQThMag8",
        "qPvS0XqHcTjG95kB9tN7HsHC0hRhhfiPce6PiX8nxe2/rPNn3Ph77C8z3Zfj7i3cP5qB9WhUr/VmUwKhvjg1Ar1W90HX+EscIIxoSI8AwsvfPv7szXa2aCTc",
        "F5/6dEX3d8z/V/hH8/9f8Ic2LQ9afe74UzlHn7y+iI6/Y9a/7L3PN8fNv8or+t4fj2ot+rrCN/uDWN2X3ufHtj//8jf7Y4r7z3N+jv5X3urH936/ONnuv5Nc",
        "vUg895x9AlFn9cXuGlz81Reerf9uX80rzfa1qLz+9srXLSEslY57f5u//z4k/mk8V5/Mkk/l+4y/wrIC2iaKf6Tj2kuPOLTO5IvVKnPa0c/Xl5lGX5/y94vB",
        "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",
    };
    private static final String[] BLOCK_INDEX = {
        "12338 0 1237",
        "12339 1237 1097",
        "12340 2334 1659",
        "12341 3993 1005",
        "12342 4998 796",
        "12594 5794 1526",
        "12595 7320 1539",
        "12596 8859 1459",
        "12597 10318 2553",
        "12598 12871 1376",
        "12850 14247 2553",
        "12851 16800 1319",
        "12852 18119 1868",
        "12853 19987 3642",
        "12854 23629 2774",
        "13106 26403 798",
        "13107 27201 757",
        "13108 27958 1379",
        "13109 29337 1489",
        "13110 30826 1124",
    };
    private static final String[] EDGE_DATA = {
"3205,3228,0,3206,3229,1,16671",
        "3206,3229,1,3205,3228,0,16672",
        "3206,3229,0,3206,3229,1,16671",
        "3205,3228,1,3205,3228,0,16672",
        "3206,3229,1,3206,3229,2,16672",
        "3206,3229,2,3205,3228,1,16673",
        "3205,3228,1,3206,3229,2,16672",
        "3205,3209,1,3206,3208,0,16672",
        "3206,3208,0,3205,3209,1,16671",
        "3206,3208,1,3206,3208,0,16672",
        "3205,3209,0,3205,3209,1,16671",
        "3205,3209,1,3205,3209,2,16672",
        "3205,3209,2,3206,3208,1,16673",
        "3206,3208,1,3205,3209,2,16672",
        "3228,3213,0,3228,3213,1,16683",
        "3228,3213,1,3228,3213,0,16684",
        "3229,3214,0,3229,3214,1,16683",
        "3229,3214,1,3229,3214,0,16684",
        "3230,3213,0,3230,3213,1,16683",
        "3230,3213,1,3230,3213,0,16684",
        "3229,3212,0,3229,3212,1,16683",
        "3229,3212,1,3229,3212,0,16684",
        "3229,3212,1,3229,3212,2,16684",
        "3228,3213,2,3228,3213,1,16679",
        "3228,3213,1,3228,3213,2,16684",
        "3229,3214,2,3229,3214,1,16679",
        "3229,3214,1,3229,3214,2,16684",
        "3230,3213,2,3230,3213,1,16679",
        "3230,3213,1,3230,3213,2,16684",
        "3229,3212,2,3229,3212,1,16679",
        "3229,3225,2,3229,3225,1,16679",
        "3229,3225,1,3229,3225,2,16684",
        "3228,3224,2,3228,3224,1,16679",
        "3228,3224,1,3228,3224,2,16684",
        "3229,3223,2,3229,3223,1,16679",
        "3229,3223,1,3229,3223,2,16684",
        "3230,3224,2,3230,3224,1,16679",
        "3230,3224,1,3230,3224,2,16684",
        "3230,3224,1,3230,3224,0,16684",
        "3230,3224,0,3230,3224,1,16683",
        "3229,3223,0,3229,3223,1,16683",
        "3228,3224,0,3228,3224,1,16683",
        "3228,3224,1,3228,3224,0,16684",
        "3229,3223,1,3229,3223,0,16684",
        "3229,3225,0,3229,3225,1,16683",
        "3229,3225,1,3229,3225,0,16684",
        "3211,3243,1,3211,3243,0,16679",
        "3211,3243,0,3211,3243,1,16683",
        "3164,3306,0,3164,3306,1,12964",
        "3164,3306,1,3164,3306,2,12965",
        "3164,3306,2,3164,3306,1,12966",
        "3165,3307,1,3165,3307,2,12965",
        "3165,3307,2,3165,3307,1,12966",
        "3165,3307,1,3165,3307,0,12965",
        "3165,3307,0,3165,3307,1,12964",
        "3164,3306,1,3164,3306,0,12965",
        "3140,3304,0,3140,3304,1,16683",
        "3140,3304,1,3140,3304,0,16679",
        "3141,3305,0,3141,3305,1,16683",
        "3141,3305,1,3141,3305,0,16679",
        "3140,3306,0,3140,3306,1,16683",
        "3140,3306,1,3140,3306,0,16679",
        "3139,3305,0,3139,3305,1,16683",
        "3139,3305,1,3139,3305,0,16679",
        "3105,3364,2,3106,3363,1,9584",
        "3106,3363,1,3105,3364,2,11511",
        "3106,3362,1,3105,3364,2,11511",
        "3108,3361,0,3108,3366,1,11498",
        "3108,3366,1,3108,3361,0,11499",
        "3109,3361,0,3109,3366,1,11498",
        "3109,3366,1,3109,3361,0,11499",
        "3212,3472,0,3212,3476,1,11807",
        "3212,3476,1,3212,3472,0,11799",
        "3213,3472,0,3213,3476,1,11807",
        "3213,3476,1,3213,3472,0,11799",
        "3203,3496,0,3204,3497,1,11790",
        "3204,3497,1,3203,3496,0,11792",
        "3204,3497,1,3204,3497,2,11792",
        "3204,3497,2,3203,3496,1,11793",
        "3204,3497,2,3204,3497,3,11801",
        "3204,3497,3,3204,3497,2,11802",
        "3220,3496,1,3219,3495,0,11793",
        "3219,3495,0,3220,3496,1,11789",
        "3218,3495,0,3220,3496,1,11789",
        "3223,3472,1,3223,3472,2,11801",
        "3223,3472,2,3223,3472,1,11802",
        "3223,3472,3,3222,3471,2,11793",
        "3222,3471,2,3223,3472,3,11790",
        "3221,3471,2,3223,3472,3,11790",
        "3238,3490,0,3242,3490,1,11797",
        "3242,3490,1,3238,3490,0,11799",
        "3238,3489,0,3242,3489,1,11797",
        "3242,3489,1,3238,3489,0,11799",
        "3257,3487,0,3258,3486,1,11790",
        "3258,3486,1,3257,3487,0,11792",
        "3257,3488,0,3258,3486,1,11790",
        "3258,3486,1,3258,3486,2,11792",
        "3258,3486,2,3257,3487,1,11792",
        "3258,3486,2,3258,3486,3,11792",
        "3258,3486,3,3257,3487,2,11793",
        "3239,3447,1,3238,3446,0,11793",
        "3238,3446,0,3239,3447,1,11789",
        "3237,3446,0,3239,3447,1,11789",
        "3266,3451,0,3266,3455,1,11798",
        "3266,3455,1,3266,3451,0,11799",
        "3267,3451,0,3267,3455,1,11798",
        "3267,3455,1,3267,3451,0,11799",
        "3254,3442,1,3254,3446,2,11797",
        "3254,3446,2,3254,3442,1,11799",
        "3253,3442,1,3253,3446,2,11797",
        "3253,3446,2,3253,3442,1,11799",
        "3257,3421,1,3256,3420,0,11793",
        "3256,3420,0,3257,3421,1,11789",
        "3255,3420,0,3257,3421,1,11789",
        "3257,3422,1,3256,3420,0,11793",
        "3234,3424,0,3234,3424,1,11794",
        "3234,3424,1,3234,3424,0,11795",
        "3230,3394,1,3226,3394,0,11799",
        "3226,3394,0,3230,3394,1,11796",
        "3230,3393,1,3226,3393,0,11799",
        "3226,3393,0,3230,3393,1,11796",
        "3230,3386,0,3230,3382,1,11797",
        "3230,3382,1,3230,3386,0,11799",
        "3231,3386,0,3231,3382,1,11797",
        "3231,3382,1,3231,3386,0,11799",
        "3222,3387,1,3222,3387,0,11802",
        "3222,3387,0,3222,3387,1,11794",
        "3185,3447,0,3185,3447,1,11794",
        "3185,3447,1,3185,3447,0,11795",
        "3159,3436,0,3155,3436,1,11797",
        "3155,3436,1,3159,3436,0,11799",
        "3159,3435,0,3155,3435,1,11797",
        "3155,3435,1,3159,3435,0,11799",
        "3186,3414,0,3186,3414,1,11794",
        "3186,3414,1,3186,3414,0,11795",
        "3205,3396,0,3205,3396,1,11794",
        "3205,3396,1,3205,3396,0,11795",
        "3203,3389,1,3205,3389,0,11802",
        "3203,3389,0,3203,3389,1,11801",
        "3262,3402,0,3262,3402,1,11794",
        "3262,3402,1,3262,3402,0,11795",
        "3283,3397,1,3283,3397,0,11802",
        "3283,3397,0,3283,3397,1,11801",
        "3202,3435,1,3202,3435,0,11795",
        "3202,3435,0,3202,3435,1,11794",
        "3246,3409,0,3246,3409,1,11794",
        "3246,3409,1,3246,3409,0,11795",
        "3202,3417,1,3202,3417,0,11795",
        "3202,3417,0,3202,3417,1,11794",
        "3214,3411,0,3214,3411,1,11794",
        "3214,3411,1,3214,3411,0,11795",
        "3179,3401,0,3179,3401,1,11801",
        "3179,3401,1,3179,3401,0,11802",
        "3175,3421,0,3175,3421,1,11801",
        "3175,3421,1,3175,3421,0,11802",
        "3267,3379,0,3269,3379,1,11794",
        "3267,3379,1,3267,3379,0,11795",
        "3188,3358,0,3188,3354,1,11797",
        "3188,3354,1,3188,3358,0,11799",
        "3189,3358,0,3189,3354,1,11797",
        "3189,3354,1,3189,3358,0,11799"
    };
    // ★官方 actions 表: "oid:upSlot:downSlot", slot=actions.index(wantact) 0-based
    //   (PC Ghost opener/act_invoke 同款算法, 源 re/ghost/objects_defs.json)
    private static final String[] CLIMB_SLOT = {
"10:-1:0","11:0:-1","54:0:-1","55:-1:0","56:0:-1","57:-1:0","60:0:-1","96:0:-1","98:-1:0","101:0:-1","132:0:-1","133:-1:0","195:0:-1","245:0:-1","246:-1:0","272:0:-1","273:-1:0","287:-1:0","882:-1:0","1581:-1:0","1582:-1:0","1738:-1:0","1747:0:-1","2114:0:-1","2118:-1:0","2119:0:-1","2120:-1:0","2121:0:-1","2122:-1:0","2147:-1:0","2148:0:-1","2187:-1:0","2190:-1:0","2268:0:-1","2269:-1:0","2352:0:-1","2353:0:-1","2405:0:-1","2408:-1:0","2410:0:-1","2447:0:-1","2448:-1:0","2517:0:-1","2542:0:-1","2544:-1:0","2590:-1:0","2592:0:-1","2605:-1:0","2608:0:-1","2609:1:2","2610:-1:0","2616:-1:0","2617:0:-1","2641:0:-1","2711:0:-1","2796:0:-1","2797:-1:0","2812:-1:0","2823:-1:0","2833:0:-1","2853:0:-1","2871:-1:0","2872:0:-1","2935:-1:0","3264:-1:0","3415:-1:0","3416:0:-1","3433:-1:0","3546:-1:0","3617:-1:0","3618:0:-1","3788:0:-1","3789:-1:0","3828:-1:0","3829:0:-1","3832:0:-1","4004:-1:0","4005:-1:0","4060:0:-1","4139:-1:0","4158:-1:0","4159:0:-1","4160:0:-1","4161:0:-1","4163:0:-1","4164:0:-1","4173:-1:0","4187:2:1","4188:0:-1","4189:-1:0","4458:0:-1","4568:0:-1","4569:1:2","4570:-1:0","4620:-1:0","4621:-1:0","4622:0:-1","4623:0:-1","4624:-1:0","4625:-1:0","4626:0:-1","4627:0:-1","4643:0:-1","4644:-1:0","4645:0:-1","4646:-1:0","4647:0:-1","4648:-1:0","4713:-1:0","4743:0:-1","4744:-1:0","4755:-1:0","4756:0:-1","4772:0:-1","4773:0:-1","4774:0:-1","4775:0:-1","4776:-1:0","4777:-1:0","4778:-1:0","4779:-1:0","4780:-1:0","4781:0:-1","4880:-1:0","4911:-1:0","4912:-1:0","4965:-1:0","4966:0:-1","4967:-1:0","4968:0:-1","4969:-1:0","4970:0:-1","5054:0:-1","5206:0:-1","5207:-1:0","5250:-1:0","5251:0:-1","5269:0:-1","5270:-1:0","5415:0:-1","5416:-1:0","5488:0:-1","5491:-1:0","5493:0:-1","5813:-1:0","5947:-1:0","6085:0:-1","6086:-1:0","6087:0:-1","6088:-1:0","6089:0:-1","6090:-1:0","6242:0:-1","6243:-1:0","6244:0:-1","6245:-1:0","6260:-1:0","6261:0:-1","6262:1:2","6279:-1:0","6280:0:-1","6281:0:-1","6312:0:-1","6313:-1:0","6372:0:-1","6373:-1:0","6418:0:-1","6419:0:-1","6435:-1:0","6436:0:-1","6439:0:-1","6450:0:-1","6497:-1:0","6498:-1:0","6499:-1:0","6500:-1:0","6501:0:-1","6502:0:-1","6503:0:-1","6504:0:-1","6523:-1:2","6561:-1:0","6645:0:-1","6648:0:-1","6649:-1:0","6841:-1:0","6842:0:-1","7142:-1:0","7221:-1:1","8744:0:-1","8745:1:2","8746:-1:0","8785:0:-1","8956:1:2","9084:-1:0","9138:0:-1","9558:0:-1","9559:-1:0","9560:-1:0","9582:0:-1","9584:-1:0","9725:0:-1","9726:-1:0","9727:0:-1","9728:-1:0","9742:0:-1","9744:0:-1","9745:-1:0","9978:0:-1","9979:-1:0","10015:0:-1","10016:-1:0","10017:0:-1","10018:-1:0","10036:-1:0","10038:0:-1","10042:-1:0","10136:0:-1","10137:-1:0","10167:0:-1","10168:-1:0","10177:1:2","10193:0:-1","10194:0:-1","10195:-1:0","10196:0:-1","10197:-1:0","10198:0:-1","10199:-1:0","10200:0:-1","10201:-1:0","10202:0:-1","10203:0:-1","10204:-1:0","10205:-1:0","10206:0:-1","10207:0:-1","10208:-1:0","10209:-1:0","10210:0:-1","10211:0:-1","10212:-1:0","10213:-1:0","10214:0:-1","10215:-1:0","10216:0:-1","10217:0:-1","10218:-1:0","10219:0:-1","10220:-1:0","10221:-1:0","10222:0:-1","10223:-1:0","10224:0:-1","10225:-1:0","10226:0:-1","10227:-1:0","10228:0:-1","10229:0:-1","10287:0:-1","10288:-1:0","10308:-1:0","10309:0:-1","10319:-1:0","10321:-1:0","10322:-1:0","10323:0:-1","10416:-1:0","10493:0:-1","10494:-1:0","10525:0:-1","10526:-1:0","10554:0:-1","10557:-1:0","10559:-1:0","10560:0:-1","10771:0:-1","10773:-1:0","10774:-1:0","10775:0:-1","10776:-1:0","10857:0:-1","10858:-1:0","11041:0:-1","11042:-1:0","11043:0:-1","11044:-1:0","11050:-1:0","11198:0:-1","11199:-1:0","11200:0:-1","11201:-1:0","11244:-1:0","11289:0:-1","11290:-1:0","11308:0:-1","11309:-1:0","11358:0:-1","11417:-1:0","11441:-1:0","11498:0:-1","11499:-1:0","11511:0:-1","11632:-1:0","11635:0:-1","11637:-1:0","11641:0:-1","11661:0:-1","11662:-1:0","11666:0:-1","11668:-1:0","11789:0:-1","11790:0:-1","11791:1:2","11792:1:2","11793:-1:0","11794:0:-1","11795:-1:0","11796:0:-1","11797:0:-1","11798:0:-1","11799:-1:0","11800:-1:0","11801:0:-1","11802:-1:0","11803:-1:0","11804:0:-1","11805:0:-1","11806:0:-1","11807:0:-1","11867:-1:0","11888:0:-1","11889:1:2","11890:-1:0","12112:0:-1","12113:-1:0","12254:-1:0","12255:0:-1","12389:-1:0","12390:0:-1","12391:0:-1","12536:0:-1","12537:1:2","12538:-1:0","12745:-1:0","12748:0:-1","12763:-1:0","12764:0:-1","12779:0:-1","12780:0:-1","12781:-1:0","12906:-1:0","12907:0:-1","12964:0:-1","12965:1:2","12966:-1:0","13497:0:-1","13498:-1:0","13499:0:-1","13500:-1:0","13501:0:-1","13502:-1:0","13503:1:2","13505:1:2","13999:0:-1","14735:0:-1","14736:1:2","14737:-1:0","14745:0:-1","14746:-1:0","14747:1:2","14758:-1:0","14880:-1:0","14927:0:-1","14995:0:-1","14996:-1:0","15115:0:-1","15116:-1:0","15191:0:-1","15202:-1:0","15413:-1:0","15608:0:-1","15645:0:-1","15646:0:-1","15647:0:-1","15648:-1:0","15649:0:-1","15650:-1:0","15651:-1:0","15652:-1:0","15653:0:-1","15654:-1:0","15655:-1:0","15656:-1:0","15657:0:-1","15746:0:-1","15747:0:-1","15756:-1:0","15890:0:-1","15892:-1:0","15893:0:-1","15895:-1:0","15896:0:-1","15898:-1:0","15899:0:-1","15901:-1:0","16060:0:-1","16062:-1:0","16066:-1:0","16108:0:-1","16109:-1:0","16110:0:-1","16111:0:-1","16112:-1:0","16114:-1:0","16450:0:-1","16537:0:-1","16538:-1:0","16556:-1:0","16646:0:-1","16647:-1:0","16663:-1:0","16664:-1:0","16665:0:-1","16667:-1:0","16668:0:-1","16669:-1:0","16670:0:-1","16671:0:-1","16672:1:2","16673:-1:0","16674:-1:0","16675:0:-1","16676:1:2","16677:-1:0","16678:-1:0","16679:-1:0","16680:-1:0","16681:-1:0","16682:-1:0","16683:0:-1","16684:1:2","16685:-1:0","16732:-1:0","16733:-1:0","16734:0:-1","16735:0:-1","16736:-1:0","17026:0:-1","17027:1:2","17028:0:-1","17120:0:-1","17121:1:2","17122:-1:0","17143:0:-1","17148:0:-1","17149:-1:0","17155:-1:0","17159:0:-1","17160:-1:0","17383:1:0","17384:-1:0","17385:0:-1","17386:-1:0","17387:0:-1","17388:-1:0","17389:-1:0","17391:0:-1","17959:0:-1","17960:-1:0","17971:-1:0","17972:-1:0","17974:0:-1","17975:-1:0","17976:0:-1","17977:0:-1","17978:-1:0","17979:-1:0","17985:-1:0","17986:0:-1","17995:-1:1","17996:0:-1","17997:0:-1","17998:-1:1","17999:-1:1","18000:1:-1","18001:-1:1","18002:1:-1","18034:-1:0","18036:0:-1","18049:-1:0","18050:0:-1","18069:-1:0","18086:0:-1","18087:-1:0","18095:0:-1","18096:-1:0","18105:0:-1","18106:-1:0","18107:-1:0","18108:0:-1","18234:0:-1","18235:-1:0","18236:0:-1","18237:-1:0","18270:-1:0","18324:0:-1","18325:-1:0","18354:0:-1","18596:-1:0","18597:-1:0","18598:0:-1","18599:0:-1","18610:0:-1","18611:-1:0","18833:-1:0","18834:0:-1","18843:-1:0","18903:0:-1","18904:-1:0","18922:-1:0","18923:0:-1","18924:-1:0","18987:-1:0","18988:0:-1","18989:-1:0","18990:0:-1","18991:0:-1","18992:-1:0","19001:0:-1","19002:-1:0","19003:0:-1","19004:-1:0","20056:0:-1","20193:-1:0","20194:0:-1","20226:-1:0","20227:0:-1","20275:-1:0","20277:0:-1","20278:-1:0","20280:0:-1","20281:0:-1","20284:0:-1","20285:-1:0","20286:0:-1","20287:-1:0","20340:-1:0","20353:0:-1","20354:-1:0","20356:0:-1","20357:-1:0","20667:0:-1","20668:0:-1","20669:0:-1","20670:0:-1","20671:0:-1","20672:0:-1","20673:0:-1","20782:0:-1","20783:-1:0","20784:0:-1","20785:-1:0","20790:-1:0","21395:0:-1","21396:-1:0","21455:-1:0","21512:0:-1","21513:-1:0","21514:0:-1","21515:-1:0","21578:0:-1","21592:0:-1","21593:-1:0","21781:0:-1","21782:-1:0","21871:0:-1","21872:-1:0","21922:-1:0","22121:0:-1","22122:-1:0","22164:-1:0","22172:0:-1","22173:-1:0","22247:0:-1","22248:0:-1","22250:0:-1","22252:-1:0","22253:-1:0","22254:-1:0","22354:-1:0","22432:0:-1","22433:1:2","22434:-1:0","22600:0:-1","22601:0:-1","22602:-1:0","22608:0:-1","22609:-1:0","22650:0:-1","22651:-1:0","22666:-1:0","22667:-1:0","22931:0:-1","22932:-1:0","22933:-1:0","22934:0:-1","22935:-1:0","22936:-1:0","22937:0:-1","22938:-1:0","22939:0:-1","22940:-1:0","22941:0:-1","22942:-1:0","23279:-1:0","23376:-1:0","23377:0:-1","23504:0:-1","23531:0:-1","23532:-1:0","23543:0:-1","23544:0:-1","23545:0:-1","23560:-1:0","23561:-1:0","23562:0:-1","23563:0:-1","23564:-1:0","23584:0:-1","23585:-1:0","23703:0:-1","23704:-1:0","23705:0:-1","23706:-1:0","23732:0:-1","23920:-1:0","23921:0:-1","23968:-1:0","23969:0:-1","24067:0:-1","24068:-1:0","24070:0:-1","24071:-1:0","24072:0:-1","24074:-1:0","24075:0:-1","24076:-1:0","24077:0:-1","24078:-1:0","24079:0:-1","24080:-1:0","24082:0:-1","24083:0:-1","24084:-1:0","24085:-1:0","24248:1:2","24249:1:2","24250:0:-1","24251:0:-1","24252:-1:0","24253:-1:0","24254:-1:0","24255:-1:0","24303:-1:0","24351:0:-1","24352:-1:0","24358:0:-1","24359:-1:0","24687:0:-1","24717:0:-1","24718:-1:0","24771:-1:2","24772:-1:2","24795:0:-1","24796:-1:0","24819:-1:0","25038:0:-1","25045:-1:0","25336:0:-1","25338:-1:0","25339:0:-1","25340:-1:0","25431:0:-1","25432:-1:0","25434:-1:0","25437:-1:0","25604:-1:0","25606:-1:0","25662:-1:0","25663:0:-1","25682:0:-1","25683:-1:0","25687:0:-1","25688:-1:0","25786:0:-1","25787:-1:0","25801:-1:0","25843:-1:0","25844:0:-1","25935:0:-1","25938:0:-1","25939:-1:0","25940:-1:0","25941:0:-1","26017:-1:0","26106:0:-1","26107:0:-1","26118:0:-1","26119:-1:0","26243:-1:0","26372:-1:1","26374:0:-1","26376:-1:1","26378:0:-1","26418:-1:0","26422:-1:0","28618:0:-1","28619:-1:0","28620:-1:0","28657:0:-1","28660:-1:0","28676:0:-1","28687:0:-1","28775:0:-1","28801:-1:0","28857:0:-1","28858:-1:0","28894:0:-1","28895:0:-1","28896:0:-1","28897:0:-1","28898:0:-1","29062:0:-1","29089:-1:0","29995:0:-1","29996:0:-1","30139:0:-1","30179:-1:0","30191:-1:0","30192:0:-1","30234:0:-1","30367:-1:0","30725:-1:0","30727:0:-1","30844:0:-1","30845:0:-1","30846:-1:0","30847:0:-1","30849:-1:0","30850:-1:0","31029:-1:0","31030:0:-1","31031:-1:0","31032:0:-1","31558:0:-1","31609:0:-1","31610:-1:0","31611:0:-1","31612:1:2","31613:-1:0","31627:0:-1","31692:-1:0","31704:0:-1","31705:-1:0","31707:-1:0","31708:0:-1","31733:0:-1","31734:-1:0","31758:0:-1","31759:-1:0","31996:-1:0","31998:0:-1","32080:-1:0","32081:0:-1","32082:-1:0","32083:0:-1","32084:-1:0","32089:0:-1","32112:0:-1","32205:0:-1","32206:-1:0","32211:-1:0","32212:0:-1","32321:0:-1","32322:0:-1","32323:0:-1","32324:0:-1","32325:0:-1","32326:0:-1","32327:0:-1","32393:0:-1","32394:-1:0","32395:-1:0","32396:0:-1","32397:-1:0","32398:0:-1","32399:-1:0","32400:0:-1","32401:0:-1","32480:0:-1","32543:0:-1","32549:0:-1","32551:0:-1","32554:0:-1","32555:-1:0","32560:-1:0","32561:0:-1","32578:-1:0","32579:0:-1","32637:-1:0","32638:0:-1","32641:-1:0","32642:0:-1","32643:-1:0","32644:0:-1","32645:0:-1","32646:-1:0","32647:0:-1","32648:-1:0","33235:0:-1","33483:-1:0","33484:0:-1","33485:-1:0","33486:0:-1","33549:0:-1","33550:0:-1","33551:-1:0","33552:-1:0","34477:0:-1","34478:-1:0","34502:0:-1","34503:-1:0","34862:-1:0","34863:0:-1","34864:0:-1","34865:-1:0","35387:0:-1","35388:-1:0","35389:1:2","35791:-1:0","35792:0:-1","35799:0:-1","37831:0:-1","37832:0:-1","37833:-1:0","37835:0:-1","37836:-1:0","37942:-1:0","37943:0:-1","37955:0:-1","37956:-1:0","37981:-1:0","38452:-1:0","38453:-1:0","38463:-1:0","38464:-1:0","38466:-1:0","38467:-1:0","38468:-1:0","38474:-1:0","38601:0:-1","38602:-1:0","38604:-1:0","38605:0:-1","38606:-1:0","38608:-1:0","38609:0:-1","38610:-1:0","38695:-1:0","38697:-1:0","38850:-1:0","38852:-1:0","38985:-1:0","38986:0:-1","38987:-1:0","39068:0:-1","39069:0:-1","39092:-1:0","39093:0:-1","39172:-1:0","39173:0:-1","39491:0:-1","39492:-1:0","39644:0:-1","39645:-1:0","40309:-1:0","40330:0:-1","40421:-1:0","40745:-1:0","40746:0:-1","41427:-1:0","41428:0:-1","41806:0:-1","41853:0:-1","41916:-1:0","41917:0:-1","41922:-1:0","41923:0:-1","41924:-1:0","41925:0:-1","41930:0:-1","42042:-1:0","42043:0:-1","42193:0:-1","42194:-1:0","42195:0:-1","42196:-1:0","42207:0:-1","42208:-1:0","42295:0:-1","42296:0:-1","42297:0:-1","42350:0:-1","42487:-1:0","42523:-1:0","43261:-1:0","43868:0:-1","43955:0:-1","43956:0:-1","43957:0:-1","43958:-1:0","43959:-1:0","44924:0:-1","44925:0:-1","45509:0:-1","46326:-1:0","46327:0:-1","46329:-1:0","46330:0:-1","46332:-1:0","46333:0:-1","46335:-1:0","46336:0:-1","46704:0:-1","46705:-1:0","46716:0:-1","46717:-1:0","46811:0:-1","46812:-1:0","47149:0:-1","47314:-1:0","47315:0:-1","47817:0:-1","47818:-1:0","48040:0:-1","48041:0:-1","48042:1:2","48043:-1:0","48046:0:-1","48047:-1:0","48198:0:-1","48299:0:-1","48303:0:-1","48304:-1:0","48730:0:-1","48732:0:-1","48795:0:-1","48797:-1:0","48919:0:-1","48924:0:-1","49700:0:-1","49701:-1:0","50143:0:-1","50503:0:-1","50504:0:-1","50505:-1:0","50508:0:-1","50509:-1:0","50859:-1:0","50860:0:-1","51551:0:-1","51554:-1:0","51555:0:-1","51556:-1:0","51641:-1:0","51642:0:-1","51643:0:-1","51644:-1:0","51854:0:-1","51855:-1:0","51856:-1:0","52012:0:-1","52013:-1:0","52614:0:-1","52616:0:-1","52618:1:2","52619:1:2","52620:-1:0","52621:-1:0","52622:0:-1","52623:-1:0","52624:0:-1","52625:-1:0","52626:0:-1","52627:-1:0","52628:0:-1","52629:-1:0","52630:0:-1","52631:1:2","52632:-1:0","52633:0:-1","52634:-1:0","52635:0:-1","52636:-1:0","52637:-1:0","52638:0:-1","52640:-1:0","52641:-1:0","52642:0:-1","52643:0:-1","53045:-1:0","53046:0:-1","53248:0:-1","53249:-1:0","53255:0:-1","53279:-1:0","53319:0:-1","53320:-1:0","53347:0:-1","53348:-1:0","53350:-1:0","53358:0:-1","53370:0:-1","53371:-1:0","53513:0:-1","53607:0:-1","53608:-1:0","53623:0:-1","53624:0:-1","53895:-1:0","53896:0:-1","53924:0:-1","53927:-1:0","53947:0:-1","53948:-1:0","54369:0:-1","54518:-1:0","54525:-1:0","54526:0:-1","54527:-1:0","54530:0:-1","54531:-1:0","54532:0:-1","54535:0:-1","54540:0:-1","54541:-1:0","54542:0:-1","54713:0:-1","54714:-1:0","54716:0:-1","54717:0:-1","54782:0:-1","54783:-1:0","54784:-1:0","54945:0:-1","54946:-1:0","54947:0:-1","54948:-1:0","56229:-1:0","56230:0:-1","56231:-1:0","56232:1:2","56233:1:2","56427:0:-1","56428:-1:0","56468:0:-1","56469:-1:0","56476:-1:0","56477:0:-1","56511:0:-1","56518:0:-1","56606:0:-1","56607:-1:0","56663:-1:0","56664:0:-1","56665:-1:0","56666:0:-1","56667:-1:0","57357:0:-1","57358:-1:0","57677:0:-1","57678:-1:0","57890:0:-1","57891:-1:0","58027:0:-1","58029:-1:0","58273:0:-1","58274:-1:0","60020:0:-1","60021:1:2","60023:-1:0","60074:-1:0","60075:-1:0","60076:0:-1","60093:-1:0","60094:0:-1","60731:0:-1","60732:-1:0","61189:0:-1","61190:0:-1","61207:0:-1","61208:0:-1","61209:-1:0","61210:-1:0","61599:0:-1","61600:0:-1","61602:0:-1","61604:-1:0","61605:-1:0","61606:-1:0","61608:-1:0","61609:-1:0","62150:0:-1","62151:0:-1","62152:-1:0","62153:-1:0","62172:0:-1","62173:-1:0","62176:0:-1","62177:0:-1","62178:0:-1","62179:0:-1","62180:-1:0","62181:-1:0","62182:0:-1","62254:-1:0","62255:0:-1","62414:0:-1","62415:-1:0","62517:0:-1","63326:0:-1"
    };
    private static int[] climbSlot(int oid){
        for(String e:CLIMB_SLOT){
            int a=e.indexOf(':'),b=e.indexOf(':',a+1);
            if(Integer.parseInt(e.substring(0,a))==oid)
                return new int[]{Integer.parseInt(e.substring(a+1,b)),Integer.parseInt(e.substring(b+1))};
        }
        return null;
    }
    // 官方: opt = slot+1; 单动作物体回退 opt1。up 用 Climb-up slot, down 用 Climb-down slot。
    private static int climbOpt(int oid,boolean up){
        int[] s=climbSlot(oid);
        int slot=(s==null)?-1:(up?s[0]:s[1]);
        if(slot<0)slot=0;
        return slot+1;
    }
    // ★官方门/闸 ObjectDef.actions 真值表: "oid:Open,Close..." (源 objects_defs.json)。
    //   设备端读不到 action，但 def id 是实时的：Open=关着(要点开)，Close=已开(不是障碍)。
    private static final String[] DOOR_ACTS = {
"3:Open","4:Open","22:Open","24:Open","37:Open","38:Open","47:Open","48:Open","52:Open","53:Open","59:Open","71:Open,Knock-at","72:Open,Knock-at","73:Open","74:Open","79:Open","80:Open","81:Open","82:Open","89:Open","90:Open","92:Open","93:Open","94:Open","95:Open","99:Open","100:Open","102:Open","131:Open","134:Open","135:Open","136:Open","166:Open","167:Open","190:Open","532:Open","553:Open","777:Open","778:Close","779:Open","883:Open","954:Close","1281:Open","1511:Open","1512:Close","1513:Open","1516:Close","1517:Open","1518:Open","1519:Close","1520:Close","1521:Open","1522:Close","1524:Open","1525:Close","1535:Open","1536:Close","1540:Open","1541:Close","1543:Open","1544:Close","1546:Open","1547:Close","1549:Open","1550:Close","1551:Open","1552:Close","1558:Open","1559:Close","1560:Open","1561:Open","1562:Open","1563:Close","1564:Close","1567:Close","1568:Open","1569:Open","1571:Close","1572:Close","1579:Open","1580:Open","1581:Climb-down,Close","1582:Climb-down,Close","1584:Open","1585:Close","1586:Open","1587:Close","1600:Open","1601:Open","1722:Open","1725:Open","1726:Open","1727:Open","1728:Open","1730:Open","1731:Open","1732:Open","1733:Open","1762:Open","1804:Open","1805:Open","1967:Open","1968:Open","1991:Open","2000:Open","2002:Open","2010:Open","2032:Open","2034:Open","2036:Open","2039:Open","2041:Open","2048:Open","2049:Open","2050:Open","2051:Open","2054:Open","2058:Open","2060:Open","2069:Open","2100:Open","2101:Close","2102:Open","2103:Close","2104:Open","2105:Close","2108:Open","2111:Open","2112:Close","2113:Close","2115:Open","2116:Open","2143:Open","2144:Open","2154:Open","2155:Open","2184:Open","2199:Open","2200:Open","2240:Open,Search","2241:Open,Search","2246:Open,Search","2247:Open,Search","2248:Open,Search","2249:Open,Search","2250:Open,Search","2255:Open,Search","2256:Open,Search","2259:Open","2260:Open","2261:Open","2262:Open","2337:Open","2338:Open","2339:Open","2391:Open,Search","2392:Open,Search","2394:Open","2397:Open","2398:Open","2399:Open","2406:Open","2407:Open","2411:Open","2416:Open","2417:Close","2427:Open","2428:Open","2429:Open","2430:Open","2431:Open","2432:Open","2433:Open","2438:Open","2439:Open","2446:Open","2526:Open","2528:Open","2537:Open","2546:Open","2547:Close","2548:Open","2549:Close","2595:Open","2596:Open","2597:Open","2598:Open","2599:Open","2600:Open","2601:Open","2602:Open","2621:Open","2622:Open","2623:Open","2624:Open","2625:Open","2626:Open","2627:Open","2628:Open","2631:Open","2673:Open,Search","2674:Open,Search","2675:Open,Watch","2676:Open,Watch","2685:Open,Search","2686:Open,Search","2687:Open,Search","2688:Open,Search","2689:Open","2690:Open","2691:Open","2692:Open","2705:Open","2706:Open","2786:Open","2787:Open","2788:Open","2789:Open","2814:Open","2815:Open","2861:Open","2862:Open","2863:Open","2865:Open","2866:Open","2881:Open","2896:Open","2897:Open","2912:Open,Search","2913:Open,Search","2922:Open","2923:Open","2930:Open,Search","3220:Open","3221:Open","3270:Open","3271:Close","3333:Open","3334:Open","3432:Open","3433:Climb-down,Close","3444:Open","3445:Open","3463:Open","3489:Open","3490:Open","3506:Open","3507:Open","3725:Open","3726:Open","3727:Close","3728:Close","3743:Open","3745:Open","3746:Open","3747:Open","3762:Open","3763:Open","3764:Close","3776:Open","3777:Close","3810:Open","4106:Open","4107:Open","4108:Open","4109:Open","4132:Open","4133:Open","4148:Open","4165:Open","4166:Open","4173:Climb-down,Close","4174:Open","4247:Open","4248:Close","4250:Open","4251:Close","4311:Open","4312:Open","4423:Open,Attack","4424:Open,Attack","4425:Close","4426:Close","4427:Open,Attack","4428:Open,Attack","4429:Close","4430:Close","4471:Open","4472:Open","4629:Open","4630:Close","4631:Open","4632:Open","4633:Open","4634:Close","4636:Open","4637:Open","4638:Open","4639:Close","4640:Open","4672:Open","4696:Open","4697:Close","4710:Open","4712:Open","4713:Climb-down,Close","4787:Open","4788:Open","4807:Open","4879:Open","4880:Climb-down,Close","4888:Open","4962:Open","4963:Open","4964:Open","5055:Open","5056:Open","5057:Open","5060:Open","5061:Open","5172:Open","5174:Open","5183:Open","5186:Open","5187:Close","5188:Close","5244:Open","5245:Close","5453:Open","5454:Open","5490:Open,None,None,None,Pick-Lock","5491:Climb-down,Close","5501:Open,None,None,None,Pick-lock","5667:Open","5799:Open","5800:Open","5801:Open","5802:Open","5803:Open","5804:Open","5805:Open","5806:Open","5887:Open","5888:Close","5889:Open","5890:Close","6100:Open","6101:Close","6102:Open","6103:Close","6104:Open","6105:Close","6106:Open","6107:Close","6108:Open","6109:Close","6110:Open","6111:Close","6112:Open","6113:Close","6114:Open","6115:Close","6238:Open","6240:Open","6278:Open","6363:Open","6434:Open","6451:Open","6452:Open","6494:Open","6553:Open","6555:Open","6614:Open","6624:Open","6625:Open","6626:Close","6627:Close","6871:Open","6872:Open","6919:Open","6920:Open","6943:Open","6975:Open","6976:Close","6977:Open","7222:Open","7231:Open","7232:Open","7233:Open","7234:Open","7256:Open","7259:Open","7260:Close","7274:Open","7302:Open","7317:Open","7320:Open","7323:Open","7326:Open","7354:Open","7373:Open","7374:Close","7452:Open","7453:Close","7454:Open","8695:Open","8696:Close","8738:Open","8739:Open","8740:Open","8741:Open","8783:Open","8786:Open","8787:Open","8788:Open","8789:Open","8791:Close","8810:Open","8811:Open","8812:Close","8813:Close","8818:Open","8819:Close","8820:Open","8821:Close","8958:Open","8959:Open","8960:Open","9038:Open,Quick-Pay(100)","9039:Open,Quick-Pay(100)","9140:Open","9141:Open","9316:Open","9317:Open","9318:Open","9319:Open","9398:Open","9470:Open","9562:Open","9563:Open","9564:Close","9565:Open,None,None,None,Pick-lock","9708:Open","9709:Open","9710:Open","9716:Open","9717:Open","9718:Open","9719:Open","9720:Open","9721:Open","9722:Open","9723:Open","9724:Open","10043:Open","10045:Open","10172:Open","10260:Open","10261:Close","10262:Open","10263:Close","10264:Open","10265:Close","10318:Open","10325:Open","10327:Close","10418:Open","10419:Open","10420:Open","10421:Open","10423:Open","10425:Open","10427:Open","10429:Open","10431:Open","10558:Open","10559:Climb-down,Close","10698:Open","11051:Open","11053:Open","11055:Open","11057:Open","11064:Open","11066:Open","11068:Open","11070:Open","11151:Open","11152:Open","11196:Open","11197:Open","11450:Open","11470:Open","11471:Close","11483:Open","11616:Open","11617:Close","11620:Open","11621:Open","11624:Close","11625:Close","11636:Open","11637:Climb-down,Close","11665:Open","11667:Open","11668:Climb-down,Close","11719:Open,Pick-lock","11720:Open,Pick-lock","11721:Open,Pick-lock","11722:Open,Pick-lock","11723:Open,Pick-lock","11724:Open,Pick-lock","11725:Open,Pick-lock","11726:Open,Pick-lock","11727:Open,Pick-lock","11728:Open,Pick-lock","11763:Open","11766:Open","11767:Open","11770:Close","11771:Close","11772:Close","11773:Open","11774:Close","11775:Open","11776:Close","11777:Open","11778:Close","11780:Open","11782:Close","11783:Open","11951:Open","11952:Open","11953:Open","11954:Open","11987:Open","12045:Open","12046:Close","12047:Open","12048:Close","12267:Open","12268:Go-down,Close","12348:Open","12349:Open","12350:Open","12444:Open","12445:Close","12446:Open","12447:Close","12448:Open","12449:Close","12467:Open","12468:Open","12617:Open","12639:Open","12657:Open","12719:Open","12723:Open","12725:Open","12744:Open","12745:Climb-down,Close","12816:Open","12817:Open","12986:Open","12987:Open","12988:Close","12989:Close","13001:Open","13002:Close","13006:Open","13007:Open","13008:Close","13009:Close","13015:Open","13016:Open","13017:Close","13018:Close","13094:Open","13095:Close","13096:Open","13097:Close","13100:Open","13101:Open","13102:Close","13103:Close","13107:Open","13108:Close","13109:Open","13110:Close","13118:Open","13119:Open","13120:Close","13121:Close","13314:Open,Pick-lock,Force,None,Remove","13315:Close","13317:Open,Pick-lock,Force,None,Remove","13318:Close","13320:Open,Pick-lock,Force,None,Remove","13321:Close","13323:Open,Pick-lock,Force,None,Remove","13324:Close","13326:Open,Pick-lock,Force,None,Remove","13327:Close","13344:Open,Pick-lock,Force,None,Remove","13345:Open,Pick-lock,Force,None,Remove","13346:Open,Pick-lock,Force,None,Remove","13347:Open,Pick-lock,Force,None,Remove","13348:Open,Pick-lock,Force,None,Remove","13349:Open,Pick-lock,Force,None,Remove","13350:Close","13351:Close","13352:Close","13353:Close","13354:Close","13355:Close","13675:Open,None,None,Remove-room,Remove","13676:Open,None,None,Remove-room,Remove","13677:Open,None,None,Remove-room,Remove","13678:Go-down,Close,None,Remove-room,Remove","13679:Go-down,Close,None,Remove-room,Remove","13680:Go-down,Close,None,Remove-room,Remove","14233:Open","14234:Close","14235:Open","14236:Close","14237:Open,None,Repair","14238:Close,None,Repair","14239:Open,None,Repair","14240:Close,None,Repair","14241:Open,None,Repair","14242:Close,None,Repair","14243:Open,None,Repair","14244:Close,None,Repair","14245:Open,None,Repair","14246:Close,None,Repair","14247:Open,None,Repair","14248:Close,None,Repair","14749:Open","14750:Close","14751:Open","14752:Open","14753:Close","14754:Close","14910:Open","14974:Open","14979:Open","14980:Open","14981:Open","14982:Open","14983:Open","15056:Open","15204:Open","15205:Close","15510:Open","15511:Close","15512:Open","15513:Close","15514:Open","15515:Close","15516:Open","15517:Close","15523:Open","15604:Open","15605:Open","15658:Open","15660:Open","15752:Open","15755:Open,Pick-lock","15759:Open,None,None,Pick-lock","15984:Open","16113:Open","16114:Climb-down,Close","16774:Open","16776:Open","16777:Close","16778:Open","16779:Close","16902:Open","16903:Close","17009:Open","17089:Open","17090:Close","17091:Open","17092:Close","17093:Open","17094:Close","17100:Open","17114:Open","17115:Close","17316:Open","17317:Close","17600:Open","17601:Open","17911:Open","17912:Open","17973:Open","17985:Climb-down,Close","18031:Close","18032:Close","18047:Open","18057:Open","18060:Open","18063:Open","18091:Open","18092:Open","18168:Open","18413:Open","18414:Open","18415:Open","18489:Open","18490:Close","18698:Open","18699:Close","18700:Open","18701:Close","18702:Open","18703:Open","19206:Open","19207:Open","19914:Open","19915:Open","19916:Open","19917:Open","19918:Open","20045:Open","20046:Open","20049:Close","20050:Close","20087:Close","20088:Close","20089:Close","20090:Close","20341:Open","20679:Open","20698:Open","20817:Open","20837:Open","20838:Open","20847:Open","20848:Open","20849:Open","20850:Open","20925:Open","21065:Open","21068:Open","21160:Open","21161:Close","21162:Open","21164:Open","21167:Open","21169:Open","21170:Open","21171:Open","21172:Open","21243:Open","21340:Open","21341:Open","21342:Close","21343:Close","21397:Open","21398:Close","21399:Open","21400:Open","21401:Close","21402:Close","21403:Open","21404:Close","21405:Open","21406:Close","21505:Open","21506:Close","21507:Open","21508:Close","21600:Open","21601:Close","21814:Open","21921:Open","21922:Climb-down,Close","22007:Open","22113:Open","22114:Open","22115:Open","22116:Open","22117:Open","22118:Open","22435:Open","22436:Close","22437:Open","22438:Close","22778:Close","22779:Open","22913:Close","22914:Open","22920:Close","22921:Open","22945:Open","23056:Open","23216:Open","23217:Close","23338:Open","23339:Close","23340:Open","23341:Close","23342:Open","23343:Close","23378:Open","23552:Open","23554:Open","23555:Open","23917:Open","23918:Close","23919:Close","23972:Open","23973:Close","24050:Open","24051:Close","24052:Open","24053:Close","24054:Open","24055:Close","24056:Open","24057:Open","24058:Close","24059:Open","24060:Open","24061:Close","24062:Open","24063:Close","24064:Open","24065:Close","24066:Close","24293:Open","24294:Open","24306:Open","24307:Close","24309:Open","24310:Close","24312:Open","24313:Close","24315:Open","24316:Close","24318:Open","24319:Close","24363:Close","24364:Open","24365:Close","24366:Open","24368:Close","24369:Open","24536:Open","24560:Open","24561:Open","24564:Open","24565:Open","24567:Open","24685:Close","24686:Open","24759:Open","24815:Close","24816:Open","24958:Open","25115:Open","25118:Open","25214:Open","25341:Open","25417:Close","25418:Open","25526:Open","25527:Open","25594:Open","25595:Open","25638:Open","25639:Close","25640:Open","25641:Close","25642:Open","25643:Close","25716:Open","25717:Close","25718:Open","25719:Close","25748:Open","25750:Open","25788:Open","25789:Close","25790:Open","25791:Close","25799:Open","25800:Close","25804:Open","25805:Close","25806:Open","25807:Close","25813:Open","25814:Open","25815:Close","25816:Close","25819:Open","25820:Close","25825:Open","25826:Close","25827:Open","25828:Close","25876:Open","25877:Close","26081:Open","26082:Open","26130:Open","26131:Open","26132:Close","26133:Close","26205:Close","26207:Close","26461:Open","26760:Open,Peek","27084:Open","27085:Open","27086:Close","27087:Close","27443:Open","27444:Close","27445:Open","27485:Open","27486:Open","27487:Close","27488:Close","28456:Open","28457:Open","28458:Open","28459:Open","28460:Open","28461:Open","28462:Open","28463:Open","28464:Close","28465:Close","28466:Close","28467:Close","28468:Close","28469:Close","28470:Close","28471:Close","28479:Open","28480:Open","28481:Close","28482:Close","28651:Open","28783:Open,Picklock","28794:Open","28851:Open","28852:Open","29066:Open","29486:Open","29487:Open","29488:Open,None,Quick-exit","29489:Open,None,Quick-exit","30110:Open","30111:Open","30112:Open","30113:Open","30114:Open","30115:Open","30116:Open","30117:Open","30118:Open","30119:Open","30164:Open","30165:Open","30166:Open","30167:Open","30364:Open","30365:Open","30366:Open","30387:Open","30388:Open","30975:Open","31953:Open","31954:Open","32052:Open","32208:Open","32404:Open","32405:Open","32406:Open","32407:Open","32464:Open","32465:Open","32534:Open,Check-Count","32562:Open","32577:Open","32578:Climb-down,Close","33155:Open","33156:Open","33491:Open","33556:Open","33557:Close","33570:Open","33572:Open","34463:Open","34464:Open","34469:Open","34470:Open","34473:Open","34474:Open","34553:Open,Quick-open","34554:Open,Quick-open","34842:Open","34843:Open","34844:Open","34926:Open","34927:Open","34928:Close","34929:Close","35795:Open","35815:Open","35931:Open","35932:Open","36253:Open","36254:Close","37455:Open","37456:Close","37457:Open","37463:Open","37464:Close","37465:Open","37466:Close","37937:Open","37938:Open","37940:Open","37941:Open","37948:Open","37952:Open","37953:Open","37954:Open","37961:Open","37963:Open","37964:Open","38012:Open","38013:Open","38071:Open","39251:Open","39253:Open","39372:Open","39373:Close","39402:Open","39406:Open","39408:Close","39481:Open","39482:Close","39507:Open","39510:Open","39512:Open","39657:Open","39658:Open","39882:Close","40178:Open,Pick-lock","40325:Open","40419:Open,None,Peek","40858:Open","40859:Close","40860:Open","40861:Close","41210:Open","41537:Open","41600:Open","41602:Close","41801:Open","41861:Open","41862:Close","41870:Open","41877:Open","41902:Open","41904:Close","41915:Open","42116:Open","42117:Close","42118:Open","42119:Close","42120:Open","42121:Close","42122:Open","42123:Close","42124:Open","42125:Close","42126:Open","42127:Close","42529:Open","42840:Open","42841:Open","42931:Open","42932:Open","42933:Open","42934:Open","43087:Open","43088:Open","43278:Open","43960:Open","43961:Open","43962:Open","43963:Open","44036:Open","44038:Open","44040:Open","44042:Open","44043:Open","44050:Open","44051:Open","44052:Open,None,None,Pay-toll(10gp)","44053:Open,None,None,Pay-toll(10gp)","44059:Open","44060:Open","44784:Open","44785:Close","44916:Open","44920:Open","44921:Open","46253:Open","46254:Close","46601:Open","46602:Open","46603:Open","46658:Open","46659:Close","46660:Open","46661:Close","46863:Open","46864:Close","48254:Open","48255:Open","48256:Close","48257:Close","48258:Open","48259:Open","48260:Close","48261:Close","48262:Open","48264:Close","48266:Open","48267:Open","48270:Close","48271:Close","48274:Open","48276:Close","48278:Open","48279:Open","48282:Close","48283:Close","48286:Open","48287:Close","48289:Open","48290:Open","48291:Close","48292:Close","48301:Open","48724:Open","48725:Open","48774:Open","49098:Open","49118:Open","49120:Open","49122:Open","49124:Open","49126:Open","49128:Open","49130:Open","49132:Open","49138:Open","49140:Open","49599:Open","49657:Open","49742:Open","49743:Close","49889:Open","50048:Open","50051:Open","50141:Open","50149:Open","50150:Open","50152:Open","50211:Open","50510:Open","50511:Open","50513:Open","50514:Open","50517:Open","50518:Open","50528:Open","50529:Open","50537:Open","50800:Open","50801:Open","50861:Open","51998:Open","52388:Open","52389:Close","52594:Open","52596:Close","52597:Open","52599:Close","52600:Open","52601:Close","52602:Open","52603:Close","52604:Open","52605:Open","52606:Close","52607:Open","52608:Open","52609:Close","52821:Open","53100:Open","53101:Open","53102:Close","53103:Close","53278:Open","53281:Open","53313:Open","53314:Open","53317:Close","53318:Close","53321:Close","53322:Open","53365:Open","53366:Open","53441:Open","53443:Open","53445:Open","53446:Close","53478:Open","53479:Open","53893:Open","53894:Close","53897:Open","53899:Open","53902:Open","53903:Open","53904:Open","53905:Open","53906:Open","53907:Open","53921:Open","53922:Open","54364:Open","54365:Open,None,Pick-lock","54695:Open","54706:Open","55096:Open","55097:Open","56147:Open","56148:Close","56149:Open","56150:Close","56158:Open","56159:Open","56160:Close","56161:Close","56376:Open","56377:Close","56507:Open","56509:Open","56512:Open","56513:Open","56528:Open","56539:Open","56540:Close","56632:Open","56634:Open","56644:Open","58062:Open","58063:Close","58064:Open","58065:Close","58097:Open","58116:Open","58117:Close","58276:None,Open","58277:Close","58280:Open","58281:Close","60018:Open","60221:Open","60222:Open","60223:Close","60224:Close","60228:Open","61336:Open","61337:Open","61338:Open","61339:Open","61352:Open","61361:Open","61362:Close","61550:Open","61819:Open","61820:Open","61821:Open","61822:Open","61823:Open","61824:Open","61825:Open","61826:Open","61827:Open","61828:Open","61829:Open","61830:Open","61831:Open","61832:Open","61833:Open","61834:Open","61835:Open","61836:Open","61837:Open","61838:Open","61839:Open","61841:Open","61842:Open","61843:Open","61844:Open","61845:Open","61846:Open","61847:Open","61849:Open","61854:Open","62368:Open","62369:Open"
    };
    private static String doorActs(int oid){
        for(String e:DOOR_ACTS){
            int a=e.indexOf(':');
            if(a>0 && Integer.parseInt(e.substring(0,a))==oid) return e.substring(a+1);
        }
        return "";
    }

    private byte[] packedBlocks;
    private int[] blockKeys,blockOffs,blockLens;
    private final Map<Integer,byte[]> blockCache=new LinkedHashMap<Integer,byte[]>(){
        protected boolean removeEldestEntry(Map.Entry<Integer,byte[]> e){return size()>48;}
    };
    private Map<Long,List<Edge>> edgeIdx=new HashMap<>();

    // ★GE楼梯 object 真坐标表 (oid -> 交互锚点x,y)。op88 的坐标字段填的是 object 锚点格,
    //   不是玩家脚格/边起点。11807 由早上 05:07 实抓成功回执确认:
    //     invoke_object_at(11807,3212,3473,0) plane0->1 成功, 玩家站(3213,3472)。
    //   边起点(3212,3472)/(3213,3472)是玩家脚格, 用作发包坐标会 ret=10 但服务器不换层。
    //   格式 "oid,x,y"; 未命中的 oid 回退到边锚点(老逻辑)。
    private static final String[] GE_STAIR_XY = {
        "11807,3212,3473",
        "11801,3224,3472"
    };
    private static int[] geStairXY(int oid){
        for(String e:GE_STAIR_XY){
            String[] f=e.split(",");
            if(Integer.parseInt(f[0])==oid) return new int[]{Integer.parseInt(f[1]),Integer.parseInt(f[2])};
        }
        return null;
    }

    private static final class Edge {
        int sx,sy,sp,dx,dy,dp,oid;
        Edge(String s){ String[] f=s.split(","); sx=Integer.parseInt(f[0]); sy=Integer.parseInt(f[1]);
            sp=Integer.parseInt(f[2]); dx=Integer.parseInt(f[3]); dy=Integer.parseInt(f[4]);
            dp=Integer.parseInt(f[5]); oid=Integer.parseInt(f[6]); }
    }
    private static final class Pt {
        int x,y,p; double g;
        int oid=-1;       // ★跨层边自带真实 object id (GE 楼梯 11807 等)
        int ex=-1,ey=-1;  // ★跨层边起点锚点 (楼梯 object 所在格), 交互发包用
        Pt(int x,int y,int p){this.x=x;this.y=y;this.p=p;}
        public boolean equals(Object o){if(!(o instanceof Pt))return false;Pt q=(Pt)o;
            return x==q.x&&y==q.y&&p==q.p;}
        public int hashCode(){return (x*73856093)^(y*19349663)^(p*83492791);}
    }

    private int goalX,goalY,goalP; private String goalName;
    private List<Pt> path=new ArrayList<>(); private int pathIdx=0;
    private long lastWalkTick=-1,lastLogTick=-1,lastInteractTick=-1,legStartTick=-1;
    private int stuckTicks=0,lastX=-1,lastY=-1,interactTries=0;
    private boolean finished=false,reachedL2=false;

    // ===== ★PC Ghost opener 1:1 移植 (OfficialNav.tryOpenObstacle) =====
    // 障碍全是 object(门/闸/梯子), 靠"路径 cost=2 边定位 + 现场 object name/actions 白名单"验证,
    // 不靠静态坐标猜, 也不靠盲目轮换走位。
    private static final String[] OBJ_NAMES={"door","gate","stair","ladder","trapdoor","hatch",
        "manhole","shortcut","opening","entrance","exit","stile","fence","railing","barrier"};
    private static final String[] PASS_ACTS={"open","close","climb-up","climb-down","climb up",
        "climb down","climb-over","climb over","cross","jump","jump-to","jump to","enter",
        "pass-through","pass through","squeeze-through","squeeze through","climb","use"};
    private static final String[] INVOKE_PRIORITY={"climb-up","climb up","climb-down","climb down",
        "climb-over","climb over","cross","open","jump","jump-to","jump to","enter",
        "pass-through","pass through","squeeze-through","squeeze through","climb","use"};
    private static final int OBS_NEAR=2, MAP_R=16;
    private long lastDoorInvokeTick=-1,lastOpenerDiag=-1;
    private final java.util.Map<String,Long> crossGateTick=new java.util.HashMap<>();
    private final java.util.Map<String,int[]> crossGateFoot=new java.util.HashMap<>();
    private final java.util.Map<String,Integer> doorOscN=new java.util.HashMap<>();
    private final java.util.Map<String,Integer> fakeDoorN=new java.util.HashMap<>();
    private static final class ObsObj { int id,x,y,pl; String name; java.util.List<String> acts; boolean stair; }

    public void configure(PluginContext c){
        ctx=c;client=c.getClient();
        // ★确保 GhostRuntime 已装当前 client (Bootstrap 通常已 install, 这里幂等兜底),
        //   否则 api().objects()/invokeObject() 会走 client=null 的安全默认返回空。
        try{ com.ghost.api.shim.GhostRuntime.install(client); }catch(Throwable e){}
    }

    public void startUp(){
        try{
            StringBuilder sb=new StringBuilder();
            for(String s:BLOCK_DATA) sb.append(s);
            packedBlocks=Base64.getDecoder().decode(sb.toString().replaceAll("\\s",""));
            int nb=BLOCK_INDEX.length;
            blockKeys=new int[nb];blockOffs=new int[nb];blockLens=new int[nb];
            for(int i=0;i<nb;i++){
                String[] f=BLOCK_INDEX[i].trim().split("\\s+");
                blockKeys[i]=Integer.parseInt(f[0]);blockOffs[i]=Integer.parseInt(f[1]);
                blockLens[i]=Integer.parseInt(f[2]);
            }
            for(String e:EDGE_DATA){Edge ed=new Edge(e);
                edgeIdx.computeIfAbsent(key(ed.sx,ed.sy,ed.sp),k->new ArrayList<>()).add(ed);}
        }catch(Throwable e){ctx.log("[实时导航] 数据初始化失败: "+e);finished=true;return;}
        ctx.log("[实时导航] 启动 官方全图块"+blockKeys.length+"块 "+packedBlocks.length+"字节 边"+EDGE_DATA.length+"条");
        // ★断点续走: 只要不在L2出发区(距L2<=12格)就直奔GE2, 不回头(避免反复穿越断线区)
        WorldPoint st=client.getLocalPlayerLocation();
        int dl2 = (st==null)?0:(Math.abs(st.getX()-3208)+Math.abs(st.getY()-3219));
        if(st!=null && dl2>12){
            reachedL2=true; setGoal(3203,3473,2,"GE2");
            ctx.log("[实时导航] ★断点续走: 当前"+st+" 距L2="+dl2+" 直奔GE2");
        }else{
            setGoal(3208,3219,2,"L2");
        }
    }
    public void shutDown(){ctx.log("[实时导航] 停止");}

    private void setGoal(int x,int y,int p,String n){
        goalX=x;goalY=y;goalP=p;goalName=n;path.clear();pathIdx=0;
        lastWalkTick=-1;lastInteractTick=-1;stuckTicks=0;interactTries=0;
        legStartTick=client.getTickCount();
        ctx.log("[实时导航] ★目标 "+n+" ("+x+","+y+",p"+p+")");
    }
    private static long key(int x,int y,int p){return ((long)x<<24)|((long)y<<2)|p;}
    // 官方 steps3(x,y,p) 邻居: 直接读邻接表, 返回 [(nx,ny,np,cost)...]
    private int steps3(int x,int y,int p,int[] out){
        if(x<X0||x>X1||y<Y0||y>Y1||p<0||p>=PL) return 0;
        int bkey=((x>>6)<<8)|(y>>6);
        int bi=Arrays.binarySearch(blockKeys,bkey);
        if(bi<0) return 0;
        byte[] g=getBlock(bi);
        int off=((p*BS+(y&63))*BS+(x&63))*PER;
        int dirMask=g[off]&0xff, costMask=g[off+1]&0xff, m=0;
        for(int i=0;i<8;i++){
            if((dirMask&(1<<i))==0) continue;
            out[m*4]=x+DIR_DX[i];out[m*4+1]=y+DIR_DY[i];out[m*4+2]=p;
            out[m*4+3]=(costMask&(1<<i))!=0?2:1;m++;
        }
        return m;
    }
    private byte[] getBlock(int bi){
        byte[] b=blockCache.get(bi); if(b!=null)return b;
        byte[] raw=new byte[BS*BS*PL*PER];
        Inflater inf=new Inflater(); inf.setInput(packedBlocks,blockOffs[bi],blockLens[bi]);
        int n=0;
        try{ n=inf.inflate(raw); }catch(java.util.zip.DataFormatException e){return new byte[BS*BS*PL*PER];}
        finally{inf.end();}
        b=(n==raw.length)?raw:Arrays.copyOf(raw,n);
        blockCache.put(bi,b); return b;
    }
    private int mask(int x,int y,int p){ return steps3(x,y,p,new int[32])>0?0x0f:0; }
    private boolean standable(int x,int y,int p){ return mask(x,y,p)!=0; }
    private void forNeighbours(int x,int y,int p,long[] out){} // 保留空桩, 兼容旧引用

    /** 官方 astar3 的 1:1 精确复刻: h=0(Dijkstra), 邻居查 steps3, 堆按 (f,g,tile) 排序。 */
    private List<Pt> astar(int sx,int sy,int sp,int gx,int gy,int gp){
        final long GOAL=key(gx,gy,gp);
        PriorityQueue<Pt> open=new PriorityQueue<>(new Comparator<Pt>(){
            // 官方 heap 三元组 (f,g,tile): f=g(h=0), 再按 g, 再按 tile 坐标
            public int compare(Pt a,Pt b){
                int c=Double.compare(a.g,b.g);
                if(c!=0)return c;
                c=Integer.compare(a.x,b.x); if(c!=0)return c;
                c=Integer.compare(a.y,b.y); if(c!=0)return c;
                return Integer.compare(a.p,b.p);
            }
        });
        Map<Long,PtState> best=new HashMap<>();
        Pt s=new Pt(sx,sy,sp); s.g=0; open.add(s);
        best.put(key(sx,sy,sp),new PtState(s,null));
        int iter=0; int[] nb=new int[8*4];
        while(!open.isEmpty()&&iter++<1000000){
            Pt c=open.poll();
            PtState cs=best.get(key(c.x,c.y,c.p));
            if(cs==null||c.g>cs.p.g+1e-6) continue;
            if(key(c.x,c.y,c.p)==GOAL){
                LinkedList<Pt> out=new LinkedList<>(); PtState cur=cs;
                while(cur!=null){out.addFirst(cur.p);cur=cur.parent;}
                return out;
            }
            int n=steps3(c.x,c.y,c.p,nb);
            // 同层位图邻接 + ★跨层交互边(edgeIdx), 两者都参与搜索(官方 astar3 同款)。
            // 缺 edgeIdx 时 L2->GE2 无法借跨层边绕行 -> 规划失败。
            List<Edge> cross=edgeIdx.get(key(c.x,c.y,c.p));
            int total=n+(cross==null?0:cross.size());
            for(int i=0;i<total;i++){
                int nx,ny,np,cost;
                int eoid=-1, eex=-1, eey=-1; // ★跨层边真实 oid/锚点, 带进 Pt 供交互发包
                if(i<n){nx=nb[i*4];ny=nb[i*4+1];np=nb[i*4+2];cost=nb[i*4+3];}
                else{Edge e=cross.get(i-n);nx=e.dx;ny=e.dy;np=e.dp;cost=1;
                     eoid=e.oid; eex=e.sx; eey=e.sy;}
                double ng=c.g+cost;
                long nk=key(nx,ny,np);
                PtState old=best.get(nk);
                if(old==null||ng<old.p.g-1e-6){
                    Pt nn=new Pt(nx,ny,np); nn.g=ng;
                    nn.oid=eoid; nn.ex=eex; nn.ey=eey;
                    best.put(nk,new PtState(nn,cs)); open.add(nn);
                }
            }
        }
        return Collections.emptyList();
    }
    private static final class PtState { Pt p; PtState parent; PtState(Pt p,PtState par){this.p=p;this.parent=par;} }

    @Subscribe
    public void onTick(GameTick tick){
        if(finished||!client.isLoggedIn())return;
        WorldPoint me=client.getLocalPlayerLocation(); if(me==null)return;
        long t=client.getTickCount();

        if((Math.abs(me.getX()-goalX)<=1&&Math.abs(me.getY()-goalY)<=1&&me.getPlane()==goalP)){
            if(!reachedL2){ctx.log("[实时导航] ★★★到达 L2 "+me+" → GE2");reachedL2=true;setGoal(3203,3473,2,"GE2");return;}
            ctx.log("[实时导航] ★★★★到达终点 GE2 "+me);finished=true;return;
        }

        // 卡住检测
        if(me.getX()==lastX&&me.getY()==lastY) stuckTicks++; else stuckTicks=0;
        lastX=me.getX();lastY=me.getY();

        // 重规划: 无路径/走完/卡住
        boolean needReplan=path.isEmpty()||pathIdx>=path.size()||stuckTicks>60;
        if(needReplan){
            List<Pt> p=astar(me.getX(),me.getY(),me.getPlane(),goalX,goalY,goalP);
            if(p.isEmpty()){
                if(t-lastLogTick>=100){lastLogTick=t;ctx.log("[实时导航] 规划失败 "+me+" -> "+goalName);}
                stuckTicks=0; return;
            }
            path=p;pathIdx=0;stuckTicks=0;lastWalkTick=-1;
            ctx.log("[实时导航] 规划 "+p.size()+"步 "+me.getX()+","+me.getY()+",p"+me.getPlane()+" -> "+goalName);
        }
        if(lastLogTick<0||t-lastLogTick>=50){
            lastLogTick=t;
            ctx.log("[实时导航] pos="+me.getX()+","+me.getY()+",p"+me.getPlane()+" 步"+pathIdx+"/"+path.size());
        }
        if(pathIdx>=path.size())return;
        Pt target=path.get(pathIdx);

        // ★PC Ghost opener: 同层路径前方有 cost=2 障碍边 -> 现场object验证+开门/爬, true=本tick接管
        if(target.p==me.getPlane() && tryOpenObstacle(me,t)) return;

        // 跨层 -> 楼梯交互 (★完全对齐 PC Ghost: oid=ed.oid→guessOid→findLiveStair现场覆盖, 坐标=现场object)
        if(target.p!=me.getPlane()){
            Pt prev=pathIdx>0?path.get(pathIdx-1):new Pt(me.getX(),me.getY(),me.getPlane());
            boolean up=target.p>me.getPlane();
            // ①PC Ghost链: 先取跨层边自带oid, 没有边就guessOid分层兜底
            Edge ed=findEdge(prev.x,prev.y,prev.p,target.x,target.y,target.p);
            int oid=(ed!=null)?ed.oid:guessOid(prev.p,target.p);
            int sx=prev.x, sy=prev.y;   // PC Ghost 默认坐标=prev格
            // ②现场 objects() 真值覆盖 oid+坐标 (PC Ghost findLiveStair同款)。
            //   ★早上成功实证: 玩家(3213,3472,p0), 楼梯object(3212,3473)斜对角曼哈顿2,
            //     invoke_object_at(11807,3212,3473,0) 用object真坐标成功上楼 -> 用现场坐标而非边起点。
            int[] live=findLiveStair(prev.x,prev.y,target.x,target.y);
            boolean liveHit=(live!=null);
            if(liveHit){oid=live[0];sx=live[1];sy=live[2];}
            else if(ed!=null){sx=ed.sx;sy=ed.sy;}
            ctx.log("[实时导航] ↑楼梯 oid链="+(ed!=null?"ed.oid="+ed.oid:"guessOid="+guessOid(prev.p,target.p))+
                " 现场="+(liveHit?"id="+live[0]+"@"+live[1]+","+live[2]:"miss")+" 采用="+oid+"@"+sx+","+sy);
            // ★交互距离用曼哈顿(PC Ghost: 必须走到物体相邻格才能交互)。
            //   max距离会把斜对角(max=2)误判为"够近"而狂发包, 实际楼梯在斜对角op88不生效。
            int sd=Math.abs(sx-me.getX())+Math.abs(sy-me.getY());
            if(sd>2){
                if(lastWalkTick<0||t-lastWalkTick>=15){client.walkTo(sx,sy);lastWalkTick=t;}
                if(lastLogTick<0||t-lastLogTick>=50){lastLogTick=t;
                    ctx.log("[实时导航] →靠近楼梯 ("+sx+","+sy+") 当前"+me.getX()+","+me.getY()+" 梯距"+sd);}
                return;
            }
            if(lastInteractTick<0||t-lastInteractTick>=40){
                lastInteractTick=t;interactTries++;
                int opt=climbOpt(oid,up);
                sendStair(oid,sx,sy,up);
                ctx.log("[实时导航] ↑楼梯 obj="+oid+" opt"+opt+(opt==2?"(op72)":opt==1?"(op88)":"(op"+opt+")")+
                    (liveHit?"(现场真值)":"(旧edge/guess)")+" @"+sx+","+sy+" 玩家"+me.getX()+","+me.getY()+
                    " p"+me.getPlane()+"->"+target.p+" try"+interactTries);
            }
            return;
        }
        interactTries=0;

        // 逐格走: 到达当前目标(容差1)则推进; 未到则发walk(间隔30tick=600ms,避免flood)
        int d=Math.max(Math.abs(target.x-me.getX()),Math.abs(target.y-me.getY()));
        if(d<=1){pathIdx++;lastWalkTick=-1;return;}
        if(lastWalkTick<0||t-lastWalkTick>=30){
            client.walkTo(target.x,target.y);lastWalkTick=t;
        }
    }

    // ===== ★PC Ghost opener 1:1 移植方法组 =====
    private boolean isObsName(String n){ for(String k:OBJ_NAMES) if(n.contains(k)) return true; return false; }
    private boolean isPassAct(String a){ for(String p:PASS_ACTS) if(a.equals(p)) return true; return false; }
    private boolean isStairName(String n){ return n.contains("stair")||n.contains("ladder")||
        n.contains("trapdoor")||n.contains("hatch"); }

    /** ★现场扫描障碍object: 1:1 用 PC Ghost 的 GhostRuntime.api().objects() (id/name/x/y/plane/actions)。
     *  该 API 端上经 AndroidOSRS.objectList() -> ObjectDefs.get(oid) 查真实 name+actions, 关门=11773[Open]/开门=11774[Close],
     *  并带真 plane。白名单 = name 命中 OR 任一 action 在 PASS_ACTS (与 OfficialNav.scanObstacles 完全一致)。 */
    private boolean obsDumped=false;
    /** ★现场源 1:1 PC Ghost: GhostRuntime.api().objects() (id/name/x/y/plane/actions, 经 AndroidOSRS.objectList()->ObjectDefs.get 真值)。
     *  弃用 client.getTileObjects()(用户要求)。装机APK的 objects() 坐标是静态缓存(错报), 故坐标只作score软排序,
     *  开门发包一律用路径cost2算出的障碍格真坐标(tx,ty); 双约束全过滤时回退到def真值门候选。 */
    private java.util.List<ObsObj> scanObstacles(int px,int py){
        java.util.List<ObsObj> out=new java.util.ArrayList<>();
        try{
            java.util.List<java.util.Map<String,Object>> all=com.ghost.api.shim.GhostRuntime.api().objects();
            if(!obsDumped){
                obsDumped=true;
                ctx.log("[opener诊断] objects() 共 "+(all==null?0:all.size())+" 个");
            }
            if(all==null)return out;
            int dumpN=0,hitN=0;
            for(java.util.Map<String,Object> m:all){
                if(m==null)continue;
                int id=m.get("id") instanceof Number?((Number)m.get("id")).intValue():0;
                if(id<=0)continue;
                String nm=String.valueOf(m.getOrDefault("name","")).toLowerCase();
                int x=m.get("x") instanceof Number?((Number)m.get("x")).intValue():0;
                int y=m.get("y") instanceof Number?((Number)m.get("y")).intValue():0;
                int pl=m.get("plane") instanceof Number?((Number)m.get("plane")).intValue():0;
                java.util.List<String> acts=new java.util.ArrayList<>();
                Object raw=m.get("actions");
                if(raw instanceof java.util.List) for(Object a:(java.util.List<?>)raw) if(a!=null) acts.add(String.valueOf(a).toLowerCase());
                // ★权威真值覆盖(绕开装机APK假动作bug): DOOR_ACTS命中->官方actions真值;
                //   未命中但带 interact+open+climb-up+climb-down 四件套->清空, 不当障碍。
                String defActs=doorActs(id);
                boolean fakeActs=acts.contains("interact")&&acts.contains("open")
                                 &&acts.contains("climb-up")&&acts.contains("climb-down");
                if(!defActs.isEmpty()){
                    acts.clear();
                    for(String part:defActs.split(",")) if(!part.trim().isEmpty()) acts.add(part.trim().toLowerCase());
                }else if(fakeActs){ acts.clear(); }
                boolean hit=isObsName(nm);
                for(String a:acts) if(isPassAct(a)){hit=true;break;}
                if(hit&&hitN<40){
                    ctx.log("[opener诊断] HIT id="+id+" name='"+nm+"' @("+x+","+y+",p"+pl+") acts="+acts+(defActs.isEmpty()?"":"(def真值)"));
                    hitN++;
                }else if(dumpN<12){
                    ctx.log("[opener诊断]     id="+id+" name='"+nm+"' @("+x+","+y+",p"+pl+") acts="+acts);
                    dumpN++;
                }
                if(!hit)continue;
                // ★非挡路物过滤: 容器/装饰物虽有 open 动作但不是寻路障碍, 交给它们会把玩家卡在
                //   箱子格反复开(实测 closed chest n=5 来回震荡)。opener 只管门/闸/栅栏类。
                if(nm.contains("chest")||nm.contains("drawer")||nm.contains("cupboard")
                   ||nm.contains("cabinet")||nm.contains("bank")||nm.contains("manhole")
                   ||nm.contains("bush")||nm.contains("search")||nm.contains("entrance"))continue;
                // ★楼梯/梯子不进opener候选: 跨层交互由专门的跨层分支处理, opener只管门/闸等挡路障碍。
                //   否则 opener 会把 staircase(climb-up) 当障碍狂点(实测 n=20) 且卡住玩家。
                boolean isStair=isStairName(nm);
                for(String a:acts) if(a.startsWith("climb")){isStair=true;break;}
                if(isStair)continue;
                ObsObj oo=new ObsObj(); oo.id=id; oo.x=x; oo.y=y; oo.pl=pl;
                oo.name=nm; oo.acts=acts; oo.stair=false;
                out.add(oo);
            }
        }catch(Throwable e){ctx.log("[opener诊断] EX "+e);}
        return out;
    }

    /** 每tick走路前: 路径前方有cost=2障碍边 -> 现场object验证+开门/爬。true=本tick被opener接管。
     *  1:1 移植 OfficialNav.tryOpenObstacle: cost2定位障碍格 -> 双约束匹配object -> score排序 ->
     *  假门证伪/门已开/防toggle -> invoke + 振荡检测。 */
    private boolean tryOpenObstacle(WorldPoint me,long t){
        int mx=me.getX(),my=me.getY(),mp=me.getPlane();
        java.util.List<ObsObj> obs=scanObstacles(mx,my);
        if(obs.isEmpty())return false;
        // 1) 路径前方(同层、地图内)路径点
        java.util.List<int[]> ahead=new java.util.ArrayList<>();
        for(int i=pathIdx;i<path.size();i++){
            Pt q=path.get(i); if(q.p!=mp)break;
            int ddx=q.x-mx,ddy=q.y-my;
            if(ddx*ddx+ddy*ddy>MAP_R*MAP_R)break;
            ahead.add(new int[]{q.x,q.y});
        }
        if(ahead.size()<2)return false;
        // 2) 沿ahead找第一条 current->next 的 cost=2 边, next=障碍格 (官方权威判据)
        int tx=-1,ty=-1; int[] nb=new int[8*4];
        costEdge:
        for(int i=0;i+1<ahead.size();i++){
            int ax=ahead.get(i)[0],ay=ahead.get(i)[1],bx=ahead.get(i+1)[0],by=ahead.get(i+1)[1];
            int n=steps3(ax,ay,mp,nb);
            for(int k=0;k<n;k++){
                if(nb[k*4]==bx&&nb[k*4+1]==by&&nb[k*4+2]==mp&&nb[k*4+3]==2){tx=bx;ty=by;break costEdge;}
            }
        }
        // 兜底1: cost2没命中再用object距离(官方OBS_NEAR) —— native真坐标修复后可命中面前门
        if(tx<0){
            outer:
            for(int[] q:ahead){
                if(q[0]==mx&&q[1]==my)continue;
                for(ObsObj o:obs){ if(Math.abs(q[0]-o.x)+Math.abs(q[1]-o.y)<=OBS_NEAR){tx=q[0];ty=q[1];break outer;} }
            }
        }
        // 兜底2: ★stuck 证据 —— cost2和object距离都没命中, 但玩家卡住(stuckTicks>=12),
        //   把路径中第一个非脚格当障碍格。PC Ghost on_obstacle 的端上补充。
        if(tx<0 && stuckTicks>=12){
            for(int ai=0;ai<ahead.size();ai++){
                int qx=ahead.get(ai)[0],qy=ahead.get(ai)[1];
                if(qx==mx&&qy==my)continue;
                tx=qx;ty=qy;
                ctx.log("[opener] ★stuck="+stuckTicks+" 强制障碍格("+tx+","+ty+") p"+mp+" ai="+ai);
                break;
            }
        }
        if(tx<0)return false;
        // 障碍格就在脚下: 先试走一次(官方on_obstacle), 走不动下面再点object
        int[] foot=crossFoot(mx,my,tx,ty,ahead);
        if(mx==tx&&my==ty){
            if(foot[0]!=mx||foot[1]!=my){
                if(lastWalkTick<0||t-lastWalkTick>=10){client.walkTo(foot[0],foot[1]);lastWalkTick=t;}
                return true;
            }
        }
        // 3) ★PC Ghost 官方双重约束 (OfficialNav 1:1): 候选必须同时
        //   (a) 贴着障碍格 dTarget<=OBS_NEAR  (b) 落在路径坐标上 dPath<=OBS_NEAR。
        //   score = onTile*100000 + actRank*10000 + dTarget*100 + dPath。
        //   ★端上 object 坐标偶有 1 格错位, 故 near 用 OBS_NEAR=2 容差(与 PC 同值), 不再一刀切全放行。
        java.util.List<int[]> ranked=new java.util.ArrayList<>();  // {obsIdx, dPath, dTarget, score}
        for(int oi=0;oi<obs.size();oi++){
            ObsObj o=obs.get(oi);
            int near=OBS_NEAR;
            int dTarget=Math.abs(o.x-tx)+Math.abs(o.y-ty);
            if(dTarget>near)continue;
            int dPath=OBS_NEAR+1;
            for(int[] q:ahead){
                int dd=Math.abs(o.x-q[0])+Math.abs(o.y-q[1]);
                if(dd<dPath){dPath=dd; if(dPath<=1)break;}
            }
            if(dPath>near)continue;
            int onTile=(o.x==tx&&o.y==ty)?0:1;
            int actRank=2;
            for(String a:o.acts){
                if(a.equals("open")||a.equals("close")||a.equals("cross")||a.equals("enter")
                   ||a.equals("pass-through")||a.equals("pass through")){actRank=0;break;}
                if(a.equals("climb-over")||a.equals("climb over")){actRank=1;break;}
            }
            if(o.stair)actRank=3; // opener只管门; 楼梯交给跨层分支
            int score=onTile*100000 + actRank*10000 + dTarget*100 + dPath;
            ranked.add(new int[]{oi,dPath,dTarget,score});
        }
        // 3.5) ★端上 objects() 坐标静态缓存错报(门报p0远处), 双约束会把面前门全过滤。
        //   回退: 只按 def 真值 actions 取门(open/close)/闸门候选, 不依赖错报坐标;
        //   发包坐标仍用障碍格(tx,ty)。这是端上对 PC Ghost 双约束的坐标适配。
        if(ranked.isEmpty()){
            ctx.log("[opener] 双约束无候选 -> def真值门回退 obs="+obs.size());
            for(int oi=0;oi<obs.size();oi++){
                ObsObj o=obs.get(oi);
                if(o.stair)continue;
                // ★只认 door/gate 名字的挡路物: 排除 chest/drawers/manhole 等容器(open动作但非障碍),
                //   否则 stuck/距离兜底会把箱子当门反复开, 玩家来回震荡。
                boolean isDoorName=o.name.contains("door")||o.name.contains("gate");
                if(!isDoorName)continue;
                boolean isDoor=false;
                for(String a:o.acts){ if(a.equals("open")||a.equals("close")){isDoor=true;break;} }
                if(!isDoor)continue;
                if(o.pl!=mp)continue;  // ★真plane已可用: 只取同层门, 排除p0/p2远处门
                // ★坐标残差兜底: 只接受"当前玩家附近"的门。实测 object 世界坐标会在跨区块时
                //   出现 ±8 抖动, 不限制距离会选到远处同 id 门并反复 toggle 错误格。
                if(Math.abs(o.x-mx)+Math.abs(o.y-my)>3)continue;
                if(Math.abs(o.x-tx)+Math.abs(o.y-ty)>3)continue;
                // ★actions 真值判门开关(PG同款): [Close]=门已开可通行 -> 最高优先级(触发直接走过);
                //   [Open]=门关需开 -> 次优先。避免选到远处静态缓存的11773[open]反复开门。
                boolean isOpenState=false;
                for(String a:o.acts){ if(a.equals("close"))isOpenState=true; }
                // ★距离优先(面前最近的门才是关键), 已开[Close]作同距离的tie-break。
                int d=Math.abs(o.x-tx)+Math.abs(o.y-ty);
                ranked.add(new int[]{oi,0,d,d*100 + (isOpenState?0:1)});
            }
        }
        // 4) 假门证伪: 有障碍格但无object候选 -> 计3次直接走过
        if(ranked.isEmpty()){
            String fk=tx+","+ty+","+mp;
            int n=fakeDoorN.containsKey(fk)?fakeDoorN.get(fk)+1:1; fakeDoorN.put(fk,n);
            if(n>=3){
                if(lastWalkTick<0||t-lastWalkTick>=15){client.walkTo(foot[0],foot[1]);lastWalkTick=t;}
                ctx.log("[opener] ★假门证伪 走过 ("+tx+","+ty+")->("+foot[0]+","+foot[1]+") n="+n);
                fakeDoorN.remove(fk); return true;
            }
            return false;
        }
        java.util.Collections.sort(ranked,new java.util.Comparator<int[]>(){
            public int compare(int[] a,int[] b){return a[3]-b[3];}
        });
        ObsObj best=obs.get(ranked.get(0)[0]);
        // ★官方 ObjectDef.actions 真值: Close 且无 Open = 门已开, 不是障碍, 直接走过(绝不发 op88)
        boolean bestOpen=false,bestClose=false;
        for(String a:best.acts){ if(a.equals("open"))bestOpen=true; if(a.equals("close"))bestClose=true; }
        if(t-lastOpenerDiag>=40){
            StringBuilder dbg=new StringBuilder("[opener] scan n="+obs.size()+" ");
            for(ObsObj o:obs) dbg.append(o.id).append("'").append(o.name).append("'=").append(o.acts).append(" ");
            dbg.append("target(").append(tx).append(",").append(ty).append(") best=").append(best.id)
               .append(" acts=").append(best.acts);
            ctx.log(dbg.toString()); lastOpenerDiag=t;
        }
        if(bestClose&&!bestOpen&&!best.stair){
            ctx.log("[opener] ★门已开 id="+best.id+" acts="+best.acts+" -> 直接走过("+foot[0]+","+foot[1]+")");
            if(lastWalkTick<0||t-lastWalkTick>=15){client.walkTo(foot[0],foot[1]);lastWalkTick=t;}
            crossGateTick.remove(best.id+"@"+tx+","+ty);
            fakeDoorN.remove(tx+","+ty+","+mp);
            return true;
        }
        // ★key用障碍格真坐标(tx,ty), 不用设备错报的best.x,best.y(否则防toggle失效)
        String key=best.id+"@"+tx+","+ty;
        long nowMs=System.currentTimeMillis();
        // 5) 门6s内开过 -> 不重复toggle, 直接走过
        Long last=crossGateTick.get(key);
        if(last!=null&&(nowMs-last)<6000&&!best.stair){
            int[] cf=crossGateFoot.get(key);
            int fx=cf!=null?cf[0]:foot[0],fy=cf!=null?cf[1]:foot[1];
            if(lastWalkTick<0||t-lastWalkTick>=15){client.walkTo(fx,fy);lastWalkTick=t;}
            return true;
        }
        if(lastDoorInvokeTick>=0&&t-lastDoorInvokeTick<40)return true;
        lastDoorInvokeTick=t;
        // 6) ★PC Ghost 同款交互: invokeObject(id,x,y,slot), slot=actions.indexOf(INVOKE_PRIORITY)。
        //   ★坐标对齐 PC Ghost: 用 object 自身真坐标 (best.x,best.y) —— native 已修复真坐标+真plane,
        //   这是官方 TileObject.getWorldLocation() 等价真值。障碍格(tx,ty)仅作object坐标离谱时的兜底。
        //   只发一次, 不叠加另一个发包(否则同tick两个op88 toggle两次=没开), 这是PC Ghost防toggle核心。
        // ★端上真值验证: 门object经native换算的y有~2格残差(报3219,3470; 真值/历史成功包=3219,3472)。
        //   路径cost2算出的障碍格(tx,ty)才是权威格, 历史成功 invoke_object_at(11773,3219,3472,0) 可证。
        //   故发包坐标一律用障碍格(tx,ty), 不用object自身坐标(PC Ghost 在端上的坐标适配)。
        int ix=tx, iy=ty;
        int slot=0; boolean found=false;
        for(String want:INVOKE_PRIORITY){
            int idx=best.acts.indexOf(want);
            if(idx>=0){slot=idx;found=true;break;}
        }
        if(!found) for(int i=0;i<best.acts.size();i++) if(isPassAct(best.acts.get(i))){slot=i;break;}
        try{
            com.ghost.api.shim.GhostRuntime.api().invokeObject(best.id,ix,iy,slot);
        }catch(Throwable e){
            ctx.log("[opener] invoke异常 "+e+" -> 回退 sendObj1");
            sendObj1(best.id,ix,iy);
        }
        if(!best.stair){crossGateTick.put(key,nowMs);crossGateFoot.put(key,foot);}
        int n=doorOscN.containsKey(key)?doorOscN.get(key)+1:1; doorOscN.put(key,n);
        ctx.log("[opener] ★invoke id="+best.id+" '"+best.name+"' slot="+slot+" acts="+best.acts+
            " 发包坐标("+ix+","+iy+") 障碍格("+tx+","+ty+") 落脚("+foot[0]+","+foot[1]+") n="+n);
        // 7) 门振荡: 同门open>=5次且接近目标 -> 冲目标
        if(n>=5&&Math.abs(mx-goalX)+Math.abs(my-goalY)<=8){
            if(lastWalkTick<0||t-lastWalkTick>=15){client.walkTo(goalX,goalY);lastWalkTick=t;}
            ctx.log("[opener] ★门振荡 冲目标 ("+goalX+","+goalY+")");
            doorOscN.remove(key);crossGateTick.remove(key);
        }
        return true;
    }

    /** 正交落脚点(官方_cross_goal 1:1): 门另一侧正交格, 排除门格/脚下格, 防横跳。 */
    private int[] crossFoot(int mx,int my,int tx,int ty,java.util.List<int[]> pathTiles){
        int dx=tx-mx,dy=ty-my,sx=0,sy=0;
        if(Math.abs(dx)>=Math.abs(dy))sx=dx>0?1:(dx<0?-1:0); else sy=dy>0?1:(dy<0?-1:0);
        int fx=tx+sx,fy=ty+sy;
        if(pathTiles!=null){
            int[] near=null;int bestD=Integer.MAX_VALUE;
            for(int[] q:pathTiles){
                if(q[0]==tx&&q[1]==ty)continue;
                if(q[0]==mx&&q[1]==my)continue;
                if(Math.abs(q[0]-fx)+Math.abs(q[1]-fy)>1)continue;
                if(q[0]!=tx&&q[1]!=ty)continue; // 正交约束
                int dot=(q[0]-mx)*(tx-mx)+(q[1]-my)*(ty-my); if(dot<=0)continue;
                int dd=Math.abs(q[0]-fx)+Math.abs(q[1]-fy);
                if(dd<bestD){bestD=dd;near=q;}
            }
            if(near!=null){fx=near[0];fy=near[1];}
        }
        if((fx==tx&&fy==ty)||(fx==mx&&fy==my)){fx=tx+sx;fy=ty+sy;}
        return new int[]{fx,fy};
    }

    /** object1(op88)通用发包: 门Open(slot0)。payload与sendStair opt1一致。 */
    private void sendObj1(int oid,int x,int y){
        int lo=oid&0xff,hi=(oid>>8)&0xff,b2=(oid>>16)&0xff;
        int X=x&0xffff,Y=y&0xffff;
        int xX=(X&0xff)^0x80,hiX=(X>>8)&0xff,hiY=(Y>>8)&0xff,loY=Y&0xff;
        client.sendPacket(88,new byte[]{(byte)xX,(byte)hiX,(byte)hiY,(byte)loY,0,
            (byte)lo,(byte)hi,(byte)b2,0});
    }

    private Edge findEdge(int sx,int sy,int sp,int dx,int dy,int dp){
        List<Edge> es=edgeIdx.get(key(sx,sy,sp)); if(es==null)return null;
        for(Edge e:es) if(e.dx==dx&&e.dy==dy&&e.dp==dp)return e; return null;
    }
    /** ★PC Ghost findLiveStair 1:1: 现场 GhostRuntime.api().objects() 读楼梯真 id+坐标。
     *  匹配 prev/target 附近3格内、name/actions 含 stair/climb/ladder/trapdoor/hatch 的 object。
     *  返回 {id,x,y} 现场坐标; 找不到返回 null。 */
    private int[] findLiveStair(int px,int py,int tx,int ty){
        int best=-1,bestD=100,bx=0,by=0;
        try{
            java.util.List<java.util.Map<String,Object>> all=com.ghost.api.shim.GhostRuntime.api().objects();
            if(all==null)return null;
            for(java.util.Map<String,Object> m:all){
                int x=m.get("x") instanceof Number?((Number)m.get("x")).intValue():0;
                int y=m.get("y") instanceof Number?((Number)m.get("y")).intValue():0;
                int id=m.get("id") instanceof Number?((Number)m.get("id")).intValue():0;
                String name=String.valueOf(m.getOrDefault("name","")).toLowerCase();
                boolean stair=name.contains("stair")||name.contains("ladder")
                        ||name.contains("trapdoor")||name.contains("hatch");
                Object raw=m.get("actions");
                // ★装机APK假动作bug: 每个object塞[interact,open,climb-up,climb-down]四件套,
                //   识别到就忽略actions(否则满场object都因climb-up被当楼梯), 只信name。
                boolean fake = raw instanceof java.util.List
                    && ((java.util.List<?>)raw).contains("climb-up")
                    && ((java.util.List<?>)raw).contains("climb-down")
                    && ((java.util.List<?>)raw).contains("interact");
                if(!stair && !fake && raw instanceof java.util.List)
                    for(Object a:(java.util.List<?>)raw) if(a!=null&&String.valueOf(a).toLowerCase().startsWith("climb")){stair=true;break;}
                if(!stair||id<=0) continue;
                int d=Math.min(Math.abs(x-px)+Math.abs(y-py),Math.abs(x-tx)+Math.abs(y-ty));
                if(d<=3 && d<bestD){bestD=d;best=id;bx=x;by=y;}
            }
        }catch(Throwable e){}
        if(best<=0)return null;
        // ★真值表覆盖坐标: native object 坐标有y残差(11801报3224,3470, 真值3224,3472),
        //   GE_STAIR_XY 来自实抓成功回执, 命中就用真坐标。
        int[] truth=geStairXY(best);
        if(truth!=null){bx=truth[0];by=truth[1];}
        return new int[]{best,bx,by};
    }
    private int guessOid(int fromP,int toP){
        if(toP>fromP)return fromP==0?16671:(fromP==1?16672:11807);
        return fromP==1?16673:16672;
    }
    /** ★官方规则发包: slot=actions.index(Climb-up/down), opt=slot+1, 按 opt 选 payload 布局。
     *  与 AstraStudio/native engine.cpp Engine::interactObject 逐字节一致:
     *   opt1/op88: [x^80, hi(x), hi(y), lo(y), 0, lo(id), hi(id), b2(id), 0]
     *   opt2/op72: [hi(y), y^80, 80, b2(id), lo(id), hi(id), 0, lo(x), hi(x)]
     *   opt3/op0 : [80, hi(x), x^80, lo(id), hi(id), b2(id), lo(y), hi(y), 80]
     *     ★v2.14 实测: 16672 Climb-down(手动点击真包 raw=0 hex=...800c04204100870c80)
     *       服务器确实执行(p1->p0), 之前误判"无包"导致下楼永远失败。 */
    private void sendStair(int oid,int x,int y,boolean up){
        int lo=oid&0xff,hi=(oid>>8)&0xff,b2=(oid>>16)&0xff;
        int X=x&0xffff,Y=y&0xffff;
        int xX=(X&0xff)^0x80,hiX=(X>>8)&0xff;
        int hiY=(Y>>8)&0xff,loY=Y&0xff,yX=(Y&0xff)^0x80;
        int opt=climbOpt(oid,up);
        if(opt==2){
            client.sendPacket(72,new byte[]{(byte)hiY,(byte)yX,(byte)0x80,(byte)b2,
                (byte)lo,(byte)hi,0,(byte)(X&0xff),(byte)hiX});
        }else if(opt==3){
            // ★v2.14: 16672 Climb-down 真包 (oid 16672=0x4120, x3204=0x0c84,y3207=0x0c87
            //   -> 80 0c 04 20 41 00 87 0c 80, 与用户手动点击抓包逐字节一致)
            client.sendPacket(0,new byte[]{(byte)0x80,(byte)hiX,(byte)xX,
                (byte)lo,(byte)hi,(byte)b2,(byte)loY,(byte)hiY,(byte)0x80});
        }else if(opt==4){
            client.sendPacket(56,new byte[]{0,(byte)0x80,(byte)hiY,(byte)yX,
                (byte)lo,(byte)hi,(byte)b2,(byte)hiX,(byte)xX});
        }else if(opt==5){
            client.sendPacket(64,new byte[]{(byte)hiX,(byte)xX,(byte)lo,(byte)hi,(byte)b2,
                (byte)0x80,(byte)yX,(byte)hiY,0});
        }else{ // opt1 默认 (含单动作楼梯 56230/11807 的 Climb-up)
            client.sendPacket(88,new byte[]{(byte)xX,(byte)hiX,(byte)hiY,(byte)loY,0,
                (byte)lo,(byte)hi,(byte)b2,0});
        }
    }
}
