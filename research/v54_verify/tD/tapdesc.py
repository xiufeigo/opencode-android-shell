import re,sys,subprocess,os
subprocess.run(['sh',os.path.join(os.path.dirname(os.path.abspath(__file__)),'ui.sh')],stdout=subprocess.DEVNULL)
x=open('/tmp/uix.xml',encoding='utf-8').read()
for m in re.finditer(r'<node[^>]*>',x):
    s=m.group(0)
    def g(a):
        mm=re.search(a+r'="([^"]*)"',s); return mm.group(1) if mm else ''
    if g('content-desc')==sys.argv[1] or g('text')==sys.argv[1]:
        b=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',g('bounds')).groups()
        cx=(int(b[0])+int(b[2]))//2; cy=(int(b[1])+int(b[3]))//2
        subprocess.run(['sh',os.path.join(os.path.dirname(os.path.abspath(__file__)),'tap.sh'),str(cx),str(cy)])
        print('tapped',sys.argv[1],cx,cy); sys.exit(0)
print('NOT FOUND',sys.argv[1]); sys.exit(1)
