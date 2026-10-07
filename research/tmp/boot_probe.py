import sys, json
sys.path.insert(0, r'D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux17p1')
import cdp
K = 'opencode.global.dat:server'

def show(label, js):
    v = cdp.evaluate(js)
    print(f'--- {label} ---')
    print(v if not isinstance(v, str) else v[:1500])
    print()

if __name__ == '__main__':
    print('=== registry key (raw) ===')
    show('raw server reg', "localStorage.getItem('%s')" % K)
    print('=== body text ===')
    show('body', "(document.body.innerText||'').replace(/\\s+/g,' ').slice(0,900)")
    print('=== 4173 mentions in body ===')
    show('has4173', "String((document.body.innerText||'').includes('4173'))")