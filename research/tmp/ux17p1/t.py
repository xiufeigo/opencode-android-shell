import sys; sys.path.insert(0,r'D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux17p1'); import cdp
print('URL', cdp.ws_url()[0][:70])
print('VAL', cdp.evaluate('1+1'))
