param([string]$Serial='emulator-5554',[int]$X,[int]$Y)
& C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe -s $Serial shell input tap $X $Y
