#!/usr/bin/env bash
# Nascence 终端特性自测脚本
#
# 用法:
#   bash scripts/nascence-selftest.sh              交互菜单
#   bash scripts/nascence-selftest.sh all          依次跑完所有分节
#   bash scripts/nascence-selftest.sh underline    只跑某一节
#
# 分节: underline ligature hyperlink notify progress sync scrollback kitty
# 注意: 这个脚本只测「终端模拟器层面」的东西, 抽屉/主题/壁纸等界面功能手动测。

set -u

bold() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
hint() { printf '\033[2m%s\033[0m\n' "$*"; }
pause() { printf '\n\033[2m按 Enter 继续…\033[0m'; read -r _ || true; }

underline() {
    bold "下划线样式 (SGR 4:x)"
    printf ' %s\n' '单线 single'
    printf '   \e[4:1m单线 single underline\e[0m\n'
    printf ' %s\n' '双线 double'
    printf '   \e[4:2m双线 double underline\e[0m\n'
    printf ' %s\n' '波浪 curly'
    printf '   \e[4:3m波浪 curly underline\e[0m\n'
    printf ' %s\n' '点线 dotted'
    printf '   \e[4:4m点线 dotted underline\e[0m\n'
    printf ' %s\n' '虚线 dashed'
    printf '   \e[4:5m虚线 dashed underline\e[0m\n'

    bold "下划线不应压住 descender"
    printf ' \e[4:1mgggg jjjj pppp yyyy qqqq\e[0m\n'
    printf ' \e[4:3mgggg jjjj pppp yyyy qqqq\e[0m\n'
    printf ' \e[4:2mgggg jjjj pppp yyyy qqqq\e[0m\n'

    bold "下划线颜色: 索引色 (SGR 58;5;N)"
    printf ' \e[4m\e[58;5;196m indexed 196 \e[0m '
    printf '\e[4m\e[58;5;46m indexed 46 \e[0m '
    printf '\e[4m\e[58;5;33m indexed 33 \e[0m '
    printf '\e[4m\e[58;5;226m indexed 226 \e[0m\n'

    bold "下划线颜色: 24 位色 (SGR 58;2;R;G;B)"
    printf ' \e[4m\e[58;2;255;80;80m truecolor f05050 \e[0m '
    printf '\e[4m\e[58;2;80;200;255m truecolor 50c8ff \e[0m\n'
    printf ' \e[4:3m\e[58;2;180;120;255m curly truecolor \e[0m '
    printf '\e[4:2m\e[58;2;120;255;120m double truecolor \e[0m\n'

    bold "颜色与样式组合, 以及 SGR 59 复位"
    printf ' \e[4:5m\e[58;5;203m dashed 203 \e[0m '
    printf '\e[4:4m\e[58;5;117m dotted 117 \e[0m '
    printf '\e[4m\e[58;5;9m explicit \e[59m default again \e[0m\n'
}

ligature() {
    bold "连字 (取决于 ~/.termux/font.ttf)"
    printf ' %s\n' '-> ==> <== != <= >= === !== |> :: ... ?? // ~~'
    printf ' %s\n' '|> <| <> <=> =!= --- ==== >>= <<= //// /* */ <-- -->'
    hint "每一项都应是连体字形; 若是分开的两个字符, 说明该字体没有这个连字。"
    hint "对照实验: 把 ~/.termux/font.ttf 改名后 termux-reload-settings, 内置 JetBrains Mono 应能全部连字。"
}

hyperlink() {
    bold "OSC 8 超链接 (单击打开, 长按是选择文本)"
    printf '\e]8;;https://example.com\e\\以 example.com 为文字的超链接\e]8;;\e\\\n'
    printf '\e]8;;https://github.com/termux/termux-app\e\\https://github.com/termux/termux-app\e]8;;\e\\\n'
    hint "未设置显式下划线时, 超链接会有一条点线提示。"
}

notify() {
    bold "OSC 通知 (需要在系统设置里给 Nascence 通知权限)"
    printf '\e]9;OSC 9 通知测试\a'
    sleep 0.4
    printf '\e]99;;OSC 99 通知测试\a'
    sleep 0.4
    printf '\e]777;notify;Nascence;OSC 777 通知测试\a'
    printf '\n已依次发送 OSC 9 / 99 / 777 三条通知, 请检查通知栏。\n'
    hint "点通知应回到产生它的那个 session。"
}

progress() {
    bold "OSC 9;4 进度条 (终端顶部细条)"
    printf ' 正常进度 (绿): '
    for p in 0 20 40 60 80 100; do
        printf '\e]9;4;1;%d\a' "$p"
        printf '%s ' "$p"
        sleep 0.25
    done
    printf '\n 错误状态 (红): '
    printf '\e]9;4;2;100\a'
    sleep 1.2
    printf '\n 暂停状态 (黄): '
    printf '\e]9;4;4;60\a'
    sleep 1.2
    printf '\n 不确定状态 (滑块): '
    printf '\e]9;4;3\a'
    sleep 2
    printf '\n 清除\n'
    printf '\e]9;4;0\a'
    hint "进度条画在终端可视区最顶部, 高度约为行高的 7%。"
}

sync() {
    bold "DECSET 2026 同步输出 (整块重绘不应闪烁)"
    printf '\e[?2026h'
    printf '\n%.0s' 1 2 3 4 5 6 7 8 9 10 11 12
    for frame in $(seq 1 60); do
        printf '\e[12A'
        for row in $(seq 1 12); do
            printf '\e[2K frame %02d  row %02d  %s\r\n' "$frame" "$row" '########################'
        done
        sleep 0.03
    done
    printf '\e[?2026l'
    printf '完成。60 帧整块重绘期间不应看到半帧或撕裂。\n'
}

scrollback() {
    bold "滚动 / 搜索 / 回到底部"
    for i in $(seq 1 200); do
        printf 'scrollback 第 %03d 行 — nascence marker ABC-%03d\n' "$i" "$i"
    done
    printf '\n'
    hint "1) 上滑查看历史: 右下角应出现「回到底部」浮动按钮"
    hint "2) 长按 → 搜索, 输入 marker ABC-042, 应高亮并显示 1/1"
    hint "3) 保持上滑, 接下来每秒输出一行: 5 秒内视图不应被拉回底部, 之后恢复跟随"
    pause

    for i in $(seq 1 8); do
        printf 'live output %02d\n' "$i"
        sleep 1
    done
    hint "第 6 行左右视图应自动回到底部 (距上次交互已超过 5 秒)。"
}

kitty() {
    bold "Kitty 键盘协议 (观察应用实际发出的字节)"
    cat <<'TXT'
 流程: 先发 CSI ? u 查询, 终端回答 CSI ? <flags> u;
 然后推入 flags=9 (1 = 功能键走 CSI-u, 8 = 所有键都上报), 再按键观察;
 退出时 pop 并恢复 flags。
 期望: 方向键 \e[A (带修饰键是 \e[1;5A), PageUp \e[5~, Home \e[H,
       普通字母 \e[97;1u, Enter \e[13u, Tab \e[9u, Backspace \e[127u。
 如果之后 shell 的按键失灵, 输入 reset 回车即可恢复。
 按 Ctrl+C (或等待 60 秒) 退出。
TXT
    python3 - <<'PY'
import os, select, sys, termios, tty, time

fd = sys.stdin.fileno()
old = termios.tcgetattr(fd)


def write(data):
    os.write(fd, data)


try:
    tty.setraw(fd)
    write(b"\x1b[?u")   # query active flags
    write(b"\x1b[>9u")  # push and set flags 1 | 8
    sys.stdout.write("flags pushed. 按键查看字节, Ctrl+C 退出\r\n")
    sys.stdout.flush()
    deadline = time.time() + 60
    while time.time() < deadline:
        ready, _, _ = select.select([fd], [], [], 0.5)
        if not ready:
            continue
        data = os.read(fd, 128)
        if data == b"\x03" or b"\x1b[99;5u" in data or b"\x1b[99;5;1u" in data:
            break
        sys.stdout.write(repr(data) + "\r\n")
        sys.stdout.flush()
finally:
    write(b"\x1b[<u")   # pop
    termios.tcsetattr(fd, termios.TCSADRAIN, old)
    sys.stdout.write("\r\nflags popped\r\n")
    sys.stdout.flush()
PY
}

ALL_SECTIONS="underline ligature hyperlink notify progress sync scrollback"

run_section() {
    case "$1" in
        underline|ligature|hyperlink|notify|progress|sync|scrollback|kitty) "$1" ;;
        *) printf '未知分节: %s\n' "$1" >&2; return 1 ;;
    esac
}

if [ $# -gt 0 ]; then
    case "$1" in
        all)
            for s in $ALL_SECTIONS; do
                run_section "$s"
                pause
            done
            run_section kitty
            ;;
        *) run_section "$1" ;;
    esac
    exit 0
fi

while true; do
    printf '\n\033[1mNascence 终端自测\033[0m\n'
    printf '  1) 下划线样式与颜色        (underline)\n'
    printf '  2) 连字                    (ligature)\n'
    printf '  3) OSC 8 超链接            (hyperlink)\n'
    printf '  4) OSC 通知                (notify)\n'
    printf '  5) OSC 9;4 进度条          (progress)\n'
    printf '  6) DECSET 2026 同步输出    (sync)\n'
    printf '  7) 滚动/搜索/回到底部      (scrollback)\n'
    printf '  8) Kitty 键盘协议          (kitty)\n'
    printf '  a) 全部\n'
    printf '  q) 退出\n'
    printf '选择: '
    read -r choice || exit 0
    case "$choice" in
        1) underline ;;
        2) ligature ;;
        3) hyperlink ;;
        4) notify ;;
        5) progress ;;
        6) sync ;;
        7) scrollback ;;
        8) kitty ;;
        a) for s in $ALL_SECTIONS; do run_section "$s"; pause; done; kitty ;;
        q) exit 0 ;;
        *) printf '无效选择\n' ;;
    esac
done
