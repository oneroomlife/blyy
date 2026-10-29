"""把参考实现复制一份，并给「权重网格越界」打上夹紧补丁。

## 为什么要打补丁

`Texture2DDecoder/astc.cpp`（与 UnityPy / AssetStudio 同源）在双线性再采样权重时直接
访问 `wv[(v + width + 1) * pn + p]`，块的最后一行/列会读到数组外的栈上残值：

- 同一输入在不同构建/不同调用顺序下结果可能不同（未定义行为）；
- 表现为贴图块边缘出现一条颜色错误的像素带。

ASTC 规格要求把索引**夹紧到权重网格内**（astcenc 的 `bilerp` 就是这么做的），
因此夹具用的 oracle 是这个「夹紧版」，本项目的 Kotlin 解码器与它逐像素对齐。

用法：

    python patch_weight_clamp.py <astc.cpp> <输出 astc_clamp.cpp>
"""

import sys

ORIGINAL = """            for (int p = 0; p < pn; p++) {
                int p00 = wv[v * pn + p];
                int p01 = wv[(v + 1) * pn + p];
                int p10 = wv[(v + data->width) * pn + p];
                int p11 = wv[(v + data->width + 1) * pn + p];
                data->weights[i][p] = (p00 * w00 + p01 * w01 + p10 * w10 + p11 * w11 + 8) >> 4;
            }"""

PATCHED = """            for (int p = 0; p < pn; p++) {
                /* SPEC 语义：越界索引必须夹紧到权重网格内（astcenc 的 bilerp 做法）。
                   原始实现直接读 wv[v+width+1]，最后一列/行会读到数组外的栈数据。 */
                int s1c = (gs >> 4) + 1 < data->width ? (gs >> 4) + 1 : data->width - 1;
                int t1c = (gt >> 4) + 1 < data->height ? (gt >> 4) + 1 : data->height - 1;
                int v00 = (gs >> 4) + (gt >> 4) * data->width;
                int v01 = s1c + (gt >> 4) * data->width;
                int v10 = (gs >> 4) + t1c * data->width;
                int v11 = s1c + t1c * data->width;
                int p00 = wv[v00 * pn + p];
                int p01 = wv[v01 * pn + p];
                int p10 = wv[v10 * pn + p];
                int p11 = wv[v11 * pn + p];
                data->weights[i][p] = (p00 * w00 + p01 * w01 + p10 * w10 + p11 * w11 + 8) >> 4;
            }"""


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 1
    src_path, out_path = sys.argv[1], sys.argv[2]
    source = open(src_path, encoding='utf-8').read()
    if ORIGINAL not in source:
        raise SystemExit('未找到待替换的权重插值代码块，参考实现可能已变版本')
    open(out_path, 'w', encoding='utf-8', newline='\n').write(source.replace(ORIGINAL, PATCHED))
    print('已写出夹紧版：', out_path)
    return 0


if __name__ == '__main__':
    sys.exit(main())
