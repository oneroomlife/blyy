"""生成 ASTC 软件解码的对拍夹具（`app/src/test/resources/astc/*.txt`）。

## 为什么要自己编一个 oracle

- Android 上「GPU 解 ASTC」不可用/不可靠（驱动对块尺寸、宽高、sRGB 的处理各不相同），
  所以 `AstcSoftwareDecoder` 必须在 CPU 上与规格逐像素一致；
- pip 上的 `texture2ddecoder` 与本目录编译出来的产物在同一版本号下就已经
  在个别像素上不一致，且内部读栈上残值（UB），不适合当唯一基准。

因此夹具由 `astc.cpp`（Texture2DDecoder, MIT）+ `patch_weight_clamp.py` 打补丁后
编译出的 `astc_clamp.exe` 生成：输入确定、输出确定，且符合规格。

## 使用

    # 1) 取参考实现源码（MIT）
    pip download texture2ddecoder --no-binary :all: -d /tmp/astc
    tar xzf /tmp/astc/texture2ddecoder-*.tar.gz -C /tmp/astc

    # 2) 打补丁并编译（见 README.md）
    #    astc_ref.exe       —— 原始语义（只用于对照，不进夹具）
    #    astc_clamp.exe     —— 权重夹紧（夹具基准）

    # 3) 生成夹具
    python tools/astc_fixture/gen_fixture.py --exe /tmp/astc/astc_clamp.exe

生成的夹具是**交付物**：`AstcSoftwareDecoderParityTest` 会读它，缺失即失败。
"""

import argparse
import hashlib
import os
import random
import subprocess
import sys

WEIGHT_A = [0, 0, 0, 3, 0, 5, 3, 0, 0, 0, 5, 3, 0, 5, 3, 0]
WEIGHT_B = [0, 0, 1, 0, 2, 0, 1, 3, 0, 0, 1, 2, 4, 2, 3, 5]
CEM_A = [0, 3, 5, 0, 3, 5, 0, 3, 5, 0, 3, 5, 0, 3, 5, 0, 3, 0, 0]
CEM_B = [8, 6, 5, 7, 5, 4, 6, 4, 3, 5, 3, 2, 4, 2, 1, 3, 1, 2, 1]

SIZES = [(4, 4), (5, 5), (6, 6), (8, 8), (10, 10), (12, 12), (8, 5), (10, 6)]
PER_SIZE = 240
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
RES = os.path.join(REPO, 'app', 'src', 'test', 'resources', 'astc')


def _u16(block, offset):
    return block[offset] | (block[offset + 1] << 8)


def block_params(block):
    """移植 `decode_block_params` 的头部解析，用来判定 oracle 是否内存安全。

    参考实现的栈缓冲是 `seq[128]` / `ev[32]`，某些随机块会把它写爆（UB 甚至段错误），
    所以生成夹具前先把这些块剔掉。
    """
    b0, b1 = block[0], block[1]
    u16 = _u16(block, 0)
    dual = 1 if (b1 & 4) else 0
    weight_range = ((b0 >> 4) & 1) | ((b1 << 2) & 8)

    if b0 & 3:
        weight_range |= (b0 << 1) & 6
        case = b0 & 0xC
        if case == 0:
            width, height = ((u16 >> 7) & 3) + 4, ((b0 >> 5) & 3) + 2
        elif case == 4:
            width, height = ((u16 >> 7) & 3) + 8, ((b0 >> 5) & 3) + 2
        elif case == 8:
            width, height = ((b0 >> 5) & 3) + 2, ((u16 >> 7) & 3) + 8
        elif b1 & 1:
            width, height = ((b0 >> 7) & 1) + 2, ((b0 >> 5) & 3) + 2
        else:
            width, height = ((b0 >> 5) & 3) + 2, ((b0 >> 7) & 1) + 6
    else:
        weight_range |= (b0 >> 1) & 6
        case = u16 & 0x180
        if case == 0:
            width, height = 12, ((b0 >> 5) & 3) + 2
        elif case == 0x80:
            width, height = ((b0 >> 5) & 3) + 2, 12
        elif case == 0x100:
            width, height = ((b0 >> 5) & 3) + 6, ((b1 >> 1) & 3) + 6
            dual = 0
            weight_range &= 7
        elif b0 & 0x20:
            width, height = 10, 6
        else:
            width, height = 6, 10

    partitions = ((b1 >> 3) & 3) + 1
    weight_count = width * height * (2 if dual else 1)
    a, b = WEIGHT_A[weight_range], WEIGHT_B[weight_range]
    if a == 3:
        weight_bits = weight_count * b + (weight_count * 8 + 4) // 5
    elif a == 5:
        weight_bits = weight_count * b + (weight_count * 7 + 2) // 3
    else:
        weight_bits = weight_count * b

    if weight_bits > 120 or weight_count > 128:
        raise ValueError('unsafe weights')

    big = int.from_bytes(block, 'little')

    def bits(bit, length):
        if length < 1 or bit < 0:
            return 0
        return (big >> bit) & ((1 << length) - 1)

    cem = [0, 0, 0, 0]
    if partitions == 1:
        cem[0] = (_u16(block, 1) >> 5) & 0xF
        config_bits = 17
    else:
        cem_base = (_u16(block, 2) >> 7) & 3
        if cem_base == 0:
            shared = (block[3] >> 1) & 0xF
            for i in range(partitions):
                cem[i] = shared
            config_bits = 29
        else:
            for i in range(partitions):
                cem[i] = ((((block[3] >> (i + 1)) & 1) + cem_base - 1) << 2)
            if partitions == 2:
                cem[0] |= (block[3] >> 3) & 3
                cem[1] |= bits(126 - weight_bits, 2)
            elif partitions == 3:
                cem[0] |= (block[3] >> 4) & 1
                cem[0] |= bits(122 - weight_bits, 2) & 2
                cem[1] |= bits(124 - weight_bits, 2)
                cem[2] |= bits(126 - weight_bits, 2)
            else:
                for i in range(4):
                    cem[i] |= bits(120 + i * 2 - weight_bits, 2)
            config_bits = 25 + partitions * 3

    endpoint_values = sum(((cem[i] >> 1) & 6) + 2 for i in range(partitions))
    if dual:
        config_bits += 2
    remain = 128 - config_bits - weight_bits

    fits = False
    for i in range(len(CEM_A)):
        if CEM_A[i] == 3:
            needed = endpoint_values * CEM_B[i] + (endpoint_values * 8 + 4) // 5
        elif CEM_A[i] == 5:
            needed = endpoint_values * CEM_B[i] + (endpoint_values * 7 + 2) // 3
        else:
            needed = endpoint_values * CEM_B[i]
        if needed <= remain:
            fits = True
            break
    if not fits or endpoint_values > 32 or remain < 0:
        raise ValueError('unsafe endpoints')
    return True


def safe(block):
    try:
        return block_params(block)
    except ValueError:
        return False


def run_batch(exe, items):
    payload = ''.join(f'{bw} {bh} {hex_block}\n' for bw, bh, hex_block in items)
    proc = subprocess.run([exe], input=payload.encode(), capture_output=True, timeout=900)
    if proc.returncode != 0:
        return None
    lines = proc.stdout.decode().strip().splitlines()
    if len(lines) != len(items):
        return None
    return [(bw, bh, hex_block, out.strip()) for (bw, bh, hex_block), out in zip(items, lines)]


def decode_all(exe, items):
    """分块执行；某块把 oracle 打崩了就逐条重试，仍崩的直接丢弃（UB 块）。"""
    results = []
    chunk = 64
    for start in range(0, len(items), chunk):
        part = items[start:start + chunk]
        got = run_batch(exe, part)
        if got is not None:
            results.extend(got)
            continue
        for one in part:
            single = run_batch(exe, [one])
            if single:
                results.extend(single)
    return results


def argb_to_bgra(argb_hex):
    raw = bytes.fromhex(argb_hex)
    out = bytearray(len(raw))
    for i in range(0, len(raw), 4):
        a, r, g, b = raw[i], raw[i + 1], raw[i + 2], raw[i + 3]
        out[i] = b
        out[i + 1] = g
        out[i + 2] = r
        out[i + 3] = a
    return bytes(out)


def digest(argb_hex):
    return hashlib.sha256(argb_to_bgra(argb_hex)).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--exe', required=True, help='夹紧版 oracle（astc_clamp.exe）')
    parser.add_argument('--seed', type=int, default=20261029)
    args = parser.parse_args()

    os.makedirs(RES, exist_ok=True)
    rng = random.Random(args.seed)

    blocks = []
    for bw, bh in SIZES:
        kept, guard = 0, 0
        while kept < PER_SIZE and guard < PER_SIZE * 800:
            guard += 1
            block = bytes(rng.getrandbits(8) for _ in range(16))
            if not safe(block):
                continue
            blocks.append((bw, bh, block.hex()))
            kept += 1
        assert kept == PER_SIZE, f'{bw}x{bh} 只取到 {kept} 个安全块'
    results = decode_all(args.exe, blocks)
    print(f'块用例解码 {len(results)}/{len(blocks)}')

    lines = [
        '# ASTC 软件解码对拍夹具（oracle = Texture2DDecoder astc.cpp + 权重夹紧补丁，勿手改）',
        '# block  <bw> <bh> <16字节块hex> <像素 sha256>   逐字节对拍',
        '# unsafe <bw> <bh> <16字节块hex> <像素 sha256>   规范外块，只校验确定/不崩',
    ]
    for bw, bh, hex_block, argb in results:
        # 0xFF / 0xFC 图案的权重位数远超 128，属于规范里不存在的块：
        # oracle 在这里读越界内存（UB），只校验「不崩 + 输出确定」，不参与哈希对拍。
        unsafe = hex_block[:4] in ("fcfc", "ffff")
        lines.append(
            f'{"unsafe" if unsafe else "block"} {bw} {bh} {hex_block} {digest(argb)}'
        )

    specials = []
    for bw, bh in SIZES:
        for block in (bytes(16), b'\xfc' * 16, b'\xfd' * 16, b'\xff' * 16):
            specials.append((bw, bh, block.hex()))
    for bw, bh, hex_block, argb in decode_all(args.exe, specials):
        # 0xFF / 0xFC 图案的权重位数远超 128，属于规范里不存在的块：
        # oracle 在这里读越界内存（UB），只校验「不崩 + 输出确定」，不参与哈希对拍。
        unsafe = hex_block[:4] in ("fcfc", "ffff")
        lines.append(
            f'{"unsafe" if unsafe else "block"} {bw} {bh} {hex_block} {digest(argb)}'
        )

    with open(os.path.join(RES, 'astc_blocks.txt'), 'w', encoding='utf-8', newline='\n') as file:
        file.write('\n'.join(lines) + '\n')
    print('块用例总数', len(lines) - 2)

    image_lines = ['# 整图用例（验证分块拼接与右/下边缘裁剪）: image <w> <h> <bw> <bh> <数据hex> <sha256>']
    for width, height, bw, bh in [(20, 14, 6, 6), (13, 7, 4, 4), (25, 19, 8, 5),
                                  (64, 64, 12, 12), (37, 41, 5, 5), (11, 11, 10, 10),
                                  (30, 30, 6, 6)]:
        needed = ((width + bw - 1) // bw) * ((height + bh - 1) // bh)
        data = []
        while len(data) < needed:
            block = bytes(rng.getrandbits(8) for _ in range(16))
            if safe(block):
                data.append(block)
        data = data[:needed]
        decoded = decode_all(args.exe, [(bw, bh, block.hex()) for block in data])
        canvas = [0] * (width * height)
        blocks_x = (width + bw - 1) // bw
        for index, (_, _, _, argb) in enumerate(decoded):
            base_x = (index % blocks_x) * bw
            base_y = (index // blocks_x) * bh
            for row in range(bh):
                y = base_y + row
                if y >= height:
                    break
                for col in range(bw):
                    x = base_x + col
                    if x >= width:
                        break
                    canvas[y * width + x] = int(argb[(row * bw + col) * 8:(row * bw + col) * 8 + 8], 16)
        raw = bytearray(width * height * 4)
        for i, value in enumerate(canvas):
            raw[i * 4] = value & 0xFF
            raw[i * 4 + 1] = (value >> 8) & 0xFF
            raw[i * 4 + 2] = (value >> 16) & 0xFF
            raw[i * 4 + 3] = (value >> 24) & 0xFF
        image_lines.append(
            f'image {width} {height} {bw} {bh} {"".join(block.hex() for block in data)} '
            f'{hashlib.sha256(bytes(raw)).hexdigest()}'
        )
    with open(os.path.join(RES, 'astc_images.txt'), 'w', encoding='utf-8', newline='\n') as file:
        file.write('\n'.join(image_lines) + '\n')
    print('整图用例总数', len(image_lines) - 1)


if __name__ == '__main__':
    main()
