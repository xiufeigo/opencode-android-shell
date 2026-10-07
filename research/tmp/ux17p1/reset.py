import sys; sys.path.insert(0,r'D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux17p1'); import cdp
print(cdp.evaluate("localStorage.clear(); 'cleared='+localStorage.length"))
