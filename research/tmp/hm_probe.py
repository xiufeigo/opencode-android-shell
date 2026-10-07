import sys, json
sys.path.insert(0, r'D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux17p1')
import cdp
K = 'opencode.global.dat:server'

def p(label, js):
    v = cdp.evaluate(js)
    print(f'--- {label} ---')
    print(v if isinstance(v, str) else json.dumps(v, ensure_ascii=False))
    print()

raw = cdp.evaluate("localStorage.getItem('%s')" % K)
print('=== RAW registry ===')
print(raw if raw else '(null)')
print()

try:
    d = json.loads(raw) if raw else None
except Exception as e:
    d = None
    print('parse error', e)

if isinstance(d, dict):
    print('=== top-level keys ===')
    print(list(d.keys()))
    for k, v in d.items():
        if isinstance(v, list):
            print(f'  {k}: list len={len(v)}')
            for item in v:
                print('     ', json.dumps(item, ensure_ascii=False)[:220])
        else:
            print(f'  {k}: {json.dumps(v, ensure_ascii=False)[:300]}')

print()
p('hidden key present', "JSON.stringify((function(){try{var d=JSON.parse(localStorage.getItem('%s')||'null');return {hasHidden: !!(d&&d.hidden), hidden: d&&d.hidden};}catch(e){return {err:String(e)};}})())" % K)
p('body 4173?', "String((document.body.innerText||'').includes('4173'))")
p('body 4095?', "String((document.body.innerText||'').includes('4095'))")