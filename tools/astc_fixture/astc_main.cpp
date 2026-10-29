// ASTC 参考解码 CLI（对拍 oracle 用）。
//
// 从标准输入逐行读取 `bw bh <32位hex块>`，输出每块 `bw*bh` 个 `%08x`（ARGB）。
// 与 astc.cpp 一起编译：
//
//   g++ -O2 -std=c++14 -I. -o astc_ref.exe astc_main.cpp astc.cpp
//
// 该二进制只用于**离线生成测试夹具**，不进 APK。

#include <cstdio>
#include <cstdint>
#include <cstring>

int decode_astc(const uint8_t *data, const long w, const long h, const int bw, const int bh, uint32_t *image);

int main() {
    int bw, bh;
    char hex[128];
    while (scanf("%d %d %127s", &bw, &bh, hex) == 3) {
        uint8_t block[16];
        for (int i = 0; i < 16; i++) {
            unsigned value;
            sscanf(hex + i * 2, "%2x", &value);
            block[i] = static_cast<uint8_t>(value);
        }
        uint32_t image[144];
        memset(image, 0, sizeof(image));
        decode_astc(block, bw, bh, bw, bh, image);
        for (int i = 0; i < bw * bh; i++) printf("%08x", image[i]);
        printf( "\n" );
    }
    return 0;
}
