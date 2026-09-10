#!/bin/sh
# 容器初始化依赖安装脚本（统一）：内置 Alpine 与自定义镜像一致——首次进入终端时在 PTY 上弹出
# 交互菜单，由用户选择自动安装基础工具、自定义安装（逐项勾选 Node.js/Python/Java/Go 等运行时，
# 基础工具 bash/curl/ripgrep/git 始终安装）、手动安装不再提示、或退出。自动/自定义安装都会先
# 列出安装清单，输入 y 确认后才开始安装；脚本按容器内包管理器（apk/apt-get/dnf/yum/pacman）
# 安装所选工具并可选换国内镜像源。
# 由 App 启动时提取到 ~/.aicode/provision.sh（容器内 /root/.aicode/provision.sh，经 -b 绑定可见）。
# 修改包清单/安装逻辑/镜像源后，需同步在 LinuxContainerEngine.PROVISION_VERSION 上 +1 触发存量设备重跑。
# 注意：apk 源分支 v3.21 需与 assets 内 alpine-rootfs 版本（ContainerInstaller.INSTALL_VERSION）保持一致。

PROVISION_VERSION="provision-script-v9"
PROVISION_SKIPPED="provision-script-skipped"
MARKER="/.provisioned"
# 多镜像候选（按优先级排序，换源时自动探测跳过不可用；阿里云对服务器访问全量 403 放最后，探测会跳过）
# 对应 ContainerInstaller.kt 的 ALPINE_MIRROR 需同步（内置 Alpine 容器 apk 源，同样改为多镜像策略）
MIRRORS="mirrors.huaweicloud.com mirrors.tuna.tsinghua.edu.cn mirrors.ustc.edu.cn mirrors.cloud.tencent.com mirrors.aliyun.com"
MIRROR=""
# 基础工具（不参与自定义勾选，始终安装）：git/ripgrep 是 AI 工作流与版本管理基础，bash/curl 是通用依赖
BASE_PKGS="bash curl ripgrep git"

# ── 诊断日志：写入宿主可见的 $HOME/.aicode/provision.log（容器内 /root/.aicode 绑定到 App 私有目录）。
# 终端 PTY 起不来或卡住时，App 侧「容器诊断」会把本文件尾部一并打进日志，用于判断脚本执行到了哪一步、
# 菜单有没有弹出来。超过 256KB 只保留尾部 64KB，避免每次进终端追加一行而无限增长。全部静默失败，不阻塞初始化。
PROVISION_LOG="$HOME/.aicode/provision.log"
plog() {
    [ -n "$HOME" ] || return 0
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" "$*" >> "$PROVISION_LOG" 2>/dev/null || return 0
    sz=$(wc -c < "$PROVISION_LOG" 2>/dev/null || echo 0)
    if [ "$sz" -gt 262144 ] 2>/dev/null; then
        tail -c 65536 "$PROVISION_LOG" > "$PROVISION_LOG.tmp" 2>/dev/null && mv "$PROVISION_LOG.tmp" "$PROVISION_LOG" 2>/dev/null
    fi
    return 0
}
plog "provision.sh 启动：version=$PROVISION_VERSION HOME=$HOME uid=$(id -u 2>/dev/null) PATH=$PATH"

# ── 宿主 supplementary gid 修复：proot 会把宿主进程的补充组（Android AID 3003 inet、
# 9997 everybody、App 自身 uid 派生 gid 等）透传进容器，/etc/group 查不到名字会让
# groups 等命令报警（cannot find name for group ID xxx）。幂等补行：gid_ 命名空间先清再补
# 防历史残留；位于 MARKER 检查之前，每次进终端都执行（已配置设备同样生效），gid 变化自动跟上。
# 全部静默失败，不阻塞进入 shell。
sed -i '/^gid_/d' /etc/group 2>/dev/null
for g in $(id -G 2>/dev/null); do
    grep -q ":x:$g:" /etc/group 2>/dev/null || echo "gid_$g:x:$g:" >> /etc/group 2>/dev/null
done

# 已按当前版本完成或用户选择手动安装（跳过）则直接退出
if [ -f "$MARKER" ]; then
    state=$(cat "$MARKER" 2>/dev/null)
    if [ "$state" = "$PROVISION_VERSION" ]; then plog "标记已是 $PROVISION_VERSION，跳过菜单"; exit 0; fi
    if [ "$state" = "$PROVISION_SKIPPED" ]; then plog "用户曾选择手动安装，跳过菜单"; exit 0; fi
fi

# ── 包管理器探测：菜单前执行一次，供安装/清单共用 ──
detect_pmgr() {
    if command -v apk >/dev/null 2>&1; then
        PMGR=apk
    elif command -v apt-get >/dev/null 2>&1; then
        PMGR=apt
    elif command -v dnf >/dev/null 2>&1; then
        PMGR=dnf
    elif command -v yum >/dev/null 2>&1; then
        PMGR=yum
    elif command -v pacman >/dev/null 2>&1; then
        PMGR=pacman
    else
        PMGR=
    fi
}

# ── 按包管理器安装一批包 ──
pkg_add() {
    case "$PMGR" in
        apk)
            apk update
            apk add --no-cache "$@"
            ;;
        apt)
            export DEBIAN_FRONTEND=noninteractive
            apt-get update -y
            apt-get install -y "$@"
            ;;
        dnf) dnf install -y "$@" ;;
        yum) yum install -y "$@" ;;
        pacman) pacman -Sy --noconfirm "$@" ;;
        *)
            echo "未支持的包管理器（apk/apt-get/dnf/yum/pacman）" >&2
            return 1
            ;;
    esac
}

# ── apt 下探测可用的 openjdk 包名（trixie 只有 21/25 无 17，bookworm 默认 17；LTS 21 优先）──
apt_java_headless() {
    for p in openjdk-21-jdk-headless openjdk-17-jdk-headless; do
        if apt-cache policy "$p" 2>/dev/null | grep -q '^  Candidate: [0-9]'; then
            echo "$p"
            return 0
        fi
    done
    echo "openjdk-17-jdk-headless"
}

# ── 运行时 → 当前包管理器下的包名映射（自定义安装清单与安装共用）──
runtime_pkgs() {
    case "$PMGR:$1" in
        apk:node)    echo "nodejs npm" ;;
        apk:python)  echo "python3 py3-pip" ;;
        apk:java)    echo "openjdk17-jdk" ;;
        apk:go)      echo "go" ;;
        apt:node)    echo "nodejs npm" ;;
        apt:python)  echo "python3 python3-pip" ;;
        apt:java)    echo "$(apt_java_headless)" ;;
        apt:go)      echo "golang-go" ;;
        dnf:node|yum:node)     echo "nodejs npm" ;;
        dnf:python|yum:python) echo "python3 python3-pip" ;;
        dnf:java|yum:java)     echo "java-17-openjdk-headless" ;;
        dnf:go|yum:go)         echo "golang" ;;
        pacman:node)   echo "nodejs npm" ;;
        pacman:python) echo "python python-pip" ;;
        pacman:java)   echo "jdk17-openjdk" ;;
        pacman:go)     echo "go" ;;
        apk:rust)      echo "rust cargo" ;;
        apk:php)       echo "php83 composer" ;;
        apt:rust)      echo "rustc cargo" ;;
        apt:php)       echo "php-cli composer" ;;
        dnf:rust|yum:rust) echo "rust cargo" ;;
        dnf:php)           echo "php-cli composer" ;;
        yum:php)           echo "php-cli" ;;
        pacman:rust)   echo "rust" ;;
        pacman:php)    echo "php" ;;
    esac
}

# ── 列出某包在仓库中的可用版本（每行一个，最新在前；保留完整版本串供安装固定；失败返回空）──
pkg_versions() {
    pkg="$1"
    case "$PMGR" in
        # apk search -v 输出为「包名-版本 - 描述…」且 -e 会命中 provides 相似包（如 nodejs-current），
        # 用「包名-数字」过滤只取真实包行，再取第一列版本；多源/多仓库行按版本序（最新在前）去重
        apk) apk search -e -v "$pkg" 2>/dev/null | grep -E "^$pkg-[0-9]" | sed -n "s/^$pkg-//p" | awk '{print $1}' | sort -Vu -r ;;
        # madison 无表头，首行即数据；多个源/仓库会有多行，按版本序（最新在前）去重
        apt) apt-cache madison "$pkg" 2>/dev/null | awk -F'|' '{gsub(/^ +| +$/, "", $2); print $2}' | sort -Vu -r ;;
        dnf) dnf list available --showduplicates "$pkg" 2>/dev/null | awk 'NR>2 {print $2}' | sort -Vu -r ;;
        yum) yum list available "$pkg" 2>/dev/null | awk 'NR>2 {print $2}' | sort -Vu -r ;;
        pacman) pacman -Si "$pkg" 2>/dev/null | sed -n 's/^Version *: //p' ;;
    esac
}

# ── 候选主版本包探测：java/php 不锁死单一版本，列出仓库真实可用的主版本包（最新在前）；其余运行时单包 ──
runtime_candidates() {
    case "$PMGR:$1" in
        # apk search 输出「包名-版本」，不能直接用；改为已知候选列表 + 精确存在性验证，输出纯包名
        apk:java)
            for p in openjdk25-jdk openjdk21-jdk openjdk17-jdk openjdk11-jdk openjdk8-jdk; do
                [ -n "$(pkg_versions "$p" | head -1)" ] && echo "$p"
            done
            ;;
        apt:java)  apt-cache search '^openjdk-[0-9]*-jdk-headless$' 2>/dev/null | awk '{print $1}' | sort -V -r ;;
        dnf:java|yum:java)
            for p in java-21-openjdk-headless java-17-openjdk-headless; do
                [ -n "$(pkg_versions "$p" | head -1)" ] && echo "$p"
            done
            ;;
        pacman:java)
            for p in jdk21-openjdk jdk17-openjdk jdk-openjdk; do
                [ -n "$(pkg_versions "$p" | head -1)" ] && echo "$p"
            done
            ;;
        apk:php)
            for p in php84 php83 php82; do
                [ -n "$(pkg_versions "$p" | head -1)" ] && echo "$p"
            done
            ;;
        apt:php)
            for p in php8.4-cli php8.3-cli php8.2-cli; do
                [ -n "$(pkg_versions "$p" | head -1)" ] && echo "$p"
            done
            ;;
        *)
            echo "$(runtime_pkgs "$1" | awk '{print $1}')"
            ;;
    esac
}

# ── 运行时主包版本号（显示用，取默认候选，去掉 apk 的 -rN 后缀）──
runtime_ver() {
    pkg_versions "$(runtime_candidates "$1" | head -1)" | head -1 | sed 's/-r[0-9]*$//'
}

# ── 更新软件包索引（版本探测与安装的前置步骤）──
pkg_update() {
    case "$PMGR" in
        apk) apk update ;;
        apt) apt-get update -y ;;
        dnf) dnf makecache -y ;;
        yum) yum makecache -y ;;
        pacman) pacman -Sy --noconfirm ;;
    esac
}

# ── 候选主版本选择：列出可用主版本包（纯包名），单选一个序号（回车/非法输入回退第 1 个）；结果写 $PICKED_PKG ──
pick_candidate() {
    PICKED_PKG=""
    cands=$(runtime_candidates "$1")
    [ -z "$cands" ] && return 1
    n=1
    for c in $cands; do
        echo "    $n) $c"
        n=$((n + 1))
    done
    printf "选择主版本（序号）: "
    read ans
    case "$ans" in
        ""|*[!0-9]*) PICKED_PKG=$(echo "$cands" | head -1) ;;
        *) PICKED_PKG=$(echo "$cands" | sed -n "${ans}p" | head -1) ;;
    esac
    [ -z "$PICKED_PKG" ] && PICKED_PKG=$(echo "$cands" | head -1)
}

# ── 运行时主包名（自定义安装优先用所选候选包，未选回退默认候选）──
pkg_for() {
    case "$1" in
        node)   [ -n "$node_pkg" ] && echo "$node_pkg" || runtime_candidates node | head -1 ;;
        python) [ -n "$python_pkg" ] && echo "$python_pkg" || runtime_candidates python | head -1 ;;
        java)   [ -n "$java_pkg" ] && echo "$java_pkg" || runtime_candidates java | head -1 ;;
        go)     [ -n "$go_pkg" ] && echo "$go_pkg" || runtime_candidates go | head -1 ;;
        rust)   [ -n "$rust_pkg" ] && echo "$rust_pkg" || runtime_candidates rust | head -1 ;;
        php)    [ -n "$php_pkg" ] && echo "$php_pkg" || runtime_candidates php | head -1 ;;
    esac
}

# ── 运行时已选主包版本号（显示用）──
ver_for() {
    pkg_versions "$(pkg_for "$1")" | head -1 | sed 's/-r[0-9]*$//'
}

# ── 运行时安装包列表（所选候选主包 + 附属包）──
runtime_install() {
    rest=$(runtime_pkgs "$1" | awk '{$1=""; sub(/^ /, ""); print}')
    if [ -n "$rest" ]; then
        echo "$(pkg_for "$1") $rest"
    else
        pkg_for "$1"
    fi
}

# ── 安装清单一行：名称 → 包列表（v版本，探测得到时；显示去掉 apk 的 -rN 后缀）──
plan_line() {
    line="    · $1 → $2"
    if [ -n "$3" ]; then
        disp=$(echo "$3" | sed 's/-r[0-9]*$//')
        line="$line（v$disp）"
    fi
    echo "$line"
}

# ── y/N 询问：$1 提示语，$2 默认值（y/n），$3 版本号（可选）；回答 y 返回 0，n 返回 1 ──
ask_yn() {
    def="$2"
    while :; do
        if [ "$def" = "y" ]; then
            printf "%s（默认安装）[Y/n]: " "$1"
        else
            printf "%s（默认跳过）[y/N]: " "$1"
        fi
        read ans
        [ -z "$ans" ] && ans="$def"
        case "$ans" in
            y|Y) return 0 ;;
            n|N) return 1 ;;
            *) echo "  请输入 y 或 n" ;;
        esac
    done
}

# ── 列出安装清单并确认：$1 为空格分隔的运行时列表（可为空）；输入 y 返回 0，其余返回 1 ──
show_plan() {
    echo ""
    echo "══════════════ 安装清单 ══════════════"
    echo "  基础工具: $BASE_PKGS"
    runtimes="$1"
    if [ -n "$runtimes" ]; then
        echo "  运行时:"
        for r in $runtimes; do
            case "$r" in
                node)   plan_line "Node.js" "$(runtime_install node)" "$(ver_for node)" ;;
                python) plan_line "Python 3" "$(runtime_install python)" "$(ver_for python)" ;;
                java)   plan_line "Java" "$(runtime_install java)" "$(ver_for java)" ;;
                go)     plan_line "Go" "$(runtime_install go)" "$(ver_for go)" ;;
                rust)   plan_line "Rust" "$(runtime_install rust)" "$(ver_for rust)" ;;
                php)    plan_line "PHP" "$(runtime_install php)" "$(ver_for php)" ;;
            esac
        done
    else
        echo "  运行时: （未选择，仅装基础工具）"
    fi
    echo "══════════════════════════════════════"
    printf "  输入 y 开始安装，其它键返回菜单: "
    read ans
    [ "$ans" = "y" ] || [ "$ans" = "Y" ]
}

# ── 换源交互：列出镜像候选供单选（1=自动探测，中间=指定镜像，最后=不换源），选定后执行换源 ──
ask_mirror() {
    echo ""
    echo "选择镜像源："
    echo "    1) 自动探测（推荐）"
    n=2
    for m in $MIRRORS; do
        echo "    $n) $m"
        n=$((n + 1))
    done
    echo "    0) 不换源（保持默认）"
    printf "输入序号（回车默认 1）: "
    read ans
    case "$ans" in
        ""|*[!0-9]*|1)
            pick_mirror
            ;;
        0)
            echo "保持默认源，不换源。"
            return 0
            ;;
        *)
            MIRROR=$(echo "$MIRRORS" | awk -v i="$((ans - 1))" '{print $i}')
            [ -z "$MIRROR" ] && { echo "序号无效，使用自动探测。"; pick_mirror; }
            ;;
    esac
    setup_mirror
}

# ── 自动安装：基础工具 + Node.js + Python，清单确认后执行 ──
# 返回：0=完成 1=安装失败 2=用户取消（未确认清单）
install_packages() {
    show_plan "node python" || return 2
    echo ""
    ask_mirror
    echo ""
    echo "开始安装基础依赖（可能需要几分钟，请耐心等待）..."
    pkg_add $BASE_PKGS $(runtime_pkgs node) $(runtime_pkgs python) || return 1
    return 0
}

# ── 自定义安装：逐项勾选运行时，基础工具始终安装，清单确认后执行 ──
# 返回：0=完成 1=安装失败 2=用户取消（未确认清单）
install_custom() {
    echo ""
    echo "自定义安装将同时安装基础工具（$BASE_PKGS）与所选运行时。"
    echo ""
    ask_mirror
    echo "正在更新软件包列表..."
    pkg_update || { echo "${C_RED}更新软件包列表失败，请检查网络后重试。${C_RESET}"; return 1; }
    echo ""
    echo "请选择需要安装的依赖"
    runtimes=""
    node_pkg=""; python_pkg=""; java_pkg=""; go_pkg=""; rust_pkg=""; php_pkg=""
    if ask_yn "是否安装 Node.js？" y; then
        echo "  Node.js 可用主版本："
        pick_candidate node
        node_pkg=$PICKED_PKG
        runtimes="$runtimes node"
    fi
    if ask_yn "是否安装 Python 3？" y; then
        echo "  Python 3 可用主版本："
        pick_candidate python
        python_pkg=$PICKED_PKG
        runtimes="$runtimes python"
    fi
    if ask_yn "是否安装 Java？" n; then
        echo "  Java 可用主版本："
        pick_candidate java
        java_pkg=$PICKED_PKG
        runtimes="$runtimes java"
    fi
    if ask_yn "是否安装 Go？" n; then
        echo "  Go 可用主版本："
        pick_candidate go
        go_pkg=$PICKED_PKG
        runtimes="$runtimes go"
    fi
    if ask_yn "是否安装 Rust？" n; then
        echo "  Rust 可用主版本："
        pick_candidate rust
        rust_pkg=$PICKED_PKG
        runtimes="$runtimes rust"
    fi
    if ask_yn "是否安装 PHP？" n; then
        echo "  PHP 可用主版本："
        pick_candidate php
        php_pkg=$PICKED_PKG
        runtimes="$runtimes php"
    fi
    if [ -z "$runtimes" ]; then
        echo "未选择任何运行时，将仅安装基础工具（$BASE_PKGS）。"
    fi
    show_plan "$runtimes" || return 2
    echo ""
    echo "开始安装所选依赖（可能需要几分钟，请耐心等待）..."
    pkgs="$BASE_PKGS"
    for r in $runtimes; do
        pkgs="$pkgs $(runtime_install "$r")"
    done
    pkg_add $pkgs || return 1
    return 0
}

# ── 探测单个 URL 的 http 状态码（curl 优先，wget 兑底，无法探测返回 000）──
http_code() {
    if command -v curl >/dev/null 2>&1; then
        curl -s -o /dev/null --max-time 8 -w '%{http_code}' "$1" 2>/dev/null || echo "000"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -T 8 -O /dev/null "$1" 2>/dev/null && echo "200" || echo "000"
    else
        echo "000"
    fi
}

# ── 探测镜像站 $1 对当前容器是否可用：按包管理器选真实内容路径；
#    apk 要求 http 直连 2xx（3xx 会跳 https，minirootfs 无 CA 证书不可用）；
#    apt 接受 2xx/3xx（apt 可跟随 https 重定向）；dnf/pacman 有响应即可用 ──
probe_mirror() {
    m="$1"
    case "$PMGR" in
        apk)
            code=$(http_code "http://$m/alpine/v3.21/main/x86_64/APKINDEX.tar.gz")
            case "$code" in 2[0-9][0-9]) return 0 ;; *) return 1 ;; esac
            ;;
        apt)
            . /etc/os-release 2>/dev/null
            if [ "$ID" = "ubuntu" ]; then
                url="http://$m/ubuntu/dists/jammy/Release"
            else
                url="http://$m/debian/dists/stable/Release"
            fi
            code=$(http_code "$url")
            case "$code" in 2[0-9][0-9]|3[0-9][0-9]) return 0 ;; *) return 1 ;; esac
            ;;
        dnf|yum|pacman)
            code=$(http_code "https://$m/archlinux/core/os/x86_64/core.db")
            case "$code" in 2[0-9][0-9]|3[0-9][0-9]|4[0-9][0-9]) return 0 ;; *) return 1 ;; esac
            ;;
    esac
}

# ── 从候选里选当前可用镜像（全部不可用则兑底第一个）──
pick_mirror() {
    for m in $MIRRORS; do
        if probe_mirror "$m"; then
            MIRROR="$m"
            return 0
        fi
    done
    MIRROR=$(echo "$MIRRORS" | awk '{print $1}')
}

# ── 镜像源：按包管理器换国内镜像源（多候选自动探测），失败自动恢复原配置（不阻塞后续安装）──
setup_mirror() {
    [ -z "$MIRROR" ] && pick_mirror
    if command -v apk >/dev/null 2>&1; then
        setup_apk_mirror
    elif command -v apt-get >/dev/null 2>&1; then
        setup_apt_mirror
    elif command -v dnf >/dev/null 2>&1; then
        setup_dnf_mirror
    elif command -v yum >/dev/null 2>&1; then
        echo "yum（RHEL/CentOS）暂不支持自动换源，使用默认源" >&2
        return 0
    elif command -v pacman >/dev/null 2>&1; then
        setup_pacman_mirror
    fi
}

setup_apk_mirror() {
    # 用 http 而非 https：minirootfs 无 ca-certificates，apk 对索引与包做独立签名校验，http 不影响完整性。
    mkdir -p /etc/apk
    # Alpine 大版本分支从镜像自身动态读取，兼容用户导入的不同版本 Alpine 镜像：
    # 1) 优先从现有 repositories 提取（官方源 / 已换过的源都含 `alpine/<分支>/`，edge 也能拿到）；
    # 2) 读不到再回退到 /etc/os-release 的 VERSION_ID（如 3.21.3 → v3.21）；
    # 3) 最后兜底 v3.21（与内置 Alpine 一致）。
    branch=""
    if [ -f /etc/apk/repositories ]; then
        branch=$(sed -n 's#.*alpine/\([^/]*\)/.*#\1#p' /etc/apk/repositories 2>/dev/null | head -1)
    fi
    if [ -z "$branch" ] && [ -f /etc/os-release ]; then
        . /etc/os-release 2>/dev/null
        if [ "$ID" = "alpine" ] && [ -n "$VERSION_ID" ]; then
            branch="v$(echo "$VERSION_ID" | cut -d. -f1-2)"
        fi
    fi
    [ -z "$branch" ] && branch="v3.21"
    cat > /etc/apk/repositories <<EOF
http://$MIRROR/alpine/$branch/main
http://$MIRROR/alpine/$branch/community
EOF
}

setup_apt_mirror() {
    . /etc/os-release 2>/dev/null
    codename="${VERSION_CODENAME:-}"
    [ -z "$codename" ] && { echo "无法识别 apt 版本代号，跳过换源" >&2; return 1; }
    # ARM 架构（手机常见）的 Ubuntu 包在 ubuntu-ports 仓库（对应 ports.ubuntu.com）；x86 走 ubuntu 主仓库。
    # Debian 主仓库本身含 arm64/armhf，无需 ports。
    arch=$(dpkg --print-architecture 2>/dev/null || uname -m)
    case "$ID" in
        ubuntu)
            case "$arch" in
                amd64|i386) repo="ubuntu" ;;
                *) repo="ubuntu-ports" ;;
            esac
            uri="http://$MIRROR/$repo/"
            suites="$codename $codename-updates $codename-backports $codename-security"
            components="main restricted universe multiverse"
            ;;
        debian)
            uri="http://$MIRROR/debian/"
            suites="$codename $codename-updates $codename-backports"
            components="main contrib non-free non-free-firmware"
            security_uri="http://$MIRROR/debian-security/"
            ;;
        *) echo "不支持的 apt 发行版：$ID，跳过换源" >&2; return 1 ;;
    esac
    # keyring 存在才写 Signed-By；缺 keyring 时 apt 回退 trusted.gpg.d
    signed_by=""
    [ -f /usr/share/keyrings/ubuntu-archive-keyring.gpg ] && signed_by="Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg"
    [ -f /usr/share/keyrings/debian-archive-keyring.gpg ] && signed_by="Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg"
    # 备份并清空旧源（sources.list 与 sources.list.d 两种格式一并处理）
    backup_dir=/etc/apt/mirror-backup
    rm -rf "$backup_dir" && mkdir -p "$backup_dir"
    [ -f /etc/apt/sources.list ] && cp /etc/apt/sources.list "$backup_dir/" 2>/dev/null
    [ -d /etc/apt/sources.list.d ] && cp -a /etc/apt/sources.list.d "$backup_dir/" 2>/dev/null
    rm -f /etc/apt/sources.list
    rm -rf /etc/apt/sources.list.d
    mkdir -p /etc/apt/sources.list.d
    {
        echo "Types: deb"
        echo "URIs: $uri"
        echo "Suites: $suites"
        echo "Components: $components"
        [ -n "$signed_by" ] && echo "$signed_by"
    } > /etc/apt/sources.list.d/aicode-mirror.sources
    if [ -n "$security_uri" ]; then
        {
            echo "Types: deb"
            echo "URIs: $security_uri"
            echo "Suites: $codename-security"
            echo "Components: $components"
            [ -n "$signed_by" ] && echo "$signed_by"
        } > /etc/apt/sources.list.d/aicode-mirror-security.sources
    fi
    # 验证：update 失败输出原因并恢复原配置
    if ! apt-get update -y; then
        echo "换源后 apt update 失败，已恢复原源配置" >&2
        rm -rf /etc/apt/sources.list.d
        [ -f "$backup_dir/sources.list" ] && cp "$backup_dir/sources.list" /etc/apt/sources.list
        [ -d "$backup_dir/sources.list.d" ] && mv "$backup_dir/sources.list.d" /etc/apt/sources.list.d
        rm -rf "$backup_dir"
        return 1
    fi
    rm -rf "$backup_dir"
    return 0
}

setup_dnf_mirror() {
    # Fedora：备份原 repo，写华为云 baseurl（国内 metalink 不可用，直接覆写 repo 文件）
    backup_dir=/etc/yum.repos.d/mirror-backup
    rm -rf "$backup_dir" && mkdir -p "$backup_dir"
    cp -a /etc/yum.repos.d/ "$backup_dir/" 2>/dev/null
    rm -f /etc/yum.repos.d/*.repo
    cat > /etc/yum.repos.d/fedora.repo <<EOF
[fedora]
name=Fedora \$releasever - \$basearch
baseurl=https://$MIRROR/fedora/releases/\$releasever/Everything/\$basearch/os/
enabled=1
gpgcheck=1
EOF
    cat > /etc/yum.repos.d/fedora-updates.repo <<EOF
[updates]
name=Fedora \$releasever - \$basearch - Updates
baseurl=https://$MIRROR/fedora/updates/\$releasever/\$basearch/
enabled=1
gpgcheck=1
EOF
    if ! dnf makecache -y >/dev/null 2>&1; then
        echo "换源后 dnf makecache 失败，已恢复原 repo 配置" >&2
        rm -f /etc/yum.repos.d/fedora.repo /etc/yum.repos.d/fedora-updates.repo
        cp -a "$backup_dir/." /etc/yum.repos.d/ 2>/dev/null
        rm -rf "$backup_dir"
        return 1
    fi
    rm -rf "$backup_dir"
    return 0
}

setup_pacman_mirror() {
    backup=/etc/pacman.d/mirrorlist
    [ -f "$backup" ] && cp "$backup" "$backup.backup"
    cat > "$backup" <<EOF
Server = https://$MIRROR/archlinux/\$repo/os/\$arch
EOF
    if ! pacman -Sy --noconfirm >/dev/null 2>&1; then
        echo "换源后 pacman -Sy 失败，已恢复原 mirrorlist" >&2
        [ -f "$backup.backup" ] && mv "$backup.backup" "$backup"
        return 1
    fi
    rm -f "$backup.backup"
    return 0
}

git_config() {
    # git 未安装（手动安装/退出路径）时跳过凭据配置
    command -v git >/dev/null 2>&1 || return 0
    # 凭据注入最小化：只对工作区根目录（$HOME/workspace/）下的仓库生效——
    # credential.helper 写进 gitconfig.credential，经 includeIf 按目录条件加载，
    # 容器内其它目录的 git 仓库不会被注入（store 命中已有凭据秒过 + aicode 自定义 helper 未命中时经文件 IPC 弹窗回填）。
    # 用 $HOME 而非写死 /root：容器 home 由环境决定，保持一致（App 侧 GIT_CONFIG_GLOBAL 同指向 $HOME/.aicode/.gitconfig）。
    AICODE_DIR="$HOME/.aicode"
    mkdir -p "$AICODE_DIR"
    cat > "$AICODE_DIR/gitconfig.credential" <<EOF
[credential]
    helper = store --file=$AICODE_DIR/git-credentials
    helper = $AICODE_DIR/git-credential-aicode
EOF
    # 先清旧的 includeIf 段（幂等），再写限定工作区根的 includeIf。
    # 段名必须带 subsection：真实段是 [includeIf "gitdir:…"]，只写 `--remove-section includeIf`
    # 匹配不到它、报 fatal 后被 2>/dev/null 吞掉，于是每跑一次初始化就 --add 累加一条重复 path。
    git config --global --remove-section "includeIf.gitdir:$HOME/workspace/" 2>/dev/null || true
    git config --global --add includeIf."gitdir:$HOME/workspace/".path "$AICODE_DIR/gitconfig.credential"
    # 清掉全局顶层 [credential] 段：helper 只应经上面的 includeIf 按目录加载，
    # 写在顶层会对容器内所有仓库生效，破坏「只注入工作区根下」的最小化设计。
    git config --global --remove-section credential 2>/dev/null || true
}

# ── 交互初始化菜单（所有容器统一，在终端 PTY 上运行，用户自主选择安装方式）──
C_BOLD=$(printf '\033[1m')
C_CYAN=$(printf '\033[36m')
C_YELLOW=$(printf '\033[33m')
C_GREEN=$(printf '\033[32m')
C_RED=$(printf '\033[31m')
C_DIM=$(printf '\033[2m')
C_RESET=$(printf '\033[0m')

detect_pmgr
plog "显示初始化菜单：PMGR=${PMGR:-未识别}"

while :; do
    echo ""
    cat <<EOF
${C_CYAN}    _    ___ ____ ___  ____  _____ ${C_RESET}
${C_CYAN}   / \  |_ _/ ___/ _ \|  _ \| ____|${C_RESET}
${C_CYAN}  / _ \  | | |  | | | | | | |  _|  ${C_RESET}
${C_CYAN} / ___ \ | | |__| |_| | |_| | |___ ${C_RESET}
${C_CYAN}/_/   \_\___\____\___/|____/|_____|${C_RESET}
${C_YELLOW}══════════════════════════════════════════════${C_RESET}
${C_BOLD}  容器初始化 · 选择安装方式${C_RESET}
${C_YELLOW}══════════════════════════════════════════════${C_RESET}
  ${C_GREEN}1. 自动安装依赖${C_RESET}（推荐）
  ${C_GREEN}2. 自定义安装${C_RESET}
  ${C_BOLD}3. 手动安装${C_RESET}（不再提示）
  ${C_DIM}4. 退出${C_RESET}
${C_YELLOW}══════════════════════════════════════════════${C_RESET}
EOF
    printf "请选择: "
    if ! read choice; then
        echo ""
        echo "输入中断，退出初始化"
        break
    fi
    case "$choice" in
        1)
            plog "用户选择 1（自动安装）"
            echo ""
            echo "${C_YELLOW}安装提示${C_RESET}："
            echo "  · 安装耗时较长，建议开启「后台保活」并将 App 保持在前台"
            echo "  · 安装过程中请勿切走或锁屏，否则进程可能被系统杀死导致安装中断"
            echo ""
            install_packages
            rc=$?
            case "$rc" in
                0)
                    echo "$PROVISION_VERSION" > "$MARKER"
                    plog "自动安装完成，写入标记 $PROVISION_VERSION"
                    echo ""
                    echo "${C_GREEN}基础依赖安装完成，开始使用吧！${C_RESET}"
                    break
                    ;;
                2)
                    printf '\033[2J\033[H'
                    echo "已取消安装，返回菜单。"
                    ;;
                *)
                    echo ""
                    echo "${C_RED}安装失败。可在 AI 对话中让 AI 读取本终端内容进行诊断修复，或重新进入终端重试。${C_RESET}"
                    break
                    ;;
            esac
            ;;
        2)
            plog "用户选择 2（自定义安装）"
            install_custom
            rc=$?
            case "$rc" in
                0)
                    echo "$PROVISION_VERSION" > "$MARKER"
                    plog "自定义安装完成，写入标记 $PROVISION_VERSION"
                    echo ""
                    echo "${C_GREEN}自定义安装完成，开始使用吧！${C_RESET}"
                    break
                    ;;
                2)
                    printf '\033[2J\033[H'
                    echo "已取消安装，返回菜单。"
                    ;;
                *)
                    echo ""
                    echo "${C_RED}安装失败。可在 AI 对话中让 AI 读取本终端内容进行诊断修复，或重新进入终端重试。${C_RESET}"
                    break
                    ;;
            esac
            ;;
        3)
            plog "用户选择 3（手动安装）"
            echo ""
            echo "${C_YELLOW}手动安装提示${C_RESET}："
            echo "  · ripgrep（rg）是必装工具，缺失会影响使用体验"
            echo "  · git 是可视化版本管理操作的基础"
            echo "  · MCP 工具依赖 python3 / nodejs 等运行时"
            echo ""
            printf "确认选择手动安装，不再提示吗？[y/N]: "
            read manual_confirm
            if [ "$manual_confirm" = "y" ] || [ "$manual_confirm" = "Y" ]; then
                echo "$PROVISION_SKIPPED" > "$MARKER"
                plog "确认手动安装，写入跳过标记"
                echo "已选择手动安装，之后进入终端不再提示。"
                break
            else
                printf '\033[2J\033[H'
                echo "已取消，返回菜单。"
            fi
            ;;
        4)
            plog "用户选择 4（退出，下次仍提示）"
            echo "已退出，下次进入终端仍会提示。"
            break
            ;;
        *)
            printf '\033[2J\033[H'
            echo "${C_RED}无效输入，请重新选择。${C_RESET}"
            ;;
    esac
done
plog "初始化菜单结束"
git_config
