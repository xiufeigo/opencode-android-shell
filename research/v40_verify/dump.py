import re,sys,subprocess,os
ADB=r'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
S='emulator-5558'
subprocess.run([ADB,'-s',S,'shell','uiautomator','dump','/sdcard/d.xml'],capture_output=True)
subprocess.run([ADB,'-s',S,'pull','/sdcard/d.xml',os.path.join(os.path.dirname(__file__),'tC','d.xml')],capture_output=True)
d=open(os.path.join(os.path.dirname(__file__),'tC','d.xml'),encoding='utf-8').read()
f=sys.argv[1] if len(sys.argv)>1 else ''
for m in re.finditer(r'<node[^>]*>',d):
    s=m.group(0)
    t=re.search(r'text="([^"]*)"',s).group(1)
    de=re.search(r'content-desc="([^"]*)"',s).group(1)
    b=re.search(r'bounds="([^"]*)"',s).group(1)
    v=t or de
    if v and (not f or f in v): print(v,'|',b)
