import subprocess, sys, time
KC={' ':62,'-':69,'_':68,'.':56,',':55,':':74,';':55,'/':76,'?':69,'!':8,'(':71,')':72,'"':7,"'":75,'+':81,'=':69,'1':8,'2':9,'3':10,'4':11,'5':12,'6':13,'7':14,'8':15,'9':16,'0':7}
for i,c in enumerate(sys.argv[1]):
    k=KC.get(c)
    if k is None: k=ord(c.lower())-ord('a')+29
    subprocess.run(['adb','-s','emulator-5554','shell','input','keyevent',str(k)])
    time.sleep(0.08)
