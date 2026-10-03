import subprocess,sys,os
ADB=r'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
env=dict(os.environ); env['MSYS_NO_PATHCONV']='1'
subprocess.run([ADB,'-s','emulator-5560','shell','input','text',sys.argv[1]],env=env)
