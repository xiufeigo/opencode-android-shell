import sys; sys.path.insert(0,r'D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux17p1'); import cdp
K='opencode.global.dat:server'
print("after reload reg:", cdp.evaluate("localStorage.getItem('%s')" % K))
print("body:", (cdp.evaluate("(document.body.innerText||'').replace(/\\s+/g,' ').slice(0,260)") or '')[:260])
