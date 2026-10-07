#!/usr/bin/env python3
"""UX16-P3 smoke driver: CDP-backed UI actions + probes.

Usage:
  python cli.py list                      -> dump clickable rows with coords
  python cli.py click "Text Here"         -> click element containing that text
  python cli.py clickcss "SELECTOR" [n]   -> click nth match of selector
  python cli.py eval file.js              -> run js file through raw()
  python cli.py vp                       -> viewport / colorScheme snapshot
"""
import sys, json, io, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cdp

def jrun(js, timeout=30):
    out = cdp.raw(js, timeout)
    if isinstance(out, str):
        try: out = json.loads(out)
        except Exception: pass
    return out

LIST_JS = r"""
var sel='a,li,button,input,textarea,[role="row"],[role="option"],[role="button"],[data-testid],[tabindex]';
var out=Array.prototype.slice.call(document.querySelectorAll(sel),0,80).map(function(e,i){
  var r=e.getBoundingClientRect();
  var t=(e.innerText||e.value||e.textContent||'').replace(/\s+/g,' ').trim().slice(0,48);
  var tag=e.tagName.toLowerCase();
  return {i:i,tag:tag,txt:t,x:Math.round(r.x+r.width/2),y:Math.round(r.y+r.height/2),
          w:Math.round(r.width),h:Math.round(r.height),
          vis:r.width>0&&r.height>0};
}).filter(function(o){return o.vis && o.txt;});
return {url:location.href, scheme:document.documentElement.dataset.colorScheme||null, items:out};
"""

def click_text(text):
    js = """
var want = %s;
var all = Array.prototype.slice.call(document.querySelectorAll('a,button,li,[role="button"],[role="option"],[role="row"],[role="menuitem"],div,span'));
var cands = all.filter(function(e){
  var r=e.getBoundingClientRect(); if(!(r.width>0&&r.height>0)) return false;
  var t=(e.innerText||e.textContent||'').replace(/\s+/g,' ').trim();
  return t===want || t.indexOf(want)===0;
});
// prefer smallest (most specific) element
cands.sort(function(a,b){var ra=a.getBoundingClientRect(),rb=b.getBoundingClientRect();return (ra.width*ra.height)-(rb.width*rb.height);});
if(!cands.length) return {ok:false, why:'no match for '+want};
var e=cands[0];
e.scrollIntoView({block:'center',inline:'center'});
var r=e.getBoundingClientRect();
var cx=Math.round(r.x+r.width/2), cy=Math.round(r.y+r.height/2);
return {ok:true, txt:(e.innerText||'').replace(/\s+/g,' ').trim().slice(0,60),
        cx:cx, cy:cy, top:Math.round(r.top), left:Math.round(r.left)};
""" % json.dumps(text)
    return jrun(js)

def tap_css(sel, nth=0):
    js = """
var sel=%s, n=%d;
var els=Array.prototype.slice.call(document.querySelectorAll(sel));
var e=els[n];
if(!e) return {ok:false, why:'no '+sel+' #'+n, count:els.length};
var r=e.getBoundingClientRect();
return {ok:true, cx:Math.round(r.x+r.width/2), cy:Math.round(r.y+r.height/2),
        txt:(e.innerText||'').replace(/\s+/g,' ').trim().slice(0,60),
        top:Math.round(r.top), left:Math.round(r.left), w:Math.round(r.width), h:Math.round(r.height)};
""" % (json.dumps(sel), nth)
    return jrun(js)

def set_input(sel, value, nth=0):
    js = """
var sel=%s, val=%s, n=%d;
var e=document.querySelectorAll(sel)[n];
if(!e) return {ok:false, why:'no '+sel+' #'+n};
var proto = e.tagName==='TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
var setter = Object.getOwnPropertyDescriptor(proto,'value').set;
setter.call(e, val);
e.dispatchEvent(new Event('input',{bubbles:true}));
e.dispatchEvent(new Event('change',{bubbles:true}));
return {ok:true, tag:e.tagName, val:e.value};
""" % (json.dumps(sel), json.dumps(value), nth)
    return jrun(js)

def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else 'list'
    if cmd == 'list':
        print(json.dumps(jrun(LIST_JS), ensure_ascii=False, indent=1))
    elif cmd == 'click':
        print(json.dumps(click_text(sys.argv[2]), ensure_ascii=False))
    elif cmd == 'clickcss':
        print(json.dumps(tap_css(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 0), ensure_ascii=False))
    elif cmd == 'setinput':
        print(json.dumps(set_input(sys.argv[2], sys.argv[3], int(sys.argv[4]) if len(sys.argv) > 4 else 0), ensure_ascii=False))
    elif cmd == 'eval':
        raw = io.open(sys.argv[2], encoding='utf-8-sig').read()
        print(json.dumps(jrun(raw, int(sys.argv[3]) if len(sys.argv) > 3 else 30), ensure_ascii=False, indent=1))
    else:
        print('unknown cmd')

if __name__ == '__main__':
    main()