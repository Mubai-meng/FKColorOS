#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
清理 LSPosed 数据库里的 `com.FuckColorOS` 旧包名脏条目。

背景（详见 docs/log_reports/LSPosed日志分析报告_20261122_1854.md 第六节）：
旧包名 `com.FuckColorOS` 已卸载，但 LSPosed 的 modules_config.db 里还留着
`modules` 表 1 条 + `scope` 表 10 条记录。运行时 scopes.txt = 0、日志 = 0 行
⇒ 纯脏数据，无危害，只是让模块列表不干净。

⚠️ 关于表结构（踩过的坑）：
  docs/log_reports/db_scope_20261122.txt 的 dump 表头是
  `[app_pkg_name, module_pkg_name, user_id]`，那是**导出时与 modules 做了 JOIN**
  的投影结果，不是 scope 的真实列。LSPosed 原生 schema 是：

      modules(mid INTEGER PRIMARY KEY, module_pkg_name TEXT, apk_path TEXT, ...)
      scope  (mid INTEGER, app_pkg_name TEXT, user_id INTEGER, PRIMARY KEY(mid, app_pkg_name, user_id))

  即 scope **没有** module_pkg_name，只能靠 mid 关联。所以本脚本先 `PRAGMA table_info`
  探测列名，再决定按哪条路径删（同时兼容未来/旧版可能的差异），不硬编码。

⚠️ 关于 WAL：
  若库为 WAL 模式，只 pull `modules_config.db` 会丢掉尚未 checkpoint 的事务，
  再 push 回去等于把模块配置回滚。因此本地先 `wal_checkpoint(TRUNCATE)` +
  `journal_mode=DELETE` 把 WAL 合并进主文件，并把设备上的 -wal/-shm 一并清掉。

用法（需设备已连 adb 且已 root，su 可用）：
    python clean_lsposed_db.py            # 只体检，不改动
    python clean_lsposed_db.py --apply    # 真正删除并推回设备

环境变量：ADB（默认 adb）、SQLITE3（默认 sqlite3）
"""
import os
import shutil
import subprocess
import sys

DB_REMOTE = "/data/adb/lspd/config/modules_config.db"
PKG = "com.FuckColorOS"
KEEP = "com.fr.colorostools"

ADB = os.environ.get("ADB", "adb")
SQLITE3 = os.environ.get("SQLITE3", "sqlite3")


def sh(cmd, check=True, quiet=False):
    """执行 shell 命令，返回 stdout（strip 过）。"""
    if not quiet:
        print("$ %s" % cmd)
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    out = (r.stdout or "").strip()
    err = (r.stderr or "").strip()
    if check and r.returncode != 0:
        raise RuntimeError("命令失败(%d): %s\nstdout: %s\nstderr: %s"
                           % (r.returncode, cmd, out, err))
    if err and not quiet:
        print("  [stderr] %s" % err)
    return out


def q(db, sql):
    """在本地库上执行 SQL，返回单单元格/多行输出（不抛错）。"""
    r = subprocess.run([SQLITE3, "-batch", db, sql],
                       capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if r.returncode != 0:
        raise RuntimeError("sqlite3 失败: %s\n%s" % (sql, r.stderr))
    return (r.stdout or "").strip()


def exec_sql(db, statements):
    """
    在**同一个** sqlite3 进程里顺序执行多条语句。

    ⚠️ 必须一次调用执行：q() 每次都新起一个进程，`BEGIN` 会随进程退出而失效，
    后续 `COMMIT` 直接报 `cannot commit - no transaction is active`
    （等于完全没有事务保护）。
    """
    script = ";\n".join(s.strip().rstrip(";") for s in statements) + ";"
    r = subprocess.run([SQLITE3, "-batch", db],
                       input=script, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if r.returncode != 0:
        raise RuntimeError("sqlite3 执行失败:\n%s\n%s" % (script, r.stderr))
    return (r.stdout or "").strip()


def rows(db, sql):
    """返回 '|' 分隔的多行结果（每行一个 list）。"""
    s = q(db, sql)
    if not s:
        return []
    return [line.split("|") for line in s.splitlines()]


def cols(db, table):
    """取表的列名列表。"""
    out = rows(db, "PRAGMA table_info(%s);" % table)
    return [r[1] for r in out if len(r) > 1]


def purge(db):
    """
    在本地库上执行清理，返回 (ok, dirty_mids, removed_scope, removed_modules)。
    只做纯本地 SQL，方便脱离 adb 单独测试。
    """
    # ---- 1. 表清单 ----
    tables = [r[0] for r in rows(
        db, "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%';")]
    print("\n表：%s" % ", ".join(tables))

    # ---- 2. 定位脏条目 ----
    if "modules" not in tables:
        raise RuntimeError("库里没有 modules 表，路径/版本不对")
    mcols = cols(db, "modules")
    print("modules 列：%s" % ", ".join(mcols))
    pkg_col = "module_pkg_name" if "module_pkg_name" in mcols else None
    if not pkg_col:
        raise RuntimeError("modules 表没有 module_pkg_name 列：%s" % mcols)

    mids = [r[0] for r in rows(
        db, "SELECT mid FROM modules WHERE %s='%s';" % (pkg_col, PKG))]
    keep_mids = [r[0] for r in rows(
        db, "SELECT mid FROM modules WHERE %s='%s';" % (pkg_col, KEEP))]

    print("\n待清理：")
    print("  modules 表 : %d 条（mid=%s）" % (len(mids), ",".join(mids) or "无"))
    print("  保留的新包名 : %d 条（mid=%s）" % (len(keep_mids), ",".join(keep_mids) or "无"))

    if not mids:
        print("✅ 没有 %s 的 modules 记录，无需清理" % PKG)
        # 仍可能 scope 有孤儿（mid 已不在 modules），一并报出来
        orphan = 0
        if "scope" in tables:
            scols = cols(db, "scope")
            if "mid" in scols:
                orphan = int(q(db, "SELECT COUNT(*) FROM scope WHERE mid NOT IN "
                                   "(SELECT mid FROM modules);") or 0)
        if orphan:
            print("   （另有 %d 条 scope 孤儿记录，mid 已不存在）" % orphan)
        return True, [], 0, 0

    mid_list = ",".join(mids)

    # ---- 3. scope 及其它含 mid 的从表 ----
    removed = {}
    for t in tables:
        if t == "modules":
            continue
        tc = cols(db, t)
        if "mid" not in tc:
            continue
        if "module_pkg_name" in tc:  # 防御：某些版本可能冗余存了包名
            n = int(q(db, "SELECT COUNT(*) FROM %s WHERE mid IN (%s) "
                          "OR module_pkg_name='%s';" % (t, mid_list, PKG)) or 0)
            before = int(q(db, "SELECT COUNT(*) FROM %s;" % t) or 0)
            removed[t] = (n, before)
        else:
            n = int(q(db, "SELECT COUNT(*) FROM %s WHERE mid IN (%s);"
                      % (t, mid_list)) or 0)
            before = int(q(db, "SELECT COUNT(*) FROM %s;" % t) or 0)
            removed[t] = (n, before)
        print("  %-8s : %d 条（总 %d）" % (t, removed[t][0], removed[t][1]))

    # 明细（scope 专用，方便对账 10 条）
    if "scope" in tables:
        print("\nscope 表该条目（应为 10 条）：")
        scols = cols(db, "scope")
        if "module_pkg_name" in scols:
            detail = q(db, "SELECT app_pkg_name FROM scope WHERE mid IN (%s) "
                           "OR module_pkg_name='%s' ORDER BY app_pkg_name;"
                        % (mid_list, PKG))
        else:
            detail = q(db, "SELECT app_pkg_name FROM scope WHERE mid IN (%s) "
                           "ORDER BY app_pkg_name;" % mid_list)
        print(detail or "(无)")

    # ---- 4. 执行删除（单进程内事务：任一语句失败则整体回滚） ----
    stmts = ["BEGIN"]
    for t, (n, _b) in removed.items():
        if n <= 0:
            continue
        tc = cols(db, t)
        if "module_pkg_name" in tc:
            stmts.append("DELETE FROM %s WHERE mid IN (%s) OR module_pkg_name='%s'"
                         % (t, mid_list, PKG))
        else:
            stmts.append("DELETE FROM %s WHERE mid IN (%s)" % (t, mid_list))
    stmts.append("DELETE FROM modules WHERE mid IN (%s)" % mid_list)
    exec_sql(db, stmts + ["COMMIT"])

    # ---- 5. 复核 ----
    left_mod = int(q(db, "SELECT COUNT(*) FROM modules WHERE %s='%s';"
                      % (pkg_col, PKG)) or 0)
    left_scope = 0
    if "scope" in tables:
        scols = cols(db, "scope")
        if "mid" in scols:
            left_scope = int(q(db, "SELECT COUNT(*) FROM scope WHERE mid IN (%s);"
                               % mid_list) or 0)
    keep_after = int(q(db, "SELECT COUNT(*) FROM modules WHERE %s='%s';"
                        % (pkg_col, KEEP)) or 0)
    s1 = "no" not in q(db, "PRAGMA integrity_check;").lower()

    print("\n删除后复核：")
    print("  modules 脏条目 : %d（应 0）" % left_mod)
    print("  scope   脏条目 : %d（应 0）" % left_scope)
    print("  新包名保留     : %d（应 %d，不能误删）" % (keep_after, len(keep_mids)))
    print("  integrity_check: %s" % ("ok" if s1 else "FAILED"))

    ok = (left_mod == 0 and left_scope == 0
          and keep_after == len(keep_mids) and s1)
    return ok, mids, removed.get("scope", (0, 0))[0], left_mod


def main():
    apply = "--apply" in sys.argv

    # ---------- 0. 工具与设备 ----------
    if not shutil.which(SQLITE3):
        print("❌ 找不到 sqlite3（%s）。可用 SQLITE3=<路径> 指定。" % SQLITE3)
        print("   本机一般在 <android-sdk>/platform-tools/sqlite3")
        return 2

    dev = sh("%s devices" % ADB, check=False)
    lines = [l for l in dev.splitlines()[1:] if l.strip()]
    if not lines:
        print("❌ 没有已连接的 adb 设备。请先开 USB 调试（或无线调试）并授权本机。")
        print("   当前 adb devices 输出：\n%s" % (dev or "(空)"))
        return 2
    print("✅ 设备：%s" % lines[0])

    # ---------- 1. 定位库 ----------
    ls = sh("%s shell su -c 'ls -l %s*'" % (ADB, DB_REMOTE), check=False)
    if DB_REMOTE not in ls:
        print("❌ 找不到 %s（LSPosed 未安装 / 路径变了 / 无 root）" % DB_REMOTE)
        print(ls or "(空)")
        return 2
    print("✅ 远端库：\n%s" % ls)
    wal_present = ("-wal" in ls) or ("-shm" in ls)

    work = os.path.join(os.getcwd(), "_lsposed_clean")
    os.makedirs(work, exist_ok=True)
    local = os.path.join(work, "modules_config.db")
    for f in (local, local + "-wal", local + "-shm"):
        if os.path.exists(f):
            os.remove(f)

    # ---------- 2. 设备内备份 ----------
    sh("%s shell su -c 'cd %s && cp -a modules_config.db modules_config.db.bak_$(date +%%s)'"
       % (ADB, os.path.dirname(DB_REMOTE)))
    print("✅ 设备内已备份（modules_config.db.bak_<时间戳>）")

    # ---------- 3. 拉取（含 WAL 旁文件） ----------
    for suffix in ("", "-wal", "-shm"):
        src = DB_REMOTE + suffix
        if suffix and not sh("%s shell su -c 'test -f %s && echo OK'" % (ADB, src),
                             check=False):
            continue
        # -wal/-shm 属主可能不是 shell 可读，统一走 su cat 落盘
        sh("%s shell su -c 'cat %s' > '%s'" % (ADB, src, local + suffix))
    if not os.path.exists(local) or os.path.getsize(local) == 0:
        print("❌ 拉取失败（本地文件为空）")
        return 2
    print("✅ 已拉取 %d 字节" % os.path.getsize(local))

    # ---------- 4. WAL 合并 ----------
    mode = q(local, "PRAGMA journal_mode;")
    print("journal_mode: %s" % mode)
    if mode.lower() == "wal":
        print("-> wal_checkpoint(TRUNCATE): %s" % q(local, "PRAGMA wal_checkpoint(TRUNCATE);"))
        print("-> journal_mode=DELETE: %s" % q(local, "PRAGMA journal_mode=DELETE;"))
        for f in (local + "-wal", local + "-shm"):
            if os.path.exists(f):
                os.remove(f)
        print("✅ WAL 已合并进主文件，旁文件已删")

    shutil.copy2(local, local + ".orig")
    print("✅ 本地基线副本：%s.orig" % local)

    # ---------- 5. 体检 / 删除 ----------
    if not apply:
        tables = [r[0] for r in rows(
            local, "SELECT name FROM sqlite_master WHERE type='table' "
                   "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%';")]
        print("\n表：%s" % ", ".join(tables))
        mcols = cols(local, "modules")
        print("modules 列：%s" % ", ".join(mcols))
        n_mod = int(q(local, "SELECT COUNT(*) FROM modules WHERE module_pkg_name='%s';" % PKG) or 0)
        mids = [r[0] for r in rows(local, "SELECT mid FROM modules WHERE module_pkg_name='%s';" % PKG)]
        n_scope = 0
        if "scope" in tables and mids:
            n_scope = int(q(local, "SELECT COUNT(*) FROM scope WHERE mid IN (%s);"
                            % ",".join(mids)) or 0)
        print("\n待清理：modules=%d  scope=%d  (mid=%s)" % (n_mod, n_scope, ",".join(mids) or "无"))
        print(q(local, "SELECT m.mid, m.module_pkg_name, m.apk_path FROM modules m "
                       "WHERE m.module_pkg_name='%s';" % PKG) or "(无)")
        print("\n以上为体检结果。要真正删除请加 --apply")
        return 0

    ok, mids, n_scope, left_mod = purge(local)
    if not ok:
        print("❌ 复核未通过，**未推回设备**。基线仍在 %s.orig" % local)
        return 1
    print("✅ 本地库清理完成并通过校验")

    # ---------- 6. 推回 ----------
    sh("%s push '%s' %s" % (ADB, local, DB_REMOTE))
    sh("%s shell su -c 'chown root:root %s && chmod 600 %s'" % (ADB, DB_REMOTE, DB_REMOTE))
    if wal_present:
        sh("%s shell su -c 'rm -f %s-wal %s-shm'" % (ADB, DB_REMOTE, DB_REMOTE))
        print("✅ 已清掉远端 -wal/-shm（避免旧 WAL 覆盖新库）")
    print("✅ 已推回设备并修正属主 600 root:root")

    # ---------- 7. 远端复核 ----------
    back = os.path.join(work, "modules_config.db.verify")
    if os.path.exists(back):
        os.remove(back)
    sh("%s shell su -c 'cat %s' > '%s'" % (ADB, DB_REMOTE, back))
    n = int(q(back, "SELECT COUNT(*) FROM modules WHERE module_pkg_name='%s';" % PKG) or -1)
    k = int(q(back, "SELECT COUNT(*) FROM modules WHERE module_pkg_name='%s';" % KEEP) or -1)
    print("远端复核：脏条目=%d（应 0） 新包名=%d（应 >0）" % (n, k))
    if n != 0 or k <= 0:
        print("❌ 远端校验不符！可用设备内 xxxx.bak_<时间戳> 回滚")
        return 1

    print("\n🎉 清理完成。")
    print("⚠️ 请**立即重启手机**让 lspd 重读该库（否则内存态可能把旧记录写回）。")
    print("   重启后进 LSPosed 管理器，模块列表里不应再有 %s" % PKG)
    return 0


if __name__ == "__main__":
    sys.exit(main())