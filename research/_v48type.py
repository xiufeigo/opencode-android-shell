import sys,subprocess,os
adb=r"C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
s=os.environ.get("V48TEXT","")
if s:
    subprocess.run([adb,"-s","emulator-5560","shell","input","text",s])
