# oc_db_probe.py - read-only probe of the opencode sqlite db for the deleted
# worktree's original record (used to restore the fixture I destroyed).
import sqlite3
import shutil
import os
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

src = '/home/xiufeigo/.local/share/opencode/opencode.db'
dst = '/tmp/oc_probe.db'
shutil.copy(src, dst)
for ext in ('-wal', '-shm'):
    p = src + ext
    if os.path.exists(p):
        shutil.copy(p, dst + ext)

c = sqlite3.connect(dst)
tabs = [r[0] for r in c.execute("select name from sqlite_master where type='table'")]
print('tables:', tabs)
for t in tabs:
    try:
        cols = [r[1] for r in c.execute('PRAGMA table_info(%s)' % t)]
        rows = list(c.execute('select * from %s' % t))
    except Exception as e:
        print('  skip', t, e)
        continue
    for r in rows:
        s = str(r)
        if 'cosmic' in s:
            print('TABLE', t)
            print('  COLS', cols)
            print('  ROW ', s[:800])
