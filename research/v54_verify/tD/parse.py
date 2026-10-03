import re,sys
x=open('/tmp/uix.xml',encoding='utf-8').read()
for m in re.finditer(r'<node[^>]*>',x):
    s=m.group(0)
    def g(a):
        mm=re.search(a+r'="([^"]*)"',s); return mm.group(1) if mm else ''
    t=g('text'); d=g('content-desc')
    if not t and not d: continue
    print(g('class').split('.')[-1],'|',t or d,'|',g('bounds'))
