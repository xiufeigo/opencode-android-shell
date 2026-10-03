import re,subprocess,sys
ADB="C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
S="emulator-5558"
subprocess.run([ADB,"-s",S,"shell","uiautomator","dump","/sdcard/fi.xml"],capture_output=True)
subprocess.run([ADB,"-s",S,"pull","/sdcard/fi.xml","C:/Windows/Temp/fi.xml"],capture_output=True)
x=open("C:/Windows/Temp/fi.xml",encoding="utf-8").read()
c=[]
for m in re.finditer(r'<node[^>]*>',x):
    s=m.group(0)
    if not re.search(r'class="[^"]*EditText"',s): continue
    b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',s)
    if b: c.append(tuple(map(int,b.groups())))
if not c: sys.exit("no EditText")
x1,y1,x2,y2=c[-1]
cx,cy=(x1+x2)//2,(y1+y2)//2
subprocess.run([ADB,"-s",S,"shell","input","tap",str(cx),str(cy)],capture_output=True)
print("tapped composer",cx,cy)
