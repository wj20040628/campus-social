# -*- coding: utf-8 -*-
"""
部署后一键冒烟(2026-10-25 固化,源自 2026-10-25 全站测试期间的手写脚本)。

为什么需要:本地/生产重启后,健康探针 200 只证明「有进程在 8080」,
不证明「新代码在跑」——实测踩过 taskkill 只杀掉 mvn 包装进程、java
子进程占着端口继续跑旧代码的坑(配额检查"失效"假象的根因)。
本脚本把核心链路真打一遍,30 秒内给出 PASS/FAIL。

用法:
  python scripts/smoke.py                       # 默认 http://127.0.0.1:8080
  python scripts/smoke.py http://120.26.4.66:8080
  环境变量 SMOKE_USER / SMOKE_PASS 覆盖测试账号(默认本地 e2e1024)。

断言项(每项对应一个曾经真实出过事的故事,别随手删):
  1 login                登录拿到 token(账号体系活着)
  2 post-create          普通发帖成功(写链路活着)
  3 guest-post-detail    游客读帖子详情 200(分享落地页放行活着)
  4 guest-comments       游客读评论 200(评论读链路活着)
  5 guest-write-blocked  游客点赞 401(写操作没被误放行)
  6 discover-buddy       搭子接口 200(新端点活着)
  7 mkt-quota-blocked    集市第 2 条在投被 1065 拦(配额检查真在跑,
                         这一项红了 = 后端跑的是旧代码,见上文的坑)
  8 cleanup              冒烟帖与活动已清理(不留脏数据)

退出码 0=全绿,1=有失败(可直接接部署脚本/CI)。
"""
import json
import os
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else os.environ.get('SMOKE_BASE', 'http://127.0.0.1:8080')
USER = os.environ.get('SMOKE_USER', 'e2e1024@campus.test')
PASS = os.environ.get('SMOKE_PASS', 'E2eTest#1024')

ok_count = 0
failures = []
skipped = []


def call(method, path, token=None, body=None, raw_token=None):
    req = urllib.request.Request(BASE + path, method=method)
    t = token if token is not None else raw_token
    if t:
        req.add_header('Authorization', 'Bearer ' + t)
    data = None
    if body is not None:
        req.add_header('Content-Type', 'application/json')
        data = json.dumps(body).encode('utf-8')
    try:
        with urllib.request.urlopen(req, data=data, timeout=15) as r:
            return r.status, json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode('utf-8'))
        except Exception:
            return e.code, {}


def check(name, cond, detail=''):
    global ok_count
    mark = 'PASS' if cond else 'FAIL'
    print(f'{mark} {name} {detail}')
    if cond:
        ok_count += 1
    else:
        failures.append(name)


def skip(name, detail=''):
    """不可判定(如撞上频率限流),不算失败——但必须显式说出来,不能静默"""
    print(f'SKIP {name} {detail}')
    skipped.append(name)


print(f'== 冒烟目标: {BASE}')

# 1. 登录
st, r = call('POST', '/auth/login', body={'username': USER, 'password': PASS})
tok = r.get('data', {}).get('accessToken', '')
check('login', st == 200 and tok, f'status={st}')

pid = None
if tok:
    # 2. 普通发帖(匿名发布已下线,冒烟只验证正常写链路)
    st, r = call('POST', '/post', tok, {'content': 'smoke 冒烟帖(即删)'})
    pid = r.get('data', {}).get('postId')
    check('post-create', st == 200 and pid, f'status={st} pid={pid}')

    if pid:
        # 3. 游客读详情
        st, _ = call('GET', f'/post/{pid}')
        check('guest-post-detail', st == 200, f'status={st}')

        # 4. 游客读评论
        st, _ = call('GET', f'/post/{pid}/comments')
        check('guest-comments', st == 200, f'status={st}')

    # 5. 游客写仍 401
    if pid:
        st, _ = call('POST', f'/post/{pid}/like')
        check('guest-write-blocked', st == 401, f'status={st}')

    # 6. 搭子接口
    st, r = call('GET', '/discover/buddy?size=6', tok)
    check('discover-buddy', st == 200 and isinstance(r.get('data', {}).get('users'), list),
          f'status={st}')

    # 7. 集市在投配额拦截(新代码探针:跑旧代码此行必 FAIL)
    # expireTime 必须 now+2h:写死远期会先撞 12 小时投放上限(1052),
    # 且业务异常的 HTTP 也是 200——判断必须看业务码,不能只看 status。
    import time
    expire = time.strftime('%Y-%m-%dT%H:%M:%S', time.localtime(time.time() + 2 * 3600))
    mkt = {'category': '游戏组队', 'title': 'smoke 配额探针(即删)',
           'content': '冒烟自动创建,发完即删', 'expireTime': expire}
    st1, r1 = call('POST', '/mkt', tok, mkt)
    if r1.get('code') == 200:
        mid = r1.get('data')
        st2, r2 = call('POST', '/mkt', tok, mkt)
        if r2.get('code') == 1065:
            check('mkt-quota-blocked', True, '第2条被 1065 拦')
        elif r2.get('code') == 429:
            # 频率限流(5 分钟 3 条)在配额之前;连续两轮冒烟会撞上。
            # 429 证明不了配额,但也说明限流活着——显式 SKIP,提示稍后重跑。
            skip('mkt-quota-blocked', '撞发布频率限流(429),5 分钟后再跑本脚本可测配额')
        else:
            check('mkt-quota-blocked', False, f'第2条 code={r2.get("code")}')
        if mid:
            call('DELETE', f'/mkt/{mid}', tok)
    elif r1.get('code') == 1065:
        # 存量在投已满(真实数据/上次冒烟残留):配额在拦,同样算生效
        check('mkt-quota-blocked', True, '第1条即被 1065 拦(存量已满,配额生效)')
    elif r1.get('code') == 429:
        skip('mkt-quota-blocked', '撞发布频率限流(429),5 分钟后再跑本脚本可测配额')
    else:
        check('mkt-quota-blocked', False,
              f'第1条意外失败 code={r1.get("code")} msg={r1.get("message")}')

# 8. 清理
if pid and tok:
    st, _ = call('DELETE', f'/post/{pid}', tok)
    check('cleanup', st == 200, f'status={st}')

print()
print(f'== 冒烟结果: {ok_count} PASS, {len(failures)} FAIL'
      + (f', {len(skipped)} SKIP' if skipped else '')
      + (f' {failures}' if failures else ''))
sys.exit(0 if not failures else 1)
