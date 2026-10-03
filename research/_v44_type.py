import sys, subprocess, time
ADB = r"C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
SERIAL = "emulator-5558"
M = {" ": "KEYCODE_SPACE", "-": "KEYCODE_MINUS", ":": "KEYCODE_SEMICOLON",
     ".": "KEYCODE_PERIOD", "/": "KEYCODE_SLASH", ",": "KEYCODE_COMMA"}
ADB = ADB


def key(k):
    subprocess.run([ADB, "-s", SERIAL, "shell", "input", "keyevent", k])


for ch in sys.argv[1]:
    if ch in M:
        key(M[ch])
    elif ch.isalpha():
        key("KEYCODE_SHIFT_LEFT")
        time.sleep(0.05)
        key("KEYCODE_" + ch.upper())
    elif ch.isdigit():
        key("KEYCODE_" + ch)
    else:
        print("skip", ch)
        continue
    time.sleep(0.08)
