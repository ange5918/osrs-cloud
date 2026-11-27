# -*- coding: utf-8 -*-
"""site 项目一键打包上传: sources/MyPlugin.java -> javac -> d8 -> dex -> manifest.json -> git commit/push gh-pages。

用法(在本文件所在目录):
    python deploy.py                 # 构建 + 提交 + 推送
    python deploy.py --no-push       # 只构建 + 提交, 不推送
    python deploy.py -m "修复攻击"   # 自定义提交信息
等价于旧 ghost-plugin/pom.xml 的 mvn package, 但不依赖 Maven。
"""
import os, sys, subprocess, hashlib

HERE = os.path.dirname(os.path.abspath(__file__))
WS = os.path.abspath(os.path.join(HERE, "..", "..", ".."))          # 工作区根
BUILD_SCRIPT = os.path.join(WS, "tools", "cloud", "build_myplugin.py")
DEX = os.path.join(HERE, "dex", "myplugin.dex")

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

print("=== [1/3] 编译 dex + 更新 manifest ===")
run([sys.executable, BUILD_SCRIPT], cwd=WS)

print("=== [2/3] 校验 dex + git commit ===")
data = open(DEX, "rb").read()
print("[deploy] dex size=%d sha256=%s" % (len(data), hashlib.sha256(data).hexdigest()))
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
