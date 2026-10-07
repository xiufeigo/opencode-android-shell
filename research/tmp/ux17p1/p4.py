import sys; sys.path.insert(0,r'D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux17p1'); import cdp
K='opencode.global.dat:server'
def ev(js): return cdp.evaluate(js)

# --- A) 首启闸门：键不存在时必须什么都不做 ---
ev("localStorage.removeItem('%s'); 'cleared'" % K)
a = ev("seedPhantomHidden(); localStorage.getItem('%s')===null ? 'ABSENT-OK' : 'VIOLATION'" % K)
print("A gate(key absent)      :", a)

# --- B) 写入路径：模拟"用户已 Add 4095"，seed 应写 hidden[origin] ---
ev("localStorage.setItem('%s', JSON.stringify({list:[{url:'http://127.0.0.1:4095'}],hidden:{}})); 'set'" % K)
b = ev("(function(){ seedPhantomHidden(); var r=JSON.parse(localStorage.getItem('%s')); return JSON.stringify(r); })()" % K)
print("B after seed            :", b)

# --- C) 幂等：再调一次，list 不变、hidden 不增 ---
c = ev("(function(){ var before=localStorage.getItem('%s'); seedPhantomHidden(); var after=localStorage.getItem('%s'); return before===after ? 'IDEMPOTENT-OK' : 'CHANGED'; })()" % (K,K))
print("C idempotent            :", c)

# --- D) 非破坏：list 必须原样保留 ---
d = ev("JSON.stringify(JSON.parse(localStorage.getItem('%s')).list)" % K)
print("D list preserved        :", d)

# --- E) 容错：JSON 损坏时不得崩、不得改写 ---
ev("localStorage.setItem('%s','{corrupt json!!'); 'x'" % K)
e = ev("(function(){ try{ seedPhantomHidden(); return 'RAW='+localStorage.getItem('%s'); }catch(err){ return 'THREW:'+err; } })()" % K)
print("E corrupt json          :", e)
