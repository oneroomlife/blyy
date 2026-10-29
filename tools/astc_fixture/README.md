# ASTC 对拍夹具（生成工具）

`app/src/test/resources/astc/` 下的两个夹具文件是 `AstcDecoderFixtureTest` 的
**唯一基准**，用来保证纯 Kotlin 的软件 ASTC 解码与规格逐像素一致。

## 为什么不用现成的 pip 包当基准

- `pip install texture2ddecoder` 的 wheel 与本目录编译出来的同一个 `astc.cpp` 在个别
  像素上已经不一致，不适合当「唯一真相」；
- 该参考实现的双线性权重再采样会**越界读**权重数组（`wv[v + width + 1]`），
  块的最后一行/列拿到的是栈上残值 —— 未定义行为，同一输入可能产生不同输出。

因此夹具由**打了「权重网格夹紧」补丁**的参考实现生成（`patch_weight_clamp.py`），
这符合 ASTC 规格，也与 astcenc 的 `blerp` 行为一致；本项目 Kotlin 解码器与之逐像素对齐。

## 生成步骤

```bash
# 1) 取参考实现源码（MIT，与 UnityPy / AssetStudio 同源）
pip download texture2ddecoder --no-binary :all: -d /tmp/astc
tar xzf /tmp/astc/texture2ddecoder-*.tar.gz -C /tmp/astc
SRC=/tmp/astc/texture2ddecoder-*/src/Texture2DDecoder

# 2) 打夹紧补丁
python tools/astc_fixture/patch_weight_clamp.py "$SRC/astc.cpp" /tmp/astc/astc_clamp.cpp

# 3) 编译 CLI（Windows 用 g++/MinGW 亦可，把 .exe 后缀加上）
g++ -O2 -std=c++14 -I"$SRC" -o /tmp/astc/astc_clamp \
    tools/astc_fixture/astc_main.cpp /tmp/astc/astc_clamp.cpp

# 4) 生成夹具（覆盖 app/src/test/resources/astc/*.txt）
python tools/astc_fixture/gen_fixture.py --exe /tmp/astc/astc_clamp

# 5) 跑对拍
./gradlew :app:testDebugUnitTest --tests '*AstcDecoderFixtureTest'
```

## 夹具内容

| 文件 | 内容 |
|------|------|
| `astc_blocks.txt` | 8 种块尺寸（4x4/5x5/6x6/8x8/10x10/12x12/8x5/10x6）× 240 个随机块 + 每种尺寸 4 个特殊块（全 0 = 保留块、`0xFC`/`0xFD` = void-extent、`0xFF`），每行给出「输入块 + 输出像素 SHA-256」。`0xFC`/`0xFF` 两种图案的权重位数远超 128（规范里不存在），标成 `unsafe`，测试只校验「不崩 + 输出确定」 |
| `astc_images.txt` | 7 个整图用例（含 37×41 / 13×7 这类非块整数倍尺寸），验证分块拼接与右/下边缘裁剪 |

注意：合法的块里，权重网格右/下边界的第四个采样点权重恒为 0，所以「夹紧索引」与
「越界读零」在**这些块**上结果相同——夹具与 pip wheel 的哈希一致，Kotlin 侧两条语义都能过。
真正会影响结果的是规范外块，因此才有 `unsafe` 这一档。

`gen_fixture.py` 在生成前会用 `decode_block_params` 的移植版过滤「非法块」：
参考实现的栈缓冲只有 `seq[128]` / `ev[32]`，随机块可能把它写爆（UB 甚至段错误），
这类块规格上也不可解码（本项目的解码器会直接渲染成 error 色）。

## 许可

参考实现为 MIT；本项目是 GPLv3。这些脚本与产物只参与测试，不进 APK。
