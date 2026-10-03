import re,sys
p=sys.argv[1]
x=open(p,encoding='utf-8',errors='replace').read()
for m in re.finditer(r'<node[^>]*?>',x):
    s=m.group(0)
    t=re.search(r'text="([^"]*)"',s).group(1)
    d=re.search(r'content-desc="([^"]*)"',s).group(1)
    b=re.search(r'bounds="([^"]*)"',s).group(1)
    e=re.search(r'enabled="([^"]*)"',s).group(1)
    ck=re.search(r'clickable="([^"]*)"',s).group(1)
    if t.strip() or d.strip() or ck=='true':
        print(f"{t[:60]!r} desc={d[:30]!r} click={ck} en={e} {b}")
