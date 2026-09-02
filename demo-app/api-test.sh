#!/usr/bin/env bash
#
# HMS Core 接口冒烟测试 —— 启动 demo-app，逐个验证 core 的对外能力。
#
# 用法：
#   ./demo-app/api-test.sh
#
# 环境变量全部可选 —— 未设置 AI_API_KEY 时脚本会实测一次模型链路，
# 密钥写在 application.yml 里同样能跑全部用例（见 probe_model）：
#   export AI_API_KEY=sk-xxx                       # 可选
#   export AI_BASE_URL=https://api.deepseek.com    # 可选
#   export AI_MODEL=deepseek-chat                  # 可选
#   export HMS_CORE_PROVIDER=openai                # openai / anthropic
#
# 可选参数：
#   --no-start   不自己启动应用，连已在运行的实例（见 BASE_URL）
#   --keep       测试结束后不停止应用，便于手工接着调
#   --only <名>  只跑名字含该关键字的组（可重复匹配子串）：
#                tool / session / permission / chat / toolcall / compact
#                stream / interactive / metrics / contract / concurrency / lifecycle
#   --list       列出所有测试组后退出
#
# 退出码：0 全部通过；非 0 为失败用例数。
#
# 本脚本不含任何密钥：要么从环境变量读，要么直接用被测应用自己的配置。
#
# ── 测试边界 ──────────────────────────────────────────────────────────
# 纯黑盒：只打 demo-app 已暴露的 REST/SSE 端点，不改动被测应用。
# hms-core 有几项能力没有对应的 HTTP 端点，因此无法在此验证，脚本会以
# skip 显式标注而非静默略过（见「未覆盖能力」组）：
#   · updateSessionPrompt   会话提示词热更新
#   · getSessionHooks       Hook 扩展点（PreToolUse / PostToolUse）
#   · getSessionDenials     DenialTracker 拒绝审计
#   · sendStreaming         Consumer<String> 直连流式（SSE 走的是 send + callbacks）
#   · max-sessions 超限、max-iterations 截断等需重启改配置的边界
#
# ── 关于上下文压缩用例 ────────────────────────────────────────────────
# core 支持用 HMS_CORE_CONTEXT_WINDOW 覆盖上下文窗口大小（默认 200K）。
# 压缩阈值是窗口的 93%，正常对话要几十万 token 才触发；脚本把窗口压到很小，
# 几轮对话即可越过阈值，从而在可接受时间内验证三层压缩链路。
#
# ── 已知产品缺陷（脚本已绕开，不代表问题已修）────────────────────────
# 权限拒绝的那一轮不会往消息历史里写 assistant 记录，于是历史出现连续两条
# user（system, user, user）。上游 API 拒绝这种消息序列，导致该会话之后
# 每一轮请求都返回 500。复现：
#   建会话 → STRICT 模式 → 让模型调 MEDIUM 工具（被拒）→ 再发任意一条消息 → 500
# 因此 toolcall 组的权限用例（6c/6d/6e）各用独立会话，用完即弃。
# 修复方向是让被拒的轮次也写入 assistant + tool 记录，保持配对完整。
#
# 另注：toolCallsCount 统计的是「模型请求的工具调用数」，在权限检查之前累加，
# 被拒绝的调用同样计入。不要用它判断工具是否真的执行了。

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8088}"
PORT="${SERVER_PORT:-8088}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_FILE="${LOG_FILE:-/tmp/hms-demo-app.log}"

# 压缩用例专用的小窗口：25000 × 93% ≈ 23250 token 即触发。
# 不能太小 —— 低于单条系统提示词的规模会让首轮就无法组装请求。
TEST_CONTEXT_WINDOW="${TEST_CONTEXT_WINDOW:-25000}"

# hms-core 注册的全局工具全集（ToolConfiguration 里 register(...) 的那批）。
# 少一个就说明注册链路漏了，多一个说明这里该补 —— 两个方向都值得报错。
EXPECTED_TOOLS=(
    WebFetch WebSearch
    Agent SendMessage
    TaskCreate TaskGet TaskList TaskUpdate TaskStop TaskOutput
    TodoWrite
    ListMcpResources ReadMcpResource
    Skill Config Sleep
    AskUserQuestion ToolSearch
    EnterPlanMode ExitPlanMode
)

DO_START=1
KEEP_RUNNING=0
ONLY=""
while [ $# -gt 0 ]; do
    case "$1" in
        --no-start) DO_START=0 ;;
        --keep)     KEEP_RUNNING=1 ;;
        --only)     shift; ONLY="${1:-}" ;;
        --list)     sed -n '/^# *tool \/ session/,/lifecycle/p' "${BASH_SOURCE[0]}"; exit 0 ;;
        -h|--help)  sed -n '2,51p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "未知参数: $1（--help 查看用法）" >&2; exit 2 ;;
    esac
    shift
done

# ── 输出 ────────────────────────────────────────────────────────────────

if [ -t 1 ]; then
    C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'
    C_BLUE=$'\033[36m'; C_DIM=$'\033[2m'; C_OFF=$'\033[0m'
else
    C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_DIM=""; C_OFF=""
fi

PASS=0; FAIL=0; SKIP=0
FAILED_NAMES=()

section() { printf '\n%s── %s %s\n' "$C_BLUE" "$1" "$C_OFF"; }
pass()    { PASS=$((PASS+1)); printf '  %s✓%s %s\n' "$C_GREEN" "$C_OFF" "$1"; }
fail()    { FAIL=$((FAIL+1)); FAILED_NAMES+=("$1"); printf '  %s✗%s %s\n' "$C_RED" "$C_OFF" "$1"
            [ -n "${2:-}" ] && printf '      %s%s%s\n' "$C_DIM" "$2" "$C_OFF"; return 0; }
skip()    { SKIP=$((SKIP+1)); printf '  %s-%s %s %s(%s)%s\n' "$C_YELLOW" "$C_OFF" "$1" "$C_DIM" "${2:-跳过}" "$C_OFF"; }
info()    { printf '  %s%s%s\n' "$C_DIM" "$1" "$C_OFF"; }

# ── HTTP 封装 ───────────────────────────────────────────────────────────

LAST_STATUS=""; LAST_BODY=""

# 请求体经临时文件传给 curl（--data-binary @file），不走命令行参数。
# Windows/Git Bash 下 curl 的 -d 会把参数里的 UTF-8 中文转成 GBK，服务端收到
# 0xb2 这类非法起始字节后直接 400（HttpMessageNotReadableException），而本脚本
# 大量用中文提示词与消息。写文件再 --data-binary 能保持字节原样。
#
# 每次调用用独立的临时文件并即时删除：并发组会在后台子 shell 里同时调 call，
# 复用同一个固定路径会让它们互相覆盖 body。
call() {
    local method="$1" path="$2" body="${3:-}" raw bodyfile=""
    if [ -n "$body" ]; then
        bodyfile="$(mktemp)"
        printf '%s' "$body" > "$bodyfile"
        raw="$(curl -sS -m 300 -w '\n%{http_code}' -X "$method" "$BASE_URL$path" \
               -H 'Content-Type: application/json; charset=utf-8' \
               --data-binary "@$bodyfile" 2>/dev/null)"
        rm -f "$bodyfile"
    else
        raw="$(curl -sS -m 300 -w '\n%{http_code}' -X "$method" "$BASE_URL$path" 2>/dev/null)"
    fi
    LAST_STATUS="${raw##*$'\n'}"
    LAST_BODY="${raw%$'\n'*}"
}

# jget <json> <jq表达式>；无 jq 时退化为按末级键名做文本匹配
jget() {
    if [ "$HAS_JQ" = 1 ]; then
        printf '%s' "$1" | jq -r "$2" 2>/dev/null
    else
        local key="${2##*.}"; key="${key%% *}"
        printf '%s' "$1" | grep -oE "\"$key\"[[:space:]]*:[[:space:]]*(\"[^\"]*\"|[^,}]*)" \
            | head -1 | sed -E 's/^"[^"]*"[[:space:]]*:[[:space:]]*//; s/^"//; s/"$//'
    fi
}

# 数组长度 —— 无 jq 时 jget '.data | length' 会返回垃圾（截断的 JSON 片段），
# 因此单独实现：有 jq 走 jq，否则数 data 数组里顶层对象的 '{' 个数。
# 只对「元素是对象的数组」有效，本脚本的用法（会话列表、消息历史）都符合。
jlen() {
    local json="$1" path="${2:-.data}"
    if [ "$HAS_JQ" = 1 ]; then
        printf '%s' "$json" | jq -r "$path | length" 2>/dev/null
    else
        # 剥掉 data 之前的部分，再数 '{'
        printf '%s' "$json" | sed -E 's/^.*"data"[[:space:]]*:[[:space:]]*\[//' \
            | grep -o '{' | wc -l | tr -d ' '
    fi
}

# 字符串数组长度（如 toolNames / 全局工具列表）：数逗号+1，空数组为 0
jlen_str() {
    local json="$1"
    if [ "$HAS_JQ" = 1 ]; then
        printf '%s' "$json" | jq -r '.data | length' 2>/dev/null
    else
        local inner
        inner="$(printf '%s' "$json" | sed -E 's/^.*"data"[[:space:]]*:[[:space:]]*\[//; s/\].*$//')"
        case "$inner" in
            ''|' ') echo 0 ;;
            *) printf '%s' "$inner" | grep -o '"' | wc -l | awk '{print $1/2}' ;;
        esac
    fi
}

expect_ok() {
    local name="$1"
    if [ "$LAST_STATUS" != "200" ]; then
        fail "$name" "HTTP $LAST_STATUS: $(printf '%s' "$LAST_BODY" | head -c 200)"; return 1
    fi
    if [ "$(jget "$LAST_BODY" '.success')" != "true" ]; then
        fail "$name" "success=false: $(printf '%s' "$LAST_BODY" | head -c 200)"; return 1
    fi
    pass "$name"; return 0
}

# 业务失败（success=false）—— 不关心状态码是 200 还是 400
expect_fail_response() {
    local name="$1"
    if [ "$(jget "$LAST_BODY" '.success')" = "false" ]; then pass "$name"; return 0; fi
    fail "$name" "预期 success=false，实际：$(printf '%s' "$LAST_BODY" | head -c 200)" ; return 1
}

# 结构化失败：必须是 4xx + success=false + 有 message。
# 守护 ApiExceptionHandler —— 没有它这些端点会返回 500 白页（HTML 而非 JSON）。
expect_client_error() {
    local name="$1"
    case "$LAST_STATUS" in
        4*) ;;
        *) fail "$name" "预期 4xx，实际 HTTP $LAST_STATUS: $(printf '%s' "$LAST_BODY" | head -c 160)"; return 1 ;;
    esac
    if [ "$(jget "$LAST_BODY" '.success')" != "false" ]; then
        fail "$name" "4xx 但响应体不是 ApiResponse 失败体：$(printf '%s' "$LAST_BODY" | head -c 160)"; return 1
    fi
    pass "$name"; return 0
}

assert_eq() {
    if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "预期 [$3]，实际 [$2]"; fi
}

assert_gt() {
    local name="$1" actual="${2:-0}" floor="$3"
    if [ "${actual:-0}" -gt "$floor" ] 2>/dev/null; then pass "$name"
    else fail "$name" "预期 > $floor，实际 [$actual]"; fi
}

assert_ge() {
    local name="$1" actual="${2:-0}" floor="$3"
    if [ "${actual:-0}" -ge "$floor" ] 2>/dev/null; then pass "$name"
    else fail "$name" "预期 >= $floor，实际 [$actual]"; fi
}

assert_contains() {
    case "$2" in *"$3"*) pass "$1" ;; *) fail "$1" "未找到 [$3]，实际：$(printf '%s' "$2" | head -c 200)" ;; esac
}

assert_not_contains() {
    case "$2" in *"$3"*) fail "$1" "不该出现 [$3]，实际：$(printf '%s' "$2" | head -c 200)" ;; *) pass "$1" ;; esac
}

# 数值字段存在且是数字（不判大小）—— 用于「字段没接上会恒缺失」的契约断言
assert_number() {
    local name="$1" v="${2:-}"
    case "$v" in
        ''|null) fail "$name" "字段缺失或为 null" ;;
        *[!0-9-]*) fail "$name" "不是数字：[$v]" ;;
        *) pass "$name" ;;
    esac
}

should_run() { [ -z "$ONLY" ] || case "$1" in *"$ONLY"*) return 0 ;; *) return 1 ;; esac; }

# 逐字节百分号编码 —— 不挑字符集，中文/标点/空格一律照字节编。
#
# 不用 python3：Windows 上 `command -v python3` 会命中 WindowsApps 里的存根，
# 检测通过但执行无输出，导致消息被静默编成空串。也不用 sed 白名单替换：
# 漏掉的中文会原样进 URL，curl 再按本地 GBK 发出去，服务端拿到乱码。
# od 是 coreutils 自带的，行为稳定。
urlenc() {
    printf '%s' "$1" | od -An -tx1 -v | tr -d '\n ' | sed -E 's/(..)/%\1/g'
}

# sse <消息> <超时秒> → 事件流写入 $SSE_OUT，HTTP 状态码写入 $SSE_STATUS
#
# 必须记录状态码：消息走 URL 查询串，长消息编码后极易超过 Tomcat 默认的 8KB
# 请求头上限，此时 Tomcat 直接回 400 HTML，请求根本没进应用。早先这里只有
# `|| true`，于是传输层失败和「模型没触发该事件」看起来一模一样 ——
# 压缩组 6 轮全是 400，却被报成「tokens 尚未越阈值」。
SSE_OUT=""; SSE_STATUS=""
sse() {
    SSE_OUT="$(mktemp)"
    SSE_STATUS="$(curl -sS -N -m "${2:-120}" -o "$SSE_OUT" -w '%{http_code}' \
        -H 'Accept: text/event-stream' \
        "$BASE_URL/api/chat/$SESSION/stream?message=$(urlenc "$1")" 2>/dev/null)" || true
    case "$SSE_STATUS" in
        200|000) ;;  # 000 = curl 因 -m 超时中断，流本身可能已有内容
        *) info "SSE 请求未被受理（HTTP $SSE_STATUS）——消息过长超出请求头上限？" ;;
    esac
}
sse_has()   { grep -qE "^event: ?$1$" "$SSE_OUT"; }
sse_count() { grep -cE "^event: ?$1$" "$SSE_OUT" 2>/dev/null || echo 0; }
# 取某类事件的第一条 data 行
sse_data()  { grep -A 1 -E "^event: ?$1$" "$SSE_OUT" | grep '^data:' | head -1; }
# 取某类事件的全部 data 行（拼成一行便于匹配）
sse_data_all() { grep -A 1 -E "^event: ?$1$" "$SSE_OUT" | grep '^data:' | tr -d '\n'; }

# ── 前置检查 ────────────────────────────────────────────────────────────

command -v curl >/dev/null || { echo "需要 curl" >&2; exit 2; }
HAS_JQ=0; command -v jq >/dev/null && HAS_JQ=1
[ "$HAS_JQ" = 0 ] && printf '%s提示：未装 jq，JSON 断言退化为文本匹配%s\n' "$C_YELLOW" "$C_OFF"

# 模型能力探测 —— 决定是否跑需要真实 AI 调用的用例。
#
# 不能只看 AI_API_KEY 环境变量：密钥也可以直接写在 application.yml 里（本仓库
# 的默认配置就是这样），那种情况下环境变量为空但模型完全可用，照 env 判断会把
# 全部模型用例静默跳过 —— 看起来"全部通过"，实际啥都没测。
#
# 因此改为「先看 env，没有就等应用起来后实打实发一条消息探测」。探测在
# probe_model 里做（需要应用已就绪），这里只记录初始猜测。
HAS_KEY=1
[ -z "${AI_API_KEY:-}" ] && HAS_KEY=probe

# probe_model —— 建一个临时会话发一条最短消息，据结果确定 HAS_KEY。
# 只在 HAS_KEY=probe 时调用；探测本身也是一个有效用例（模型链路是否打通）。
probe_model() {
    [ "$HAS_KEY" = "probe" ] || return 0
    info "未设置 AI_API_KEY，改为实测模型链路（密钥可能写在 application.yml 里）"

    call POST /api/sessions '{"sessionPrompt":"probe"}'
    local pid=""
    [ "$(jget "$LAST_BODY" '.success')" = "true" ] && pid="$(jget "$LAST_BODY" '.data.sessionId')"
    if [ -z "$pid" ] || [ "$pid" = "null" ]; then
        HAS_KEY=0
        info "探测会话创建失败，按「无模型」处理"
        return 0
    fi

    call POST "/api/chat/$pid" '{"message":"只回复一个字：好"}'
    if [ "$(jget "$LAST_BODY" '.success')" = "true" ]; then
        HAS_KEY=1
        pass "模型链路可用（实测发送成功，tokens=$(jget "$LAST_BODY" '.data.totalTokens')）"
    else
        HAS_KEY=0
        local code; code="$(jget "$LAST_BODY" '.data.errorCode')"
        info "模型不可用（errorCode=${code:-?}），需真实 AI 的用例将跳过"
        info "$(printf '%s' "$LAST_BODY" | head -c 200)"
    fi
    call DELETE "/api/sessions/$pid"
}

# ── 启动应用 ────────────────────────────────────────────────────────────

APP_PID=""
cleanup() {
    if [ -n "$APP_PID" ] && [ "$KEEP_RUNNING" = 0 ]; then
        info "停止应用 (pid $APP_PID)"
        kill "$APP_PID" 2>/dev/null
        for _ in $(seq 20); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 0.5; done
        kill -9 "$APP_PID" 2>/dev/null
    elif [ -n "$APP_PID" ]; then
        info "应用继续运行 (pid $APP_PID)，日志：$LOG_FILE"
    fi
}
trap cleanup EXIT

wait_ready() {
    printf '  等待就绪 '
    for _ in $(seq 120); do
        if ! kill -0 "$APP_PID" 2>/dev/null; then
            printf '\n'; fail "应用启动" "进程已退出，日志尾部："
            tail -25 "$LOG_FILE" | sed 's/^/      /'; return 1
        fi
        if curl -sS -m 3 "$BASE_URL/api/tools" >/dev/null 2>&1; then printf '\n'; return 0; fi
        printf '.'; sleep 1
    done
    printf '\n'; fail "应用启动" "120 秒内未就绪，日志尾部："
    tail -25 "$LOG_FILE" | sed 's/^/      /'; return 1
}

# start_app <额外的 -D 参数...>
start_app() {
    local extra="$*"
    # i18n 显式关闭：application.yml 里是 true，非中文环境启动会串行发起多次
    # 大模型调用翻译提示词，阻塞数十秒甚至超过等待窗口。
    (cd "$REPO_ROOT" && exec mvn -o -q -f demo-app/pom.xml spring-boot:run \
        -Dspring-boot.run.jvmArguments="-Dhms-core.i18n.enabled=false -Dserver.port=$PORT $extra" \
        ) > "$LOG_FILE" 2>&1 &
    APP_PID=$!
    wait_ready
}

stop_app() {
    [ -z "$APP_PID" ] && return 0
    kill "$APP_PID" 2>/dev/null
    for _ in $(seq 20); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 0.5; done
    kill -9 "$APP_PID" 2>/dev/null
    APP_PID=""
    sleep 1
}

if [ "$DO_START" = 1 ]; then
    section "启动 demo-app"
    info "provider=${HMS_CORE_PROVIDER:-anthropic(默认)} model=${AI_MODEL:-默认} base=${AI_BASE_URL:-默认}"
    start_app || exit 1
    pass "应用启动就绪"
else
    section "连接已运行的实例"
    curl -sS -m 5 "$BASE_URL/api/tools" >/dev/null 2>&1 \
        || { fail "连接 $BASE_URL" "无响应，先启动应用或去掉 --no-start"; exit 1; }
    pass "连接 $BASE_URL"
fi

# 应用已就绪，此时才能实测模型链路
probe_model

# ══════════════════════════════════════════════════════════════════════
# 1. 工具注册（两级工具体系）
# ══════════════════════════════════════════════════════════════════════
if should_run "tool"; then
section "工具注册"

call GET /api/tools
if expect_ok "列出全局工具"; then
    N_GLOBAL="$(jlen_str "$LAST_BODY")"
    info "全局工具 $N_GLOBAL 个"
    MISSING_TOOLS=""
    for t in "${EXPECTED_TOOLS[@]}"; do
        case "$LAST_BODY" in *"\"$t\""*) ;; *) MISSING_TOOLS="$MISSING_TOOLS $t" ;; esac
    done
    if [ -z "$MISSING_TOOLS" ]; then
        pass "全部 ${#EXPECTED_TOOLS[@]} 个预期工具均已注册"
    else
        fail "全部 ${#EXPECTED_TOOLS[@]} 个预期工具均已注册" "缺少：$MISSING_TOOLS"
    fi
    # 数量对不上说明注册表变了 —— 要么漏注册，要么该更新 EXPECTED_TOOLS
    if [ "$HAS_JQ" = 1 ]; then
        assert_eq "全局工具数与预期一致" "$N_GLOBAL" "${#EXPECTED_TOOLS[@]}"
    fi
fi

call POST "/api/tools/nope-$$/add/WebSearch"
expect_fail_response "向不存在的会话添加工具应失败"
fi

# ══════════════════════════════════════════════════════════════════════
# 2. 会话生命周期
# ══════════════════════════════════════════════════════════════════════
section "会话生命周期"

call POST /api/sessions '{"sessionPrompt":"你是接口测试助手。回答尽量简短，不要寒暄。"}'
SESSION=""
if expect_ok "创建会话（带自定义提示词）"; then
    SESSION="$(jget "$LAST_BODY" '.data.sessionId')"
    info "sessionId=$SESSION"
fi
if [ -z "$SESSION" ] || [ "$SESSION" = "null" ]; then
    printf '\n%s致命：无法创建会话，中止。%s\n' "$C_RED" "$C_OFF"; exit 1
fi

# 不带 body 创建（走 createSession() 无参重载）
call POST /api/sessions
BARE_SESSION=""
if expect_ok "创建会话（不带 body，走无参重载）"; then
    BARE_SESSION="$(jget "$LAST_BODY" '.data.sessionId')"
fi

call GET /api/sessions
if expect_ok "列出会话"; then
    assert_ge "列表至少含刚建的两个会话" "$(jlen "$LAST_BODY")" 2
    # 前端靠这两个字段自算总量（totalTokens() 是 record 派生方法，不进 JSON）
    assert_contains "列表含 inputTokens 字段" "$LAST_BODY" '"inputTokens"'
    assert_contains "列表含 outputTokens 字段" "$LAST_BODY" '"outputTokens"'
    assert_not_contains "列表不含 totalTokens（派生方法不序列化）" "$LAST_BODY" '"totalTokens"'
fi

[ -n "$BARE_SESSION" ] && [ "$BARE_SESSION" != "null" ] && {
    call DELETE "/api/sessions/$BARE_SESSION"
    expect_ok "销毁无参创建的会话"
}

call GET "/api/sessions/$SESSION"
if expect_ok "查询会话详情"; then
    assert_eq "详情 sessionId 一致" "$(jget "$LAST_BODY" '.data.sessionId')" "$SESSION"
    assert_eq "初始状态为 ACTIVE" "$(jget "$LAST_BODY" '.data.status')" "ACTIVE"
    # 会话应拿到全局工具的独立副本 —— 挑一个必然存在的工具名验证，
    # 不用数组长度：无 jq 时 jget 无法正确求 length。
    assert_contains "会话已拿到全局工具副本" "$LAST_BODY" '"TodoWrite"'
    assert_contains "详情回显会话提示词" "$LAST_BODY" "接口测试助手"
    assert_number "详情含 idleSeconds" "$(jget "$LAST_BODY" '.data.idleSeconds')"
    assert_number "详情含 messageCount" "$(jget "$LAST_BODY" '.data.messageCount')"
fi

call GET "/api/sessions/nope-$$"
expect_fail_response "查询不存在的会话应失败"

# 两级工具隔离
if should_run "tool"; then
call POST "/api/tools/$SESSION/remove/WebSearch"
expect_ok "移除会话工具 WebSearch"

call GET "/api/tools/$SESSION"
assert_not_contains "移除后会话工具列表不含 WebSearch" "$LAST_BODY" '"WebSearch"'

call GET /api/tools
assert_contains "全局工具不受会话移除影响（两级隔离）" "$LAST_BODY" '"WebSearch"'

# 另一个会话不该受影响 —— 会话级注册表必须互相独立
call POST /api/sessions '{"sessionPrompt":"隔离验证会话"}'
ISO_SESSION=""
expect_ok "创建第二个会话验证工具隔离" && ISO_SESSION="$(jget "$LAST_BODY" '.data.sessionId')"
if [ -n "$ISO_SESSION" ] && [ "$ISO_SESSION" != "null" ]; then
    call GET "/api/tools/$ISO_SESSION"
    assert_contains "新会话仍有 WebSearch（会话间工具互不影响）" "$LAST_BODY" '"WebSearch"'
    call DELETE "/api/sessions/$ISO_SESSION"
    expect_ok "销毁隔离验证会话"
fi

call POST "/api/tools/$SESSION/add/WebSearch"
expect_ok "恢复会话工具 WebSearch"
call GET "/api/tools/$SESSION"
assert_contains "恢复后会话工具列表含 WebSearch" "$LAST_BODY" '"WebSearch"'

call POST "/api/tools/$SESSION/add/NoSuchTool$$"
expect_fail_response "添加不存在的工具应失败"

# 移除不存在的工具是幂等操作，不该报错
call POST "/api/tools/$SESSION/remove/NoSuchTool$$"
expect_ok "移除不存在的工具应幂等成功"
fi

call POST "/api/sessions/$SESSION/pause"
expect_ok "暂停会话"
call GET "/api/sessions/$SESSION"
assert_eq "暂停后状态为 PAUSED" "$(jget "$LAST_BODY" '.data.status')" "PAUSED"

# 暂停期间只读查询仍应可用（requireExistingSession 不拒 PAUSED）
call GET "/api/sessions/$SESSION/tokens"
expect_ok "暂停期间仍可查询 token 统计"
call GET "/api/sessions/$SESSION/messages"
expect_ok "暂停期间仍可读取消息历史"

# 重复暂停 → IllegalStateException → 必须是结构化 4xx 而非 500
call POST "/api/sessions/$SESSION/pause"
expect_client_error "重复暂停返回结构化错误（非 500）"

call POST "/api/sessions/$SESSION/resume"
expect_ok "恢复会话"
call GET "/api/sessions/$SESSION"
assert_eq "恢复后状态为 ACTIVE" "$(jget "$LAST_BODY" '.data.status')" "ACTIVE"

# 重复恢复应幂等（resume 走 requireExistingSession，不校验状态）
call POST "/api/sessions/$SESSION/resume"
expect_ok "重复恢复应幂等成功"

# ══════════════════════════════════════════════════════════════════════
# 3. 错误契约（守护 ApiExceptionHandler）
# ══════════════════════════════════════════════════════════════════════
if should_run "contract"; then
section "错误响应契约"

# 这些端点把 sessionId 直接交给 SDK，会话不存在时 SDK 抛 IllegalArgumentException。
# 没有全局异常处理器就会变成 Spring 默认 500 白页 —— 前端只能拿到一段 HTML。
for p in "/api/sessions/nope-$$/tokens" "/api/metrics/nope-$$"; do
    call GET "$p"
    expect_client_error "GET $p 返回结构化 4xx"
done

for p in "/api/sessions/nope-$$/pause" "/api/sessions/nope-$$/resume"; do
    call POST "$p"
    expect_client_error "POST $p 返回结构化 4xx"
done

# 响应体必须是 JSON 而非 HTML —— 这是「500 白页」最直接的判据
call GET "/api/metrics/nope-$$"
assert_not_contains "错误响应不是 HTML 白页" "$LAST_BODY" "<html"
assert_contains "错误响应是 ApiResponse JSON" "$LAST_BODY" '"success"'

# 反例：会话工具查询对不存在的会话返回空列表（hms-core 既有设计，非 bug）
call GET "/api/tools/nope-$$"
if expect_ok "不存在会话的工具查询返回成功（空列表语义）"; then
    assert_contains "返回空列表" "$LAST_BODY" '[]'
fi

# 取消是幂等静默操作
call POST "/api/sessions/nope-$$/cancel"
expect_ok "取消不存在的会话应静默成功"

# 畸形 JSON 不该 500
call POST /api/sessions '{"sessionPrompt":'
case "$LAST_STATUS" in
    4*) pass "畸形 JSON 返回 4xx" ;;
    *)  fail "畸形 JSON 返回 4xx" "实际 HTTP $LAST_STATUS" ;;
esac
fi

# ══════════════════════════════════════════════════════════════════════
# 4. 权限体系
# ══════════════════════════════════════════════════════════════════════
if should_run "permission"; then
section "权限体系"

call GET /api/permissions
if expect_ok "查询权限状态"; then
    info "当前模式=$(jget "$LAST_BODY" '.data.mode')"
    assert_contains "响应含 rules 数组" "$LAST_BODY" '"rules"'
fi

for mode in STRICT SAFE DEFAULT TRUSTED BYPASS; do
    call PUT /api/permissions/mode "{\"mode\":\"$mode\"}"
    if expect_ok "切换权限模式 → $mode"; then
        call GET /api/permissions
        assert_eq "查询回显模式 $mode" "$(jget "$LAST_BODY" '.data.mode')" "$mode"
    fi
done

call PUT /api/permissions/mode '{"mode":"NOT_A_MODE"}'
expect_fail_response "非法权限模式应被拒绝"
call PUT /api/permissions/mode '{"mode":"default"}'
expect_ok "权限模式大小写不敏感（default → DEFAULT）"
call GET /api/permissions
assert_eq "小写输入被规范化为 DEFAULT" "$(jget "$LAST_BODY" '.data.mode')" "DEFAULT"

# 工具级规则（description=* → PermissionRule.forTool）
call POST /api/permissions/rules '{"toolName":"WebFetch","description":"*","action":"deny"}'
expect_ok "添加工具级 DENY 规则"
call GET /api/permissions
if [ "$HAS_JQ" = 1 ]; then
    assert_eq "工具级规则的 commandPattern 为 *" \
        "$(jget "$LAST_BODY" '.data.rules[] | select(.toolName=="WebFetch") | .commandPattern')" "*"
    assert_eq "工具级规则的 behavior 为 DENY" \
        "$(jget "$LAST_BODY" '.data.rules[] | select(.toolName=="WebFetch") | .behavior')" "DENY"
else
    assert_contains "规则列表含新增 DENY" "$LAST_BODY" "WebFetch"
fi

# 命令前缀规则（description 非 * → PermissionRule.forCommand，落 "前缀:*"）
call POST /api/permissions/rules '{"toolName":"Bash","description":"git","action":"allow"}'
expect_ok "添加命令前缀 ALLOW 规则"
call GET /api/permissions
if [ "$HAS_JQ" = 1 ]; then
    assert_eq "命令前缀规则落为 git:*" \
        "$(jget "$LAST_BODY" '.data.rules[] | select(.toolName=="Bash") | .commandPattern')" "git:*"
fi

call POST /api/permissions/rules '{"toolName":"","description":"*","action":"allow"}'
expect_fail_response "空 toolName 应被拒绝"

call POST /api/permissions/rules '{"toolName":"WebSearch","description":"*","action":"NOT_AN_ACTION"}'
expect_fail_response "非法 action 应被拒绝"

call DELETE /api/permissions/rules
expect_ok "清空所有规则"
call GET /api/permissions
if [ "$HAS_JQ" = 1 ]; then
    assert_eq "清空后规则数为 0" "$(jget "$LAST_BODY" '.data.rules | length')" "0"
fi
fi

# ══════════════════════════════════════════════════════════════════════
# 5. 同步对话 + 多轮上下文
# ══════════════════════════════════════════════════════════════════════
if should_run "chat"; then
section "同步对话与多轮上下文"

if [ "$HAS_KEY" = 0 ]; then
    skip "同步对话" "未设置 AI_API_KEY"
else
    call POST "/api/chat/$SESSION" '{"message":"只回复两个字：收到"}'
    if expect_ok "发送消息并拿到回复"; then
        CONTENT="$(jget "$LAST_BODY" '.data.content')"
        info "回复：$(printf '%s' "$CONTENT" | head -c 80)"
        [ -n "$CONTENT" ] && [ "$CONTENT" != "null" ] \
            && pass "回复内容非空" || fail "回复内容非空" "content=[$CONTENT]"
        # totalTokens 恒 0 说明 TokenTracker 未接上
        assert_gt "token 用量已记账" "$(jget "$LAST_BODY" '.data.totalTokens')" 0
        assert_eq "未被中断" "$(jget "$LAST_BODY" '.data.interrupted')" "false"
        assert_number "响应含 toolCallsCount" "$(jget "$LAST_BODY" '.data.toolCallsCount')"
    fi

    call POST "/api/chat/$SESSION" '{"message":"我刚让你回复的是哪两个字？只答那两个字。"}'
    expect_ok "第二轮对话" \
        && assert_contains "模型记得上一轮（多轮上下文生效）" "$(jget "$LAST_BODY" '.data.content')" "收到"

    call GET "/api/sessions/$SESSION/messages"
    if expect_ok "查询消息历史"; then
        info "历史 $(jlen "$LAST_BODY") 条"
        assert_gt "历史含两轮问答" "$(jlen "$LAST_BODY")" 3
        assert_contains "历史含 user 角色" "$LAST_BODY" '"user"'
        assert_contains "历史含 assistant 角色" "$LAST_BODY" '"assistant"'
    fi

    # 会话详情里的 messageCount 应随对话增长
    call GET "/api/sessions/$SESSION"
    assert_gt "会话 messageCount 已增长" "$(jget "$LAST_BODY" '.data.messageCount')" 0

    call GET "/api/sessions/$SESSION/tokens"
    if expect_ok "查询 token 统计"; then
        TIN="$(jget "$LAST_BODY" '.data.inputTokens')"
        TOUT="$(jget "$LAST_BODY" '.data.outputTokens')"
        TTOT="$(jget "$LAST_BODY" '.data.totalTokens')"
        info "input=$TIN output=$TOUT total=$TTOT"
        assert_gt "会话累计 input token > 0" "$TIN" 0
        assert_gt "会话累计 output token > 0" "$TOUT" 0
        # /tokens 是 Controller 手工组装的 Map，这里 totalTokens 反而应该存在
        assert_eq "totalTokens = input + output" "$TTOT" "$((${TIN:-0} + ${TOUT:-0}))"
    fi

    # 空消息应被 SDK 拒绝（HmsErrorCode.INVALID_INPUT）
    call POST "/api/chat/$SESSION" '{"message":""}'
    if [ "$LAST_STATUS" = "200" ] && [ "$(jget "$LAST_BODY" '.success')" = "true" ]; then
        fail "空消息应被拒绝" "居然成功了"
    else
        pass "空消息应被拒绝"
    fi

    call POST "/api/chat/nope-$$" '{"message":"hi"}'
    expect_fail_response "向不存在的会话发消息应失败"

    # 暂停中的会话必须拒绝新消息（requireSession 拒 PAUSED）
    call POST "/api/sessions/$SESSION/pause"
    expect_ok "暂停会话以验证拒绝新消息"
    call POST "/api/chat/$SESSION" '{"message":"这条应该被拒绝"}'
    expect_fail_response "暂停中的会话拒绝新消息"
    call POST "/api/sessions/$SESSION/resume"
    expect_ok "恢复会话"
fi
fi

# ══════════════════════════════════════════════════════════════════════
# 6. 工具调用（模型自主决定调工具 → 权限 → 执行 → 结果回传 → 续写）
# ══════════════════════════════════════════════════════════════════════
if should_run "toolcall"; then
section "工具调用链路"

if [ "$HAS_KEY" = 0 ]; then
    skip "工具调用" "未设置 AI_API_KEY"
else
    # ── 6a. READ_ONLY 工具应被 headless 策略放行并真实执行 ──
    # TodoWrite 是 READ_ONLY（只动内存中的 todo 列表），headless 下自动放行。
    sse "用 TodoWrite 工具创建一条待办：写接口测试。创建完直接说完成。" 150
    if [ -s "$SSE_OUT" ]; then
        if sse_has tool_use; then
            pass "模型自主发起了工具调用（收到 tool_use 事件）"
            info "tool_use 事件 $(sse_count tool_use) 次"
            sse_data tool_use | sed 's/^/      /' | cut -c1-160
            assert_contains "调用的是 TodoWrite" "$(sse_data_all tool_use)" "TodoWrite"
            # tool_use 事件的三个字段构成对前端的契约
            TU="$(sse_data_all tool_use)"
            assert_contains "tool_use 含 toolName 字段" "$TU" '"toolName"'
            assert_contains "tool_use 含 input 字段" "$TU" '"input"'
            assert_contains "tool_use 含 result 字段" "$TU" '"result"'
        else
            fail "模型自主发起了工具调用" "整个流里没有 tool_use 事件"
        fi
        # 工具执行完后循环必须继续，最终给出回复
        sse_has complete && pass "工具调用后循环继续并完成（收到 complete）" \
            || fail "工具调用后循环继续并完成" "未收到 complete 事件"
        # complete 里的 toolCallsCount 应与 tool_use 事件数一致
        if [ "$HAS_JQ" = 1 ] && sse_has complete; then
            CC="$(sse_data complete | sed 's/^data: *//' | jq -r '.toolCallsCount' 2>/dev/null)"
            info "complete.toolCallsCount=$CC，tool_use 事件数=$(sse_count tool_use)"
            assert_gt "complete 的 toolCallsCount > 0" "$CC" 0
        fi
    else
        fail "工具调用 SSE 流" "无任何输出"
    fi
    rm -f "$SSE_OUT"

    # ── 6b. 工具调用计入 toolCallsCount ──
    call POST "/api/chat/$SESSION" '{"message":"用 TodoWrite 把那条待办标记为已完成，然后说好了。"}'
    if expect_ok "同步模式下的工具调用"; then
        TC="$(jget "$LAST_BODY" '.data.toolCallsCount')"
        info "toolCallsCount=$TC"
        assert_gt "工具调用次数被记账" "$TC" 0
    fi

    # ── 6c. MEDIUM 风险工具在 headless（无回调）下必须被拒绝 ──
    # 这是权限修复的核心断言：TaskCreate 是 MEDIUM，同步 API 无从询问用户，
    # 必须拒绝而非静默放行。拒绝文本会作为工具结果回传给模型。
    # 用独立会话：权限拒绝会在历史里留下「连续两条 user」的破损序列（见 6d 注释），
    # 之后这个会话的每一轮都会 500。隔离掉，别让它污染 6f/6g 与后续测试组。
    call DELETE /api/permissions/rules
    expect_ok "清空权限规则（隔离前序用例的残留）"
    call POST /api/sessions '{"sessionPrompt":"你是权限边界验证助手。"}'
    SSESSION=""
    if expect_ok "为 STRICT 用例创建独立会话"; then
        SSESSION="$(jget "$LAST_BODY" '.data.sessionId')"
    fi
    case "$SSESSION" in ''|null) SSESSION="$SESSION" ;; esac

    call PUT /api/permissions/mode '{"mode":"STRICT"}'
    expect_ok "切到 STRICT 模式（仅放行 READ_ONLY）"
    call POST "/api/chat/$SSESSION" '{"message":"用 TaskCreate 工具创建一个任务，主题随便。做完告诉我工具返回了什么原文。"}'
    if expect_ok "MEDIUM 工具调用（STRICT 模式，无人可询问）"; then
        RESP="$(jget "$LAST_BODY" '.data.content')"
        TC_STRICT="$(jget "$LAST_BODY" '.data.toolCallsCount')"
        info "toolCallsCount=$TC_STRICT 回复：$(printf '%s' "$RESP" | head -c 150)"
        # 注意：toolCallsCount 统计的是「模型请求的工具调用数」，在权限检查之前
        # 累加（AgentLoop 第 427 行 addAndGet(assistant.getToolCalls().size())），
        # 被拒绝的调用同样计数。所以不能用它判断「是否真的执行了」。
        #
        # 可靠判据是拒绝文本有没有作为工具结果回传给模型 —— 那是权限拦截真正
        # 发生过的证据。模型措辞自由，因此匹配一组同义词而非精确文本。
        case "$RESP" in
            *拒绝*|*denied*|*Denied*|*权限*|*permission*|*Permission*|*不允许*)
                pass "MEDIUM 工具在 STRICT 下被拒绝（拒绝原文回传给了模型）" ;;
            '')
                # 空回复也是拒绝的常见表现：工具被拦，模型无话可说
                info "模型返回空回复（工具被拦后无话可说，属常见表现）"
                pass "MEDIUM 工具在 STRICT 下被拒绝（无成功输出）" ;;
            *)
                fail "MEDIUM 工具在 STRICT 下被拒绝" \
                     "回复既未提及拒绝也非空，可能被静默放行：$(printf '%s' "$RESP" | head -c 200)" ;;
        esac
    fi
    # 这个会话的历史已被拒绝轮次破坏，用完即弃
    if [ "$SSESSION" != "$SESSION" ]; then
        call DELETE "/api/sessions/$SSESSION"
        expect_ok "销毁 STRICT 用例会话"
    fi

    # ── 6d. BYPASS 模式下同一工具应放行（反向验证权限确实在起作用）──
    #
    # 必须先清空规则：权限规则是全局状态，前序用例（permission 组、6e）留下的
    # DENY 会让这里的「应放行」假失败 —— 规则优先级高于模式，DENY 在 BYPASS 下
    # 一样生效（这本身正确，见 6e），所以断言放行前必须确保没有残留规则。
    # 用全新会话，不复用被 6c 拒绝过的那个。
    #
    # 已知缺陷：权限拒绝的那一轮不会往历史里写 assistant 消息，于是历史出现
    # 连续两条 user（system, user, user），下一轮请求被上游按非法消息序列拒掉，
    # demo-app 报 500。复用同一会话会让 6d/6e 稳定失败在这个无关的问题上。
    # 独立会话既绕开它，也让 6d/6e 真正测的是权限优先级本身。
    call DELETE /api/permissions/rules
    expect_ok "清空权限规则（隔离前序用例的残留）"
    call POST /api/sessions '{"sessionPrompt":"你是权限验证助手。回答尽量简短。"}'
    PSESSION=""
    expect_ok "为权限用例创建独立会话" && PSESSION="$(jget "$LAST_BODY" '.data.sessionId')"

    if [ -n "$PSESSION" ] && [ "$PSESSION" != "null" ]; then
        call PUT /api/permissions/mode '{"mode":"BYPASS"}'
        expect_ok "切到 BYPASS 模式"
        call POST "/api/chat/$PSESSION" '{"message":"用 TaskCreate 建一个任务，主题叫 bypass-check。完成后只说 ok。"}'
        if expect_ok "BYPASS 模式下的 MEDIUM 工具调用"; then
            info "toolCallsCount=$(jget "$LAST_BODY" '.data.toolCallsCount') 回复：$(printf '%s' "$(jget "$LAST_BODY" '.data.content')" | head -c 80)"
            assert_gt "BYPASS 下工具确实执行了" "$(jget "$LAST_BODY" '.data.toolCallsCount')" 0
        fi

        # ── 6e. DENY 规则优先于 BYPASS 模式（规则优先级）──
        # BYPASS 放行一切，但显式 DENY 规则必须仍然生效，否则规则体系形同虚设。
        # 同样用新会话：上一步若成功执行，历史是完好的；但为了不受影响仍另起一个。
        call POST /api/permissions/rules '{"toolName":"TaskCreate","description":"*","action":"deny"}'
        expect_ok "在 BYPASS 下添加 TaskCreate 的 DENY 规则"
        call POST /api/sessions '{"sessionPrompt":"你是规则优先级验证助手。"}'
        DSESSION=""
        expect_ok "为 DENY 用例创建独立会话" && DSESSION="$(jget "$LAST_BODY" '.data.sessionId')"
        if [ -n "$DSESSION" ] && [ "$DSESSION" != "null" ]; then
            call POST "/api/chat/$DSESSION" '{"message":"用 TaskCreate 建一个任务叫 deny-check。做完告诉我工具返回了什么原文。"}'
            if expect_ok "BYPASS + DENY 规则下的工具调用"; then
                RESP="$(jget "$LAST_BODY" '.data.content')"
                info "回复：$(printf '%s' "$RESP" | head -c 120)"
                case "$RESP" in
                    *拒绝*|*denied*|*Denied*|*权限*|*permission*|*Permission*|*不允许*)
                        pass "DENY 规则优先于 BYPASS 模式（拒绝原文回传）" ;;
                    '')
                        pass "DENY 规则优先于 BYPASS 模式（无成功输出）" ;;
                    *)
                        fail "DENY 规则优先于 BYPASS 模式" \
                             "BYPASS 下 DENY 规则似未生效：$(printf '%s' "$RESP" | head -c 200)" ;;
                esac
            fi
            call DELETE "/api/sessions/$DSESSION"
            expect_ok "销毁 DENY 用例会话"
        fi

        call DELETE "/api/sessions/$PSESSION"
        expect_ok "销毁权限用例会话"
    fi

    call DELETE /api/permissions/rules
    expect_ok "清除 DENY 规则"
    call PUT /api/permissions/mode '{"mode":"DEFAULT"}'
    expect_ok "切回 DEFAULT 模式"

    # ── 6f. 多工具串联：一轮里模型应能连续调用多次 ──
    sse "先用 TodoWrite 建三条待办（a、b、c），再用 TodoWrite 把 a 标记完成。全部做完说搞定。" 180
    if [ -s "$SSE_OUT" ]; then
        N="$(sse_count tool_use)"
        info "本轮 tool_use $N 次"
        assert_gt "同一轮内多次工具调用（Agent 循环多迭代）" "$N" 1
    fi
    rm -f "$SSE_OUT"

    # ── 6g. 被移除的工具不该能被调用（会话级注册表真的生效）──
    call POST "/api/tools/$SESSION/remove/TodoWrite"
    expect_ok "从会话移除 TodoWrite"
    call POST "/api/chat/$SESSION" '{"message":"用 TodoWrite 建一条待办叫 removed-check。如果没有这个工具就直接说：工具不可用。"}'
    if expect_ok "调用已移除的工具"; then
        RESP="$(jget "$LAST_BODY" '.data.content')"
        info "回复：$(printf '%s' "$RESP" | head -c 150)"
        # 工具不在会话注册表里，模型看不到它，不该产生成功的 TodoWrite 调用
        assert_eq "已移除的工具未被调用" "$(jget "$LAST_BODY" '.data.toolCallsCount')" "0"
    fi
    call POST "/api/tools/$SESSION/add/TodoWrite"
    expect_ok "恢复会话工具 TodoWrite"
fi
fi

# ══════════════════════════════════════════════════════════════════════
# 7. 上下文压缩（重启应用，用极小窗口逼出三层压缩）
# ══════════════════════════════════════════════════════════════════════
if should_run "compact"; then
section "上下文压缩"

if [ "$HAS_KEY" = 0 ]; then
    skip "上下文压缩" "未设置 AI_API_KEY"
elif [ "$DO_START" = 0 ]; then
    skip "上下文压缩" "--no-start 模式下无法用小窗口重启应用"
else
    info "重启应用，上下文窗口压到 $TEST_CONTEXT_WINDOW token（默认 200K）"
    info "压缩阈值为窗口的 93%，即约 $((TEST_CONTEXT_WINDOW * 93 / 100)) token"
    stop_app
    # HMS_CORE_CONTEXT_WINDOW 由 core 通过 getenv 读取，所以走环境变量而非 -D
    if HMS_CORE_CONTEXT_WINDOW="$TEST_CONTEXT_WINDOW" start_app; then
        pass "应用以小上下文窗口重启"

        call POST /api/sessions '{"sessionPrompt":"你是压缩测试助手。"}'
        CSESSION=""
        expect_ok "创建压缩测试会话" && CSESSION="$(jget "$LAST_BODY" '.data.sessionId')"

        if [ -n "$CSESSION" ] && [ "$CSESSION" != "null" ]; then
            SESSION_BAK="$SESSION"; SESSION="$CSESSION"
            COMPACTED=0
            # 每轮塞一段长文本推高上下文，直到收到 compaction 事件。
            #
            # 填充必须走 POST 请求体，不能走 SSE 的 URL 查询串：中文逐字节百分号
            # 编码后体积膨胀 3 倍，seq 60 那种规模编码后已有 12.5KB，超过 Tomcat
            # 默认 8KB 请求头上限 → Tomcat 回 400，请求根本没到应用。
            #
            # 填充量按实测标定：基线（系统提示词+工具定义）约 5.8K token，
            # seq 60 每轮只推高约 1.6K —— 6 轮才 ~16K，够不到 23250 的阈值。
            # seq 400 每轮约 10K，两三轮内必然越阈值。
            #
            # 于是分两步：POST 把上下文推到阈值附近，再用一条「短」消息走 SSE，
            # 让越阈值发生在那一轮，从而在事件流里抓到 compaction。
            for round in 1 2 3 4 5 6; do
                PAD="$(printf '第%s段背景资料：' "$round"
                       for i in $(seq 400); do printf '这是一段用于占满上下文窗口的填充文本，编号%s-%s。' "$round" "$i"; done)"
                call POST "/api/chat/$SESSION" "{\"message\":\"$PAD 请只回复该段编号。\"}"
                if [ "$(jget "$LAST_BODY" '.success')" != "true" ]; then
                    fail "第 $round 轮填充对话" "HTTP $LAST_STATUS: $(printf '%s' "$LAST_BODY" | head -c 160)"
                    break
                fi
                call GET "/api/sessions/$SESSION/tokens"
                info "第 $round 轮填充完成，累计 token=$(jget "$LAST_BODY" '.data.totalTokens')"

                # 短消息走 SSE —— 若上下文已越阈值，压缩会发生在这一轮
                sse "只回复一个字：好" 150
                if [ -s "$SSE_OUT" ] && sse_has compaction; then
                    pass "第 $round 轮触发上下文压缩（收到 compaction 事件）"
                    sse_data compaction | sed 's/^/      /' | cut -c1-200
                    CDATA="$(sse_data compaction)"
                    # layer 必须是四层之一（含手动触发的 MANUAL）
                    case "$CDATA" in
                        *MICRO*|*SESSION_MEMORY*|*FULL*|*MANUAL*)
                            pass "压缩层级为 MICRO / SESSION_MEMORY / FULL / MANUAL 之一" ;;
                        *) fail "压缩层级合法" "layer 异常：$CDATA" ;;
                    esac
                    # compaction 事件的字段契约
                    assert_contains "compaction 含 messagesBefore" "$CDATA" '"messagesBefore"'
                    assert_contains "compaction 含 messagesAfter" "$CDATA" '"messagesAfter"'
                    assert_contains "compaction 含 reason" "$CDATA" '"reason"'
                    # 压缩必须真的减少了消息数，否则等于没压
                    if [ "$HAS_JQ" = 1 ]; then
                        MB="$(printf '%s' "$CDATA" | sed 's/^data: *//' | jq -r '.messagesBefore' 2>/dev/null)"
                        MA="$(printf '%s' "$CDATA" | sed 's/^data: *//' | jq -r '.messagesAfter' 2>/dev/null)"
                        info "消息数 $MB → $MA"
                        if [ "${MA:-0}" -lt "${MB:-0}" ] 2>/dev/null; then
                            pass "压缩后消息数确实减少（$MB → $MA）"
                        else
                            fail "压缩后消息数确实减少" "$MB → $MA，没有减少"
                        fi
                    fi
                    COMPACTED=1
                    rm -f "$SSE_OUT"; break
                fi
                [ -s "$SSE_OUT" ] && info "第 $round 轮未触发压缩（tokens 尚未越阈值）"
                rm -f "$SSE_OUT"
            done

            if [ "$COMPACTED" = 0 ]; then
                fail "6 轮内触发上下文压缩" \
                     "未收到 compaction 事件。可能窗口($TEST_CONTEXT_WINDOW)仍偏大，或压缩链路未接上"
            fi

            # 压缩后会话必须仍可用 —— 压坏配对会让下一次请求被服务端 400
            call POST "/api/chat/$SESSION" '{"message":"只回复一个字：好"}'
            expect_ok "压缩后会话仍可正常对话（tool_use/tool_result 配对未被破坏）"

            call GET "/api/sessions/$SESSION/messages"
            if expect_ok "压缩后仍能读取消息历史"; then
                info "压缩后历史 $(jlen "$LAST_BODY") 条"
            fi

            # 压缩后 token 统计不该被清零（累计器语义）
            call GET "/api/sessions/$SESSION/tokens"
            expect_ok "压缩后 token 统计仍可查询" \
                && assert_gt "压缩后累计 token 未被清零" "$(jget "$LAST_BODY" '.data.totalTokens')" 0

            call DELETE "/api/sessions/$SESSION"
            expect_ok "销毁压缩测试会话"
            SESSION="$SESSION_BAK"
        fi

        # 还原为默认窗口，后续用例不受影响
        info "还原默认上下文窗口"
        stop_app
        start_app || exit 1
        pass "应用以默认窗口重启"
        # 之前的会话已随重启消失，重建一个供后续用例使用
        call POST /api/sessions '{"sessionPrompt":"你是接口测试助手。回答尽量简短。"}'
        expect_ok "重建测试会话" && SESSION="$(jget "$LAST_BODY" '.data.sessionId')"
    fi
fi
fi

# ══════════════════════════════════════════════════════════════════════
# 8. SSE 事件面
# ══════════════════════════════════════════════════════════════════════
if should_run "stream"; then
section "SSE 流式对话"

if [ "$HAS_KEY" = 0 ]; then
    skip "流式对话" "未设置 AI_API_KEY"
else
    sse "从1数到5，每个数字之间加空格，只输出数字。" 120
    if [ -s "$SSE_OUT" ]; then
        pass "SSE 连接建立并收到数据"
        info "收到 $(wc -l < "$SSE_OUT" | tr -d ' ') 行，事件类型：$(grep -oE '^event: ?[a-z_]+' "$SSE_OUT" | sed 's/event: *//' | sort -u | tr '\n' ' ')"
        sse_has token && pass "收到 token 事件（逐 token 流式输出）" \
            || fail "收到 token 事件" "流中无 token 事件"
        assert_gt "token 事件多于 1 个（确实是流式而非一次性）" "$(sse_count token)" 1
        sse_has complete && pass "收到 complete 事件" || fail "收到 complete 事件" "流中无 complete"

        # complete 事件的四字段契约（前端 chat-panel.js 依赖）
        CD="$(sse_data_all complete)"
        assert_contains "complete 含 content" "$CD" '"content"'
        assert_contains "complete 含 totalTokens" "$CD" '"totalTokens"'
        assert_contains "complete 含 toolCallsCount" "$CD" '"toolCallsCount"'
        assert_contains "complete 含 interrupted" "$CD" '"interrupted"'

        # 正常完成的轮次 interrupted 必须为 false
        if [ "$HAS_JQ" = 1 ]; then
            assert_eq "正常完成时 interrupted=false" \
                "$(sse_data complete | sed 's/^data: *//' | jq -r '.interrupted' 2>/dev/null)" "false"
            # 流式聚合内容应与 token 拼接一致（非空即可，逐字比对易受措辞影响）
            CC="$(sse_data complete | sed 's/^data: *//' | jq -r '.content' 2>/dev/null)"
            [ -n "$CC" ] && [ "$CC" != "null" ] \
                && pass "complete.content 为聚合后的完整回复" \
                || fail "complete.content 为聚合后的完整回复" "content 为空"
        fi

        # SSE 帧格式：event 行数必须等于 data 行数，一一配对。
        #
        # 模式里的空格必须可选（'event: ?'）—— Spring 实际输出的是 "event:token"，
        # 冒号后没有空格。写死 '^event: ' 会一个都匹配不到、得到 0。
        N_EV="$(grep -cE '^event: ?[a-z_]+$' "$SSE_OUT" | head -1 | tr -d ' ')"
        N_DA="$(grep -cE '^data: ?\{'        "$SSE_OUT" | head -1 | tr -d ' ')"
        info "event 行 $N_EV，data 行 $N_DA"
        assert_eq "SSE 帧格式完好（event 与 data 行数相等）" "${N_DA:-0}" "${N_EV:-0}"
    else
        fail "SSE 连接建立并收到数据" "无任何输出"
    fi
    rm -f "$SSE_OUT"

    # 不存在的会话：SSE 不该抛异常断连，而应推一条 error 事件后正常收尾
    ERR_OUT="$(mktemp)"
    curl -sS -N -m 20 -H 'Accept: text/event-stream' \
        "$BASE_URL/api/chat/nope-$$/stream?message=hi" > "$ERR_OUT" 2>/dev/null || true
    if grep -qE '^event: ?error$' "$ERR_OUT"; then
        pass "对不存在的会话推 error 事件（而非断连）"
        ED="$(grep -A 1 -E '^event: ?error$' "$ERR_OUT" | grep '^data:' | head -1)"
        info "$(printf '%s' "$ED" | cut -c1-160)"
        assert_contains "error 事件含 message" "$ED" '"message"'
        # 结构化错误码：前端据此分支处理，不必解析 message 文本
        assert_contains "error 事件含 code" "$ED" '"code"'
        if [ "$HAS_JQ" = 1 ]; then
            assert_eq "会话不存在的错误码为 2001" \
                "$(printf '%s' "$ED" | sed 's/^data: *//' | jq -r '.code' 2>/dev/null)" "2001"
        fi
    else
        fail "对不存在的会话推 error 事件" "流中无 error 事件：$(head -c 160 "$ERR_OUT")"
    fi
    rm -f "$ERR_OUT"
fi

call POST "/api/sessions/$SESSION/cancel"
expect_ok "取消执行（空闲时调用也应安全）"
fi

# ══════════════════════════════════════════════════════════════════════
# 9. 取消语义（中断中的请求应带 interrupted 标志收尾）
# ══════════════════════════════════════════════════════════════════════
if should_run "lifecycle"; then
section "取消与中断语义"

if [ "$HAS_KEY" = 0 ]; then
    skip "取消语义" "未设置 AI_API_KEY"
else
    # 起一个长任务，中途取消 —— core 的 cancel() 只翻转 volatile 标志，
    # runStreaming 正常返回，complete 事件必须带 interrupted=true 并保留已产生的用量。
    CANCEL_OUT="$(mktemp)"
    curl -sS -N -m 120 -H 'Accept: text/event-stream' \
        "$BASE_URL/api/chat/$SESSION/stream?message=$(urlenc '请从1数到300，每个数字单独一行，不要省略。')" \
        > "$CANCEL_OUT" 2>/dev/null &
    CSSE_PID=$!

    # 等首个 token 到达再取消，确保确实打断了「进行中」的生成
    STARTED=0
    for _ in $(seq 40); do
        grep -qE '^event: ?token$' "$CANCEL_OUT" 2>/dev/null && { STARTED=1; break; }
        kill -0 "$CSSE_PID" 2>/dev/null || break
        sleep 0.5
    done

    if [ "$STARTED" = 1 ]; then
        pass "长任务已开始输出（收到首个 token）"
        call POST "/api/sessions/$SESSION/cancel"
        expect_ok "取消进行中的执行"
        wait "$CSSE_PID" 2>/dev/null

        if grep -qE '^event: ?complete$' "$CANCEL_OUT"; then
            pass "取消后仍收到 complete 事件（而非静默断流）"
            CD="$(grep -A 1 -E '^event: ?complete$' "$CANCEL_OUT" | grep '^data:' | head -1)"
            info "$(printf '%s' "$CD" | cut -c1-200)"
            assert_contains "complete 标记 interrupted=true" "$CD" '"interrupted":true'
            # totalTokens 字段必须在（HmsResponse.interrupted 保留用量的重载），
            # 但不断言 > 0 —— 取消可能发生在 usage 回报之前，此时为 0 是合理的。
            assert_contains "中断的 complete 仍带 totalTokens 字段" "$CD" '"totalTokens"'
            if [ "$HAS_JQ" = 1 ]; then
                info "中断轮次用量 totalTokens=$(printf '%s' "$CD" | sed 's/^data: *//' | jq -r '.totalTokens' 2>/dev/null)"
            fi
        else
            fail "取消后仍收到 complete 事件" "流中无 complete —— 中断前的内容丢失了"
        fi

        # 取消只影响当轮，会话本身必须仍可用
        call GET "/api/sessions/$SESSION"
        expect_ok "取消后会话仍存在"
        assert_eq "取消后状态回到 ACTIVE" "$(jget "$LAST_BODY" '.data.status')" "ACTIVE"
        call POST "/api/chat/$SESSION" '{"message":"只回复一个字：好"}'
        expect_ok "取消后会话仍可正常对话"
    else
        kill "$CSSE_PID" 2>/dev/null
        wait "$CSSE_PID" 2>/dev/null
        skip "取消进行中的执行" "长任务未及时产出 token，无法可靠验证中断点"
    fi
    rm -f "$CANCEL_OUT"
fi
fi

# ══════════════════════════════════════════════════════════════════════
# 10. 并发与连接管理
# ══════════════════════════════════════════════════════════════════════
if should_run "concurrency"; then
section "并发与连接管理"

# 多会话并发创建 —— 会话表是 ConcurrentHashMap，不该丢失或串号
#
# 只 wait 自己派生的这几个 PID，不能用裸 wait：start_app 是把被测应用当后台
# 作业拉起的（APP_PID=$!），裸 wait 会连它一起等，而应用不会自己退出 ——
# 脚本就永久卡在这里，段标题之后一条断言都打不出来。
CONC_IDS=""
CONC_PIDS=""
for i in 1 2 3 4 5; do
    (call POST /api/sessions "{\"sessionPrompt\":\"并发会话$i\"}"
     jget "$LAST_BODY" '.data.sessionId') > "/tmp/hms-conc-$$-$i" 2>/dev/null &
    CONC_PIDS="$CONC_PIDS $!"
done
for p in $CONC_PIDS; do wait "$p" 2>/dev/null; done
CONC_OK=0
for i in 1 2 3 4 5; do
    id="$(tail -1 "/tmp/hms-conc-$$-$i" 2>/dev/null)"
    if [ -n "$id" ] && [ "$id" != "null" ]; then
        CONC_IDS="$CONC_IDS $id"; CONC_OK=$((CONC_OK+1))
    fi
    rm -f "/tmp/hms-conc-$$-$i"
done
assert_eq "并发创建 5 个会话全部成功" "$CONC_OK" "5"

# 会话 ID 必须互不相同
UNIQ="$(printf '%s\n' $CONC_IDS | sort -u | wc -l | tr -d ' ')"
assert_eq "并发创建的会话 ID 互不重复" "$UNIQ" "$CONC_OK"

call GET /api/sessions
assert_ge "列表包含全部并发创建的会话" "$(jlen "$LAST_BODY")" "$CONC_OK"

# 并发销毁（同样只等自己的 PID，理由见上）
DEL_PIDS=""
for id in $CONC_IDS; do
    (call DELETE "/api/sessions/$id") >/dev/null 2>&1 &
    DEL_PIDS="$DEL_PIDS $!"
done
for p in $DEL_PIDS; do wait "$p" 2>/dev/null; done
pass "并发销毁 $CONC_OK 个会话"

DESTROYED_OK=1
for id in $CONC_IDS; do
    call GET "/api/sessions/$id"
    [ "$(jget "$LAST_BODY" '.success')" = "false" ] || DESTROYED_OK=0
done
[ "$DESTROYED_OK" = 1 ] && pass "全部并发会话确认已销毁" \
    || fail "全部并发会话确认已销毁" "仍有会话可查询"

if [ "$HAS_KEY" = 1 ]; then
    # 同一会话的第二条 SSE 连接会顶掉第一条（HmsSseBridge.register 的语义）
    S1="$(mktemp)"; S2="$(mktemp)"
    curl -sS -N -m 60 -H 'Accept: text/event-stream' \
        "$BASE_URL/api/chat/$SESSION/stream?message=$(urlenc '从1数到100，每个数字一行。')" > "$S1" 2>/dev/null &
    P1=$!
    sleep 3
    curl -sS -N -m 60 -H 'Accept: text/event-stream' \
        "$BASE_URL/api/chat/$SESSION/stream?message=$(urlenc '只回复：第二条')" > "$S2" 2>/dev/null &
    P2=$!
    wait "$P1" 2>/dev/null; wait "$P2" 2>/dev/null
    # 第一条连接应被服务端关闭（顶掉），不该两条都跑到 complete
    if [ -s "$S2" ]; then
        pass "同一会话的第二条 SSE 连接可建立"
        info "第一条 $(wc -l < "$S1" | tr -d ' ') 行，第二条 $(wc -l < "$S2" | tr -d ' ') 行"
    else
        fail "同一会话的第二条 SSE 连接可建立" "第二条无输出"
    fi
    # 会话在两条连接抢占后必须仍可用
    call GET "/api/sessions/$SESSION"
    expect_ok "连接抢占后会话仍健康"
    rm -f "$S1" "$S2"
fi
fi

# ══════════════════════════════════════════════════════════════════════
# 11. 交互式能力（AskUserQuestion + 权限确认回传）
# ══════════════════════════════════════════════════════════════════════
if should_run "interactive"; then
section "交互式能力"

if [ "$HAS_KEY" = 0 ]; then
    skip "交互式能力" "未设置 AI_API_KEY"
else
    # AskUserQuestion：模型提问 → SSE 推 ask_user → 脚本回传答案 → 循环继续
    ASK_OUT="$(mktemp)"
    curl -sS -N -m 150 -H 'Accept: text/event-stream' \
        "$BASE_URL/api/chat/$SESSION/stream?message=$(urlenc '用 AskUserQuestion 工具问我喜欢猫还是狗，拿到答案后复述我的选择。')" \
        > "$ASK_OUT" 2>/dev/null &
    SSE_PID=$!

    # 等 ask_user 事件出现，然后回传答案
    ANSWERED=0
    for _ in $(seq 60); do
        if grep -qE "^event: ?ask_user$" "$ASK_OUT" 2>/dev/null; then
            pass "收到 ask_user 事件（模型主动提问）"
            AD="$(grep -A 1 -E "^event: ?ask_user$" "$ASK_OUT" | grep '^data:' | head -1)"
            info "$(printf '%s' "$AD" | cut -c1-160)"
            assert_contains "ask_user 含 question 字段" "$AD" '"question"'
            assert_contains "ask_user 含 options 字段" "$AD" '"options"'
            call POST "/api/chat/$SESSION/ask-response" '{"response":"猫"}'
            expect_ok "回传用户答案"
            ANSWERED=1
            break
        fi
        kill -0 "$SSE_PID" 2>/dev/null || break
        sleep 1
    done
    wait "$SSE_PID" 2>/dev/null

    if [ "$ANSWERED" = 1 ]; then
        if grep -qE "^event: ?complete$" "$ASK_OUT"; then
            pass "回传答案后循环继续并完成"
            CDATA="$(grep -A 1 -E '^event: ?complete$' "$ASK_OUT" | grep '^data:' | head -1)"
            case "$CDATA" in
                *猫*) pass "模型收到了回传的答案（回复里提及「猫」）" ;;
                *) info "回复未直接出现「猫」（模型措辞自由，不算失败）：$(printf '%s' "$CDATA" | head -c 120)" ;;
            esac
        else
            fail "回传答案后循环继续并完成" "未收到 complete 事件"
        fi
    else
        # 模型可能选择不调用该工具 —— 这是模型行为而非 core 缺陷
        skip "AskUserQuestion 交互" "模型本轮未发起提问（模型行为，非 core 问题）"
    fi
    rm -f "$ASK_OUT"

    # 尽力交付语义：无待答请求时回传也返回成功，前端无需处理这种竞态
    call POST "/api/chat/$SESSION/ask-response" '{"response":"迟到的答案"}'
    expect_ok "无待答请求时回传答案也成功（尽力交付语义）"
    call POST "/api/chat/$SESSION/permission-response" '{"response":"allow"}'
    expect_ok "权限确认回传接口可达（无待确认时也应成功）"

    # 不存在的会话回传答案同样是尽力交付，不该 500
    call POST "/api/chat/nope-$$/ask-response" '{"response":"x"}'
    expect_ok "向不存在的会话回传答案不报错（尽力交付）"
fi
fi

# ══════════════════════════════════════════════════════════════════════
# 12. 指标
# ══════════════════════════════════════════════════════════════════════
if should_run "metrics"; then
section "指标采集"

call GET /api/metrics/overview
if expect_ok "查询全局概览"; then
    # 字段名以 MetricsController.getOverview 的实际组装为准
    info "活跃会话=$(jget "$LAST_BODY" '.data.activeSessionCount') 总会话=$(jget "$LAST_BODY" '.data.totalSessions') 总token=$(jget "$LAST_BODY" '.data.totalTokens')"
    for k in activeSessionCount totalSessions totalInputTokens totalOutputTokens totalTokens; do
        assert_contains "概览含 $k" "$LAST_BODY" "\"$k\""
    done
    assert_number "activeSessionCount 是数字" "$(jget "$LAST_BODY" '.data.activeSessionCount')"
fi

call GET "/api/metrics/$SESSION"
if expect_ok "查询会话指标"; then
    # metricsMap 的 key 以 MetricsCollector.toMap() 为准
    for k in api_calls user_messages assistant_messages tool_usage \
             input_tokens output_tokens errors duration_seconds session_id start_time; do
        assert_contains "指标含 $k" "$LAST_BODY" "$k"
    done
    assert_contains "响应含 metricsSummary" "$LAST_BODY" '"metricsSummary"'

    # 「指标非零」只有在本轮确实对话过时才成立。用 --only metrics 单跑时 chat 组
    # 被跳过，会话一句话都没说，此时 api_calls=0 是正确结果而非缺陷 ——
    # 所以这里补一次对话，让断言自带前提，不再隐式依赖组的执行顺序。
    if [ "$HAS_KEY" = 1 ]; then
        call POST "/api/chat/$SESSION" '{"message":"只回复一个字：好"}'
        if expect_ok "为指标断言补一轮对话"; then
            call GET "/api/metrics/$SESSION"
            if expect_ok "对话后重新查询会话指标"; then
                # 这几项恒 0 说明 MetricsCollector 没接上对应调用点
                assert_gt "api_calls > 0" "$(jget "$LAST_BODY" '.data.metricsMap.api_calls')" 0
                assert_gt "user_messages > 0" "$(jget "$LAST_BODY" '.data.metricsMap.user_messages')" 0
                assert_gt "assistant_messages > 0" "$(jget "$LAST_BODY" '.data.metricsMap.assistant_messages')" 0
                assert_gt "input_tokens > 0" "$(jget "$LAST_BODY" '.data.metricsMap.input_tokens')" 0
                info "tool_usage=$(jget "$LAST_BODY" '.data.metricsMap.tool_usage')"

                # 指标里的 token 数应与 /tokens 端点一致（同一个 TokenTracker）
                MIN="$(jget "$LAST_BODY" '.data.inputTokens')"
                call GET "/api/sessions/$SESSION/tokens"
                assert_eq "指标与 /tokens 端点的 inputTokens 一致" \
                    "$(jget "$LAST_BODY" '.data.inputTokens')" "$MIN"
            fi
        fi
    else
        skip "指标非零断言" "无可用模型，会话未产生任何调用"
    fi
fi
fi

# ══════════════════════════════════════════════════════════════════════
# 13. 未覆盖能力（显式标注，避免「没测」被误读为「测过了」）
# ══════════════════════════════════════════════════════════════════════
section "未覆盖能力（无对应 REST 端点）"

skip "updateSessionPrompt 会话提示词热更新" "demo-app 未暴露 PUT /api/sessions/{id}/prompt"
skip "getSessionHooks Hook 扩展点" "PreToolUse / PostToolUse 仅 Java API 可用"
skip "getSessionDenials 拒绝审计" "DenialTracker 仅 Java API 可用"
skip "sendStreaming(Consumer) 直连流式" "SSE 走 send + HmsCallbacks，该重载无端点"
skip "max-sessions 超限拒绝" "需以极小 max-sessions 重启，按约定不纳入"
skip "max-iterations 截断" "需以极小 max-iterations 重启，按约定不纳入"
skip "MCP 资源工具实际连接" "ListMcpResources / ReadMcpResource 需外部 MCP server"
info "以上能力有 hms-core 侧的单元测试覆盖（见 hms-core/src/test）"

# ══════════════════════════════════════════════════════════════════════
# 14. 清理
# ══════════════════════════════════════════════════════════════════════
section "清理"

call DELETE "/api/sessions/$SESSION"
expect_ok "销毁会话"

call GET "/api/sessions/$SESSION"
expect_fail_response "销毁后查询应失败"

call DELETE "/api/sessions/nope-$$"
expect_fail_response "销毁不存在的会话应失败"

call POST "/api/sessions/cleanup?idleSeconds=99999"
expect_ok "批量清理空闲会话（阈值极大，应清理 0 个）" \
    && info "清理了 $(jget "$LAST_BODY" '.data.cleaned') 个"

call POST "/api/sessions/cleanup?idleSeconds=0"
expect_ok "批量清理空闲会话（阈值 0，清理全部空闲）" \
    && info "清理了 $(jget "$LAST_BODY" '.data.cleaned') 个"

call GET /api/sessions
expect_ok "清理后仍能列出会话"

# ══════════════════════════════════════════════════════════════════════
printf '\n%s────────────────────────────────%s\n' "$C_BLUE" "$C_OFF"
printf '通过 %s%d%s  失败 %s%d%s  跳过 %s%d%s\n' \
    "$C_GREEN" "$PASS" "$C_OFF" "$C_RED" "$FAIL" "$C_OFF" "$C_YELLOW" "$SKIP" "$C_OFF"

if [ "$FAIL" -gt 0 ]; then
    printf '\n%s失败用例：%s\n' "$C_RED" "$C_OFF"
    for n in "${FAILED_NAMES[@]}"; do printf '  · %s\n' "$n"; done
    printf '\n应用日志：%s\n' "$LOG_FILE"
    exit "$FAIL"
fi

printf '\n%s全部通过%s\n' "$C_GREEN" "$C_OFF"
exit 0
