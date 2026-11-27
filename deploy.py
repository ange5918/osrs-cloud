# -*- coding: utf-8 -*-
"""site 项目一键打包上传(纯源码容器): sources/*.bsh.java -> src/*.java -> manifest.json -> git commit/push gh-pages。

不再编译 dex：悬浮窗下载 .java 到本地, 游戏内 BeanShell 容器直接执行并调用 ghost api。
用法(在本文件所在目录):
    python deploy.py                 # 构建 + 提交 + 推送
    python deploy.py --no-push       # 只构建 + 提交, 不推送
    python deploy.py -m "修复攻击"   # 自定义提交信息
"""
import os, sys, subprocess, hashlib, json

HERE = os.path.dirname(os.path.abspath(__file__))
WS = os.path.abspath(os.path.join(HERE, "..", "..", ".."))          # 工作区根
BUILD_SCRIPT = os.path.join(WS, "tools", "cloud", "build_myplugin.py")
MANIFEST = os.path.join(HERE, "manifest.json")

args = sys.argv[1:]
no_push = "--no-push" in args
msg = "myplugin: one-click deploy"
if "-m" in args:
    i = args.index("-m")
    if i + 1 < len(args):
        msg = args[i + 1]

def run(cmd, cwd=None, check=True):
    print("  $", " ".join(str(x) for x in cmd))
    r = subprocess.run(cmd, cwd=cwd)
    if check and r.returncode != 0:
        raise SystemExit("FAILED(%d): %s" % (r.returncode, " ".join(str(x) for x in cmd)))
    return r

print("=== [1/3] 构建源码脚本 + 更新 manifest ===")
run([sys.executable, BUILD_SCRIPT], cwd=WS)

print("=== [2/3] 校验源码条目 + git commit ===")
mf = json.load(open(MANIFEST, encoding="utf-8"))
for s in mf.get("scripts", []):
    rel = s.get("path", "")
    fp = os.path.join(HERE, rel.replace("/", os.sep))
    if os.path.exists(fp):
        data = open(fp, "rb").read()
        print("[deploy] %s size=%d sha256=%s" % (rel, len(data), hashlib.sha256(data).hexdigest()))
run(["git", "add", "-A"], cwd=HERE)
st = subprocess.run(["git", "status", "--porcelain"], cwd=HERE, capture_output=True, text=True).stdout
if st.strip():
    print("[deploy] changes:\n" + st)
    run(["git", "commit", "-m", msg], cwd=HERE)
else:
    print("[deploy] no changes to commit")

if not no_push:
    print("=== [3/3] push -> origin gh-pages ===")
    run(["git", "push", "-f", "origin", "gh-pages"], cwd=HERE)
    print("[deploy] done: https://ange5918.github.io/osrs-cloud/")
else:
    print("[deploy] skipped push (--no-push)")
