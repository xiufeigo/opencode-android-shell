# oc_wt_probe.py - read-only: dump the opencode `worktree` table to recover the
# deleted fixture's original row (project id, commit, branch).
import sqlite3
import shutil
import os
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

src = '/home/xiufeigo/.local/share/opencode/opencode.db'
dst = '/tmp/oc_wt.db'
shutil.copy(src, dst)
for ext in ('-wal', '-shm'):
    p = src + ext
    if os.path.exists(p):
        shutil.copy(p, dst + ext)

c = sqlite3.connect(dst)
for t in ('worktree', 'workspace'):
    cols = [r[1] for r in c.execute('PRAGMA table_info(%s)' % t)]
    print('===', t, '===')
    print('cols:', cols)
    for r in c.execute('select * from %s' % t):
        print('  ', r)
