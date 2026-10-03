import subprocess, sys
ADB=r"C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
S="emulator-5560"
PLAIN={'.':'KEYCODE_PERIOD',',':'KEYCODE_COMMA','/':'KEYCODE_SLASH','-':'KEYCODE_MINUS',
 '=':'KEYCODE_EQUALS','@':'KEYCODE_AT',"'":'KEYCODE_APOSTROPHE','[':'KEYCODE_LEFT_BRACKET',
 ']':'KEYCODE_RIGHT_BRACKET','`':'KEYCODE_GRAVE'}
SHIFT={':':['KEYCODE_SEMICOLON'],'_':['KEYCODE_MINUS'],'~':['KEYCODE_GRAVE'],
 '+':['KEYCODE_EQUALS'],'?':['KEYCODE_SLASH'],'#':['KEYCODE_3'],'$':['KEYCODE_4'],
 '%':['KEYCODE_5'],'&':['KEYCODE_7'],'*':['KEYCODE_8'],'(':['KEYCODE_9'],')':['KEYCODE_0'],
 '!':['KEYCODE_1']}
def run(args): subprocess.run([ADB,"-s",S,"shell","input"]+args,check=True)
for c in sys.argv[1]:
    if c==' ': run(["keyevent","KEYCODE_SPACE"])
    elif c.isdigit(): run(["keyevent","KEYCODE_"+c])
    elif c.islower(): run(["keyevent","KEYCODE_"+c.upper()])
    elif c.isupper(): run(["keycombination","KEYCODE_SHIFT_LEFT","KEYCODE_"+c])
    elif c in PLAIN: run(["keyevent",PLAIN[c]])
    elif c in SHIFT: run(["keycombination","KEYCODE_SHIFT_LEFT"]+SHIFT[c])
    else: print("UNSUPPORTED",repr(c)); sys.exit(1)
print("typed",repr(sys.argv[1]))
