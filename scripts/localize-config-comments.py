# -*- coding: utf-8 -*-
"""把 war_project 配置文件的英文注释就地替换为中文（只动注释，不动键与值）。

用法: python zh_config_comments.py <toml路径>
"""
import sys
import hashlib
import os

M = [
    ('Base seconds required to capture a node.',
     '占领一个节点所需的基础秒数。'),
    ('Progress seconds recovered each second when capture stops.',
     '占领停止后，每秒回退的进度秒数。'),
    ('Extra capture speed multiplier for each additional leading player.',
     '领先方每多一名玩家，额外增加的占领速度倍率。'),
    ('Maximum capture speed multiplier from player count.',
     '由在场人数带来的占领速度倍率上限。'),
    ('Enable capture debug logging.',
     '开启占领调试日志。'),
    ('Seconds between resource settlements; each settlement grants output * seconds / 60.',
     '资源结算的间隔秒数；每次结算发放「每分钟产出 × 间隔秒数 ÷ 60」。'),
    ('Enable resource settlement debug logging.',
     '开启资源结算调试日志。'),
    ('Seconds a player may stay outside the map area before being killed.',
     '玩家越出地图边界后允许停留的秒数，超时将被击杀。'),
    ('Seconds a player must wait between two resource transfers (0 disables the cooldown).',
     '两次资源转移之间必须等待的秒数（0 表示无冷却）。'),
    ('Maximum ammo a single transfer may send.',
     '单次资源转移可发送的弹药上限。'),
    ('Maximum fuel a single transfer may send.',
     '单次资源转移可发送的燃料上限。'),
    ('Starting war score of every allied cluster (side) shown on the VP progress bar.',
     'VP 战局条上每个同盟簇（阵营）的初始战局分。'),
    ('The score drops while the opposing sides hold VP nodes; a side reaching zero ends the game.',
     '对方占据 VP 节点时本方分数会持续下降；任一方归零即结束战局。'),
    ('Points per minute the opposing side loses for every VP node this side holds.',
     '本方每占据一个 VP 节点，对方每分钟被扣除的分数。'),
    ('Several VP nodes add up (three nodes -> 3x this value per minute).',
     '多个 VP 节点可叠加（占三个节点即每分钟扣三倍该值）。'),
    ('Which backend draws the resource island and the transfer panel.',
     '由哪个后端绘制资源条与转移面板。'),
    ('auto     - use Chromium when it is available, otherwise the built in HTML renderer',
     'auto     - 可用时使用 Chromium，否则回退到内置 HTML 渲染器'),
    ('native   - always use the built in HTML renderer',
     'native   - 始终使用内置 HTML 渲染器'),
    ('chromium - request Chromium; the built in renderer still takes over if it cannot start',
     'chromium - 申请使用 Chromium；若无法启动仍由内置渲染器接管'),
    ('Download mirror for the Chromium Embedded Framework binaries; empty uses the public build host.',
     'Chromium Embedded Framework 二进制的下载镜像地址；留空则使用公开构建源。'),
    ('Log Chromium surface diagnostics (frames, uploads, frame time) every 60 seconds.',
     '每 60 秒输出一次 Chromium 表面诊断日志（帧数、上传次数、帧耗时）。'),
    ('Upper bound on how many frames per second the Chromium surface may produce.',
     'Chromium 表面每秒最多可产出的帧数上限。'),
    ('Chromium only paints while its message loop is pumped, so this is the real frame',
     'Chromium 只在其消息循环被驱动时才会绘制，因此这个值就是真实帧率：'),
    ('rate: the pages are static apart from short transitions, and a lower value means',
     '页面除短暂过渡外基本静止，取更低的值意味着'),
    ('less CPU for a surface nobody is animating. 30 is plenty for this interface.',
     '为一块无人在动的表面省下 CPU。对本界面而言 30 已经足够。'),
    ('Only the Chromium backend uses it.',
     '仅 Chromium 后端使用此项。'),
    ('Start Chromium only when the interface first has to be shown instead of at game',
     '仅在界面首次需要显示时才启动 Chromium，而不是在游戏启动时。'),
    ('startup. A session that never shows the island then runs without any browser',
     '这样整局都不显示资源条的会话可以完全不启动浏览器进程'),
    ('process at all (no idle CPU, no ~150 MiB of helper processes); the built in',
     '（没有空转 CPU 开销，也不占用约 150 MiB 的辅助进程）；'),
    ('renderer draws the island until Chromium is up.',
     '在 Chromium 就绪之前由内置渲染器绘制资源条。'),
    ('Let Chromium rasterise and composite on the graphics card instead of the CPU.',
     '让 Chromium 使用显卡而非 CPU 进行光栅化与合成。'),
    ('Both modes hand the finished pixels back to the game as a CPU bitmap (the',
     '两种模式都要把最终像素以 CPU 位图形式交回游戏（java-cef'),
    ('java-cef bindings have no shared-texture path), so the GPU mode additionally pays',
     '绑定没有共享纹理通路），因此 GPU 模式还要额外付出'),
    ('for a GPU-to-CPU read back and for a GPU process competing with Minecraft.',
     '一次 GPU 到 CPU 的回读，以及一个与 Minecraft 争抢显卡的 GPU 进程。'),
    ('Measured on the Intel UHD 630 of this machine with a 704x600 surface: hardware',
     '在本机 Intel UHD 630、704x600 表面上的实测：硬件'),
    ('GPU ~14% of one core, software (SwiftShader) ~9%. Default false therefore, and',
     'GPU 约占单核 14%，软件渲染（SwiftShader）约 9%，故默认 false；'),
    ('true is there for machines where the calculation comes out the other way.',
     '若某些机器上结论相反，可改为 true。'),
    ('Setting this changes the real Chromium command line: with false it starts with',
     '此项会改变真实的 Chromium 命令行：false 时带'),
    ('--disable-gpu and friends, with true it does not.',
     '--disable-gpu 等参数，true 时不带。'),
    # 上一次诊断写入的临时串，恢复成正式译文
    ('ZH-TEST-占领测试',
     '占领一个节点所需的基础秒数。'),
]

def chinese_lines(data):
    text = data.decode('utf-8', 'replace')
    return sum(1 for line in text.splitlines()
               if any('\u4e00' <= ch <= '\u9fff' for ch in line))

def main():
    path = sys.argv[1]
    print('target:', os.path.abspath(path))
    data = open(path, 'rb').read()
    before = hashlib.md5(data).hexdigest()
    replaced, missing, ambiguous = 0, 0, 0
    for old, new in M:
        ob = old.encode('utf-8')
        count = data.count(ob)
        if count == 1:
            data = data.replace(ob, new.encode('utf-8'))
            replaced += 1
        elif count == 0:
            missing += 1
        else:
            ambiguous += 1
            print('  AMBIGUOUS x%d: %s' % (count, old[:50]))
    with open(path, 'wb') as fh:
        fh.write(data)
        fh.flush()
        os.fsync(fh.fileno())
    readback = open(path, 'rb').read()
    print('replaced segments :', replaced)
    print('not found         :', missing)
    print('ambiguous         :', ambiguous)
    print('md5 before        :', before)
    print('md5 after write   :', hashlib.md5(readback).hexdigest())
    print('bytes             :', len(readback))
    print('chinese lines     :', chinese_lines(readback))
    remaining = sum(1 for old, _ in M if old.encode('utf-8') in readback)
    print('english left      :', remaining)

main()
