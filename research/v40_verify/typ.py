import subprocess, sys, time

C = {'a':29,'b':30,'c':31,'d':32,'e':33,'f':34,'g':35,'h':36,'i':37,'j':38,'k':39,
     'l':40,'m':41,'n':42,'o':43,'p':44,'q':45,'r':46,'s':47,'t':48,'u':49,'v':50,
     'w':51,'x':52,'y':53,'z':54,'-':69,'.':56,'/':76,
     '0':7,'1':8,'2':9,'3':10,'4':11,'5':12,'6':13,'7':14,'8':15,'9':16}
SERIAL = 'emulator-5556'
ADB = r'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'


def keyevent(codes, chunk=6):
    codes = [str(c) for c in codes]
    for i in range(0, len(codes), chunk):
        subprocess.run([ADB, '-s', SERIAL, 'shell', 'input', 'keyevent'] + codes[i:i + chunk],
                       capture_output=True)
        time.sleep(0.25)


def type_path(p):
    keyevent([C[c] for c in p if c in C])


def clear(n=200):
    keyevent([123] + [67] * n)
