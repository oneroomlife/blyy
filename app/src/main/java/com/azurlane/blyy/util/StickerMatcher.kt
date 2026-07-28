package com.azurlane.blyy.util

import com.azurlane.blyy.data.model.StickerResource

/**
 * 表情包匹配器：根据 AI 回复文本匹配最合适的表情包。
 *
 * 从 JiuxinViewModel 提取，纯逻辑无状态，全局单例复用。
 *
 * 匹配策略（按优先级）：
 * 1. 标签精确匹配（优先匹配更长的标签，避免短标签误匹配）
 * 2. 情绪倾向分析（基于关键词组合判断整体情绪）
 * 3. 保底：不返回表情包（避免无关表情包干扰对话）
 *
 * 表情包资源来源: https://wiki.biligame.com/blhx/表情包
 * 使用 patchwiki.biligame.com 直链，Coil + OkHttp 加载
 */
object StickerMatcher {

    // ═══════════════════════════════════════════════════
    // 表情包资源库
    // ═══════════════════════════════════════════════════
    private val stickerLibrary = listOf(
        // ── 新表情（19 项）──
        StickerResource("https://patchwiki.biligame.com/images/blhx/1/1a/pplmi3rbpprvizqr5lgotrss43ff14i.png", listOf("惊了", "震惊", "不会吧"), "惊了"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/1/12/etkhxbpwfb5dsx46zbocml1sr8mnusn.png", listOf("快住手", "住手", "停下"), "快住手"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/e/e9/stps3z3zhm1u0ezc520jtmecuyksii1.png", listOf("抓到你了", "抓到", "逮到"), "抓到你了~"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/8/87/5jlkxre04bblkmo5v7al36d9nrin3g7.png", listOf("好热", "热", "流汗"), "好热啊…"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/a/a6/g50cq4ezrqqs1sifa9qw8hn8qno0k2f.png", listOf("还不睡", "睡觉", "该睡了"), "还不睡"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/50/7wpm0szjxcjuy4pwvexznnwqyueo9nk.png", listOf("眠眠", "困", "想睡"), "眠眠"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/1/12/njet0s7923g93o1v0zdk7ilkl953e47.png", listOf("准备万全", "准备好了", "就绪"), "准备万全"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/96/qxxput26d347lfj0alotoupif7klrpy.png", listOf("再等等", "等等", "稍等"), "再等等"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/5c/hcbu91tgnwlqrwkynoyupu7064mmokf.png", listOf("开饭", "吃饭", "饿了"), "开饭啦"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/6/62/7o9s2yss4eoysh4dlj4m2z5abt40uqs.png", listOf("闪亮登场", "登场", "出场"), "闪亮登场"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/e/e5/absxsrd2sos5hd8allacjr2hrbtfb13.png", listOf("吃什么", "吃啥", "想吃"), "吃什么呢？"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/c9/kyipp7y5nd6xezu9pkswxkcoravvhz7.png", listOf("让我看看", "看看", "瞅瞅"), "让我看看"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/a/a8/b0nsyeoe2athrm1q1xlqrl1u4xhtn4g.png", listOf("按摩", "舒服", "放松"), "按摩按摩"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/c8/jd1vczvnf05wpzv9sju02kgx6jefwb3.png", listOf("乖巧", "乖", "听话"), "乖巧"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/d/d0/8ok459s13l6gqe3m8dxfi5ycbiu2cym.png", listOf("别看", "不许看", "转过去"), "别看"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/0/06/2ol7wblu8xusvoiwgtxqx820g02d5on.png", listOf("大大", "好大", "这么大"), "大大"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/2/2a/1h51txw82zkkn9qam59ydx8zmb6sbus.png", listOf("惊", "吓", "哇"), "惊"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/c6/6kf9qgrfswon1uh5z7axzy0b5ilygzi.gif", listOf("怒了", "生气", "发怒"), "怒了！"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/95/5tzutvjr6imqdpte369qmqtq4q20a14.gif", listOf("看这里", "看这", "注意"), "看这里！"),

        // ── 官方动态表情包第三弹 (by Seseren)（24 项）──
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/51/kztm6swwpg3aslxiupb9who1dw5m72x.gif", listOf("点这里", "过来", "喂", "嘿"), "点这里"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/58/7w3ob2nzbwgagqaypbsuto1lrp2wisg.gif", listOf("灵魂出窍", "震惊", "吓死", "晕"), "灵魂出窍"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/b/b4/aefbc7eowvprfjhi3nf0c85jgdgn1j4.gif", listOf("嘲讽", "鄙视", "看不起"), "嘲讽"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/d/d3/akqa32ui49nov2bpnrknrxrwcbxp6w8.gif", listOf("吃", "美食", "好吃", "饿"), "吃披萨"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/94/gp1qbm03ohim6tx2nn9wf16ulnl5on7.gif", listOf("登场", "闪亮", "出场", "锵"), "锵"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/8/8f/2ohgz0kzdke7bx1vtmwvefz1e5alw7h.gif", listOf("柠檬", "酸", "羡慕", "嫉妒"), "柠檬"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/3/3c/0n2lucjg028ocqcnhz57e0lzax7fkii.gif", listOf("收拾你", "打你", "揍", "惩罚"), "收拾你"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/6/62/bv6mkviiu19pmmowds9izb3xgy5vkvl.gif", listOf("双重闪亮", "闪亮", "耀眼"), "双重闪亮"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/c0/hmilsh86dr8o5l2atdr5zft7nsoe0mj.gif", listOf("我来了", "来了", "到", "报到"), "我来了"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/5b/fjiics3pfj893996b0ihskj45b1hltg.gif", listOf("砰砰", "爆炸", "轰", "开火"), "砰砰"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/0/05/ggcamyow5jmoslw03az2ihayz08rgwm.gif", listOf("唱歌", "歌唱", "音乐", "啦啦"), "歌唱生命"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/c7/4uxymdps8g137nzjtdaswg9nliyuoha.gif", listOf("标枪", "兔耳"), "标枪"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/6/6e/9vqa8n47ucdl6wpoljjmkveulmt9fht.gif", listOf("兔耳飞", "飞", "跳"), "兔耳飞"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/c6/3kyx6e0vsugwc2wdjgnitqk6q2jq78x.gif", listOf("哼哼", "傲娇", "才不是"), "哼哼"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/8/8f/fed208x01eggp3vafij5oc7kgfho20h.gif", listOf("加班", "工作", "忙", "奋斗"), "加班"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/1/13/18qigej0wvge94ftlejga55czdp1avt.gif", listOf("加班", "工作", "忙"), "加班-2"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/0/07/mcnrhekyoniggr55iy7veud9yovxwhl.gif", listOf("微笑", "靠近", "笑"), "微笑靠近"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/f/f3/oumyt46hnvrh6a7laognpi5bh1ffbth.gif", listOf("海豹", "萌", "小动物"), "小海豹"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/c4/3jtnxaqmrewp27wovhl0fnj4b17bda6.gif", listOf("愤怒", "暴怒", "气死", "极限愤怒"), "极限愤怒"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/8/83/kga3atemix1zil2hkkpt3q22kzgj1ad.gif", listOf("花", "漂亮", "美丽", "好看"), "花花"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/5e/gch50jl7gmnfo29ius5vfvpz7sbr1eu.gif", listOf("没办法", "无奈", "算了"), "真没办法"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/6/6d/0ve9q4m5njt48wjppew1jwr1i8ewmpl.gif", listOf("哈哈", "哇哈哈", "大笑", "笑死"), "哇哈哈"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/e/e5/k6vbtfy1310ezogrcgpgonk50ge02z5.gif", listOf("美味", "超好吃", "馋"), "超美味"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/ca/nz6i1pu837ae0gr4d6s7e47btdrwpz3.gif", listOf("停下", "快停下", "住手"), "快停下啊"),

        // ── 官方动态表情包第二弹（24 项）──
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/5e/8f7i8j706ddsdwot5yia3g9heyiyrnh.gif", listOf("吓晕", "吓死", "晕倒"), "吓晕"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/7/7e/k42ea9g8vzhqbuj81cu6mfm8rcin4bv.gif", listOf("危险", "救命", "要掉了"), "要掉了"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/8/81/4ydtk0sc6rljrj36j3turjucxm3l3au.gif", listOf("喝", "干杯", "一起喝", "饮料"), "一起喝？"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/e/e5/p7k1r0gd5pbdspfjshsoi4u09kufj5s.gif", listOf("晕头", "转圈", "迷糊", "头晕"), "晕头转向"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/1/1e/46bbzxcjmvju5xx04z719f8x4aksqv8.gif", listOf("嘿嘿", "欸嘿嘿", "坏笑", "偷笑"), "欸嘿嘿"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/47/mxzsjoagp8sg5nwm8klguhgzbs1iq67.gif", listOf("被戳", "戳", "碰我"), "被戳"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/2/2e/r4xh6cgowytsopa6jk0rxkkdsaoo0q3.gif", listOf("魔术", "惊喜", "变魔术"), "变魔术"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/b/b1/avec8c9zh7xslwr9aheb5dr2bl7b8sr.gif", listOf("唱歌", "歌唱", "音乐"), "唱歌"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/e/e8/th7uhaufwqeama9y55way3a3vuq37sx.gif", listOf("充电", "充能", "满血"), "充电ing"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/99/gs81rdp8by6uer5sxg8klm9l10id4sk.gif", listOf("吹奏", "演奏", "乐器"), "吹奏"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/cd/4qoqj4v3i50t64s7t0ns1nxl2sg8gbl.gif", listOf("打call", "应援", "支持", "加油"), "打call"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/f/fa/7cqrz6b9g9dw4w0q6880h7f7y3ecz85.gif", listOf("大哭", "哭", "呜呜", "伤心", "难过"), "大哭"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/7/77/ebfs7mjj6dse5ksjnanddhe9a967rbd.gif", listOf("点赞", "赞", "好"), "点赞"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/95/g9t40q44bqvc96maec8ogy661i93e3q.gif", listOf("咕嘟", "喝", "喝水"), "咕嘟咕嘟"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/e/e0/66yvzc6gngtdgtekb1fdu95fp8p6qpy.gif", listOf("咔嚓", "拍照", "照片"), "咔嚓"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/94/cbnov0uo8tvgzue7ovke8zo53bi67r2.gif", listOf("脸红", "害羞", "不好意思", "羞"), "脸红"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/a/aa/betnh2iiu6pqznd9bwro1seb86of5o1.gif", listOf("跑来跑去", "跑", "跑路"), "跑来跑去"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/9d/f68fs0s62yl1v9higzbvqvwl9zuy5ca.gif", listOf("气", "生气", "怒"), "气"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/d/d0/hgax0kjpdg52q3f0x47hyeg2t5d6gel.gif", listOf("去吧", "上", "冲"), "去吧！"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/3/33/awpd12qfttm7znaokzhqvt9s2enqovn.gif", listOf("闪闪", "红花", "奖励"), "闪闪红花"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/7/7f/pqh045mzn5zpumw3gj2x4u1d9gmjz2g.gif", listOf("失落", "沮丧", "低落"), "失落"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/54/24vvrmnw5fvlyh6tkq5ctxq0cuurmwk.gif", listOf("水管", "喷水", "水"), "水管"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/0/0b/rgbnm43k7sb1ea1oxs891c27d4uzkpo.gif", listOf("上课", "听讲", "学习"), "听我上课！"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/b/b0/411flib2mjo38m3j0meyqwegrv5tm9c.gif", listOf("停下来", "停下啊", "住手"), "停下来啊！"),

        // ── 官方表情包第二弹（精选 20 项）──
        StickerResource("https://patchwiki.biligame.com/images/blhx/d/d1/hyzl8slvtk791vtg4xwmqo58teke77h.gif", listOf("？！", "震惊", "不会吧"), "？！？！"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/9a/fyumt24usynofuxl5ngiffzkgwyzwdx.gif", listOf("excellent", "优秀", "完美"), "Excellent"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/0/0c/56ijalb2wh324jaalwmlohqi5qi1rme.gif", listOf("victory", "胜利", "赢了"), "Victory"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/e/ec/mdzkf52gyh46ve85imzejy8aa3yuobs.gif", listOf("爱情", "爱意", "喜欢"), "爱情表现"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/7/78/smthc7atl7mikgtuz9e3m1e66z9p7l3.gif", listOf("抱抱", "拥抱", "贴贴", "抱"), "抱抱"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/cd/2749okiuv71ln6xohgxluhmpfftuwgk.gif", listOf("不准看", "不许看", "别看"), "不准看"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/96/ktbpt7t4dx351l9176utwdj1xb3ksrn.gif", listOf("超可爱", "可爱", "萌"), "超可爱"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/d/d8/44rjib91jpynpcd8ybkvx7iize3uycd.gif", listOf("超辣", "辣", "好辣"), "超辣"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/0/08/des07if98h4m1j4pr53pzn8w0x86juf.gif", listOf("逮捕", "抓", "拘留"), "逮捕"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/55/0jtn4h8p01875t4buyaqx3pwczn1xtl.gif", listOf("刚起床", "起床", "早安"), "刚起床"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/45/keengam0uut4y7661d06nvabc4td5kn.gif", listOf("好吃", "美味", "好吃…"), "好吃…"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/48/4njzmpmzn1dzpu0okx2f8mo925rm202.gif", listOf("呵呵呵", "呵呵", "冷笑"), "呵呵呵…"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/a/ad/cv8zdzt58duk7k3gbk49vp30ighdxyt.gif", listOf("欢迎回来", "欢迎", "回来"), "欢迎回来"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/58/sun2w5kz5656n2jknerspqhwzkr6glg.gif", listOf("见敌必杀", "必杀", "战斗"), "见敌必杀"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/1/18/0ludph3elesw2rcoh3d8rwi07tb6ae5.gif", listOf("笨蛋", "你是笨蛋吗", "傻"), "你是笨蛋吗？"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/99/2jl1makx7q6j4w9gp7s4lp5sxvqnl3f.gif", listOf("期待", "期待哦", "盼望"), "期待哦"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/a/a3/bjer4epw0342p5n4xu8666z8ungmny8.gif", listOf("任务完成", "完成", "搞定"), "任务完成"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/d/d4/mz3nvjx6sl7d6i6vn3pzdd8psao3ha7.gif", listOf("太慢了", "速度慢", "慢"), "速度太慢了"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/a/a2/475hwv07p7s7kr3th64sgks75jzhz5c.gif", listOf("提不起劲", "没动力", "懒"), "提不起劲"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/2/2d/5ktivqoca1pt97gsbxma7qj98d5u2u4.gif", listOf("休息中", "休息", "歇会"), "休息中"),

        // ── 官方像素表情包（15 项）──
        StickerResource("https://patchwiki.biligame.com/images/blhx/2/22/d9oilk04vn7vl1u8iyivp961lwr5kyf.gif", listOf("唔嗯", "嗯", "思考"), "唔嗯"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/44/dhl8nayk4e287n24ar5a26w7d1aqs7g.gif", listOf("今晚", "来点什么", "吃什么"), "今天晚上来点什么"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/2/21/5q9389v3atiwck6pndjjc7cikvurur1.gif", listOf("沉思", "想", "思考"), "沉思ing"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/8/85/n2kct18jnb72u8b2e8a5r4pv29jb55l.gif", listOf("改造", "等我", "等一下"), "等我改造完"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/98/9bpz69c5cwq1sx4r113wzh2mr0eyihk.gif", listOf("锉刀", "锉两刀", "修理"), "要不要我锉两刀"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/f/f4/h53cuk7w3u5crxb8it59b0zd6ai1yya.gif", listOf("泪流", "哭", "流泪"), "泪流不止"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/7/78/mt4fkye20zi6msxzyqq34chvc5jd6cr.gif", listOf("萌萌哒", "萌", "可爱"), "萌萌哒"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/41/rgx7j6j0qasl2k0v0cnm20bwv9npi1k.gif", listOf("完蛋", "哦豁", "糟了"), "哦豁，完蛋"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/1/1d/5y9c562o3osla7m7qjhyjovzzj1ihad.gif", listOf("热情", "火热", "热情似火"), "热情似火"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/40/4eeeqbrbm6l5h9awzoev2du8qlpw8vy.gif", listOf("没生气", "生气", "哼"), "我没生气"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/6/6b/1iq3mkjyl0ktzymy63bcwfgkx7y8ss5.gif", listOf("拉菲", "来点"), "要来点拉菲嘛"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/1/1e/gfy8nz6muis25rtujb4v1vu4dab4lql.gif", listOf("啊我死了", "死了", "不行了"), "啊我死了"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/8/8c/dmeo4jtqcqw8j4ca135fbdjtp859tmu.gif", listOf("睡觉", "困", "zzz", "晚安"), "ZZZ"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/7/78/3xomzem65o0f5mc2671ubf61dkz34cf.gif", listOf("？？", "疑惑", "什么"), "？？？？？"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/7/73/mh4fnn3g43l8b9vl9uhe3iwtyevci1j.gif", listOf("揍你", "打你", "揍"), "揍你哦"),

        // ── 官方表情包（精选 20 项）──
        StickerResource("https://patchwiki.biligame.com/images/blhx/a/af/7wdz38nj335rsuj5enqpamgeocm0fop.png", listOf("不可以", "不行", "禁止"), "不可以！"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/b/b9/sud52ogw7cdm9fh5z0ojouu7h7bfwlu.png", listOf("开玩笑", "开玩笑的", "逗你"), "开玩笑的"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/f/f1/2x6g4u1t1oewytpodfb2xrfdfc2ihzn.png", listOf("胆小鬼", "胆小", "怕"), "胆小鬼"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/3/3c/bsuoe7i0d9sgusotzf41j508zlh4zxq.png", listOf("跪下", "跪", "惩罚"), "给我跪下"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/94/j20uxqzi2to6bgtsp2f2mtg7djihy1n.png", listOf("通宵", "熬夜", "肝"), "通宵"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/b/b7/f1686kxd3ufa1s62jqxv2egw6f9e4k7.png", listOf("要来吗", "来吗", "邀请"), "要来吗？"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/49/90ma8mb0tprmafe9gjpffjgvzk01p6v.png", listOf("笨蛋", "笨", "傻"), "笨蛋"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/5e/7xsy9cdbjr4tba956zfe49863dv9pq1.png", listOf("萌", "可爱"), "萌"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/b/b0/a3g5pj3uw0wdnmgvsx5dl7da14p1445.png", listOf("早安", "早上好", "早"), "早安"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/2/24/qqu4swk7jm0em1ds3b5afsmh85fra4l.png", listOf("ok", "好的", "没问题"), "OK"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/4c/iqtb6zp49sgqxfd1zpsk8uey2j67wx7.png", listOf("变态", "色狼", "hentai"), "变态！"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/0/06/cd6lvph1a1pbd162rtd5whnkhp0jd6m.png", listOf("优雅", "优美", "端庄"), "优雅"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/95/6fxn9n7fjh5o6i1lhy186dpylurn7gn.png", listOf("晚安", "睡了", "好梦"), "晚安"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/0/09/czcxfudb7vw1mfo3okfnwnudqd2661h.png", listOf("发现猎物", "猎物", "发现"), "发现猎物"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/2/28/6jqlmo28ymrrerny5jks8omojq5t4qm.png", listOf("突破", "限界突破", "升级"), "限界突破"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/9f/rdfy116m9affa0z8ppnh25lxr89q6ax.png", listOf("揍你", "打你", "揍"), "揍你哦"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/e/e9/axe34i7436utl16ehoxu28sqsvz3tvi.png", listOf("请多指教", "指教", "多多指教"), "请多指教"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/47/fnoovu1qq3vn7040hoobwmc7n0huxm4.png", listOf("！？", "震惊", "诶"), "！？"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/4c/5vi3b4ep1y7957ccwsimise7nw78km3.png", listOf("走运", "幸运", "运气好"), "真走运！"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/8/81/4edmny47r0ig7l0rw3g51hbcolhnc6w.png", listOf("就是这样", "没错", "对"), "就是这样")
    )

    /**
     * 表情包名称索引（O(1) 查找）。
     *
     * [analyzeEmotionTendency] 中多处 `stickerLibrary.find { it.name == "xxx" }` 是 O(n) 线性扫描，
     * 每次情绪分析最坏需要扫描 100+ 项。此 Map 一次性构建索引，将每次查找降为 O(1)。
     */
    private val stickerByName: Map<String, StickerResource> by lazy {
        stickerLibrary.associateBy { it.name }
    }

    /**
     * 从文本中匹配最合适的表情包。
     *
     * 匹配策略（按优先级）：
     * 1. 标签精确匹配（优先匹配更长的标签，避免短标签误匹配）
     * 2. 情绪倾向分析（基于关键词组合判断整体情绪）
     * 3. 保底：不返回表情包（避免无关表情包干扰对话）
     */
    fun findBestSticker(text: String): StickerResource? {
        // 1. 标签精确匹配：计算每个表情包的匹配得分
        val scored = stickerLibrary.mapNotNull { sticker ->
            val bestTag = sticker.tags
                .filter { tag -> text.contains(tag, ignoreCase = true) }
                .maxByOrNull { it.length } // 优先匹配更长的标签
            if (bestTag != null) sticker to bestTag.length else null
        }

        if (scored.isNotEmpty()) {
            // 同分中随机选一个，增加多样性
            val maxScore = scored.maxOf { it.second }
            val topMatches = scored.filter { it.second == maxScore }
            return topMatches.random().first
        }

        // 2. 情绪倾向分析（基于多关键词组合判断）
        val emotionSticker = analyzeEmotionTendency(text)
        if (emotionSticker != null) return emotionSticker

        // 3. 不强制返回表情包，避免无关表情包
        return null
    }

    /**
     * 情绪倾向分析：根据文本中的关键词组合判断整体情绪
     * 返回最符合情绪的表情包，或 null 表示无法判断
     */
    private fun analyzeEmotionTendency(text: String): StickerResource? {
        // 通过 stickerByName 索引 O(1) 查找，替代原先 stickerLibrary.find { it.name == ... } 的 O(n) 扫描
        return when {
            // ── 积极情绪 ──
            hasAny(text, "好耶", "太棒了", "太好了", "好开心", "嘻嘻", "开心") -> stickerByName["哇哈哈"]
            hasAny(text, "嘿嘿", "坏笑") -> stickerByName["欸嘿嘿"]
            hasAny(text, "喜欢你", "最喜欢", "好喜欢", "爱") -> stickerByName["爱情表现"]
            hasAny(text, "谢谢", "感谢") -> stickerByName["点赞"]
            hasAny(text, "加油", "你可以的", "一起努力") -> stickerByName["打call"]
            hasAny(text, "可爱", "萌") -> stickerByName["超可爱"]
            hasAny(text, "欢迎回来", "欢迎") -> stickerByName["欢迎回来"]

            // ── 消极情绪 ──
            hasAny(text, "好难过", "好伤心", "好委屈", "哭") -> stickerByName["大哭"]
            hasAny(text, "好生气", "气死了", "好气", "怒") -> stickerByName["极限愤怒"]
            hasAny(text, "好累", "好困", "想睡觉", "困") -> stickerByName["ZZZ"]
            hasAny(text, "完蛋", "糟了", "哦豁") -> stickerByName["哦豁，完蛋"]
            hasAny(text, "失落", "沮丧", "低落") -> stickerByName["失落"]

            // ── 傲娇 ──
            hasAny(text, "才不是", "哼，才", "笨蛋指挥官", "笨蛋") -> stickerByName["你是笨蛋吗？"]

            // ── 亲密 ──
            hasAny(text, "贴贴", "抱抱你", "摸摸头", "抱") -> stickerByName["抱抱"]
            hasAny(text, "害羞", "脸红", "不好意思") -> stickerByName["脸红"]

            // ── 惊讶 ──
            hasAny(text, "诶", "不会吧", "震惊", "？！") -> stickerByName["？！？！"]

            else -> null
        }
    }

    /** 检查文本是否包含任一关键词 */
    private fun hasAny(text: String, vararg keywords: String): Boolean =
        keywords.any { text.contains(it, ignoreCase = true) }
}
