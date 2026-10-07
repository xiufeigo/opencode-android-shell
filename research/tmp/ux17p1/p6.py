import sys,time; sys.path.insert(0,r'D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux17p1'); import cdp
K='opencode.global.dat:server'
# 1) 模拟"用户已 Add 4095"：list 有 4095，hidden 为空
cdp.evaluate("localStorage.setItem('%s', JSON.stringify({list:[{url:'http://127.0.0.1:4095',name:'opencode'}],hidden:{}}))" % K)
print("seeded registry:", cdp.evaluate("localStorage.getItem('%s')" % K))
# 2) 触发文档开始 = 重载
cdp.evaluate("location.reload()")
print("reloading...")
