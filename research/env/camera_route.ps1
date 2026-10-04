<#
    camera_route.ps1 — V7 相机扫码「可复现路径」固化脚本

    目的：把 F3 那一轮的相机取流验证固化成一条**可重复、无需人工点击**的路径，
          并在路径走不通时**精确指出卡在哪一步**（不猜、不写"应该可以"）。

    用法：
      pwsh -File research\env\camera_route.ps1 -Port 6110
      pwsh -File research\env\camera_route.ps1 -Port 6110 -KeepInstance   # 故意保留实例

    硬规矩（见 research\HOUSEKEEPING.md）：
      * 端口必须 **偶数且 >= 6110**，启动前查占用；
      * 只用已装镜像：android-35;google_apis_playstore_tablet + pixel_tablet 几何 1080x2400@420
        （几何**不是**用 -skin，pixel_tablet 不是合法 skin 名，用 wm 覆盖）；
      * 谁起谁关；只按 serial 关；**禁止** adb kill-server / -a / 通配符；
      * 后端 127.0.0.1:4095 由 Lead 起好，本脚本**只做 adb reverse**，绝不启停。

    ⚠️ 本轮（V7）实测结论见 research\web\V7_CLOSEOUT.md：
       被测 release 包 applicationId = com.xiufeigo.opencodeshell.web.debug，
       但 **BuildConfig.DEBUG = false**（`run-as` 报 "package not debuggable"），
       因此 F3 用的 `oc_debug_eval` 注入通道在本包上**静默失效**（见 STEP 6 断言）。
       脚本会把这一点作为显式断言跑出来，而不是假装成功。
#>
[CmdletBinding()]
param(
    [int]$Port = 6110,
    [string]$Avd = "OC_Deliver_R2",
    [string]$Apk = "research\deliver\opencode-shell-web-release-nor8.apk",
    [string]$ApkMd5 = "bc7f30180775485ce887dc74e4489c72",
    [string]$Password = "opencode",
    [switch]$KeepInstance
)

$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$emu = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
$repo = "C:\sensorsdata\main\program\opencode-android-shell"
$outDir = "$repo\research\env\v7"
$log = "$outDir\camera_route_$Port.log"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$script:startedInstance = $false
$serial = "emulator-$Port"
$steps = [System.Collections.ArrayList]::new()

function Say([string]$m) {
    $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m
    Write-Output $line
    Add-Content -Path $log -Value $line -Encoding utf8
}
function Step([int]$n, [string]$name, [string]$result, [string]$detail) {
    [void]$steps.Add([pscustomobject]@{ N = $n; Step = $name; Result = $result; Detail = $detail })
    Say ("STEP {0} [{1}] {2} :: {3}" -f $n, $result, $name, $detail)
}
function A($serial_, [string[]]$argv) {
    $o = & $adb -s $serial_ @argv 2>&1
    return ($o | Out-String).Trim()
}
function Shot([string]$tag) {
    $p = "$outDir\${tag}.png"
    & $adb -s $serial exec-out screencap -p > $p
    return $p
}

# ---------------------------------------------------------------- STEP 0 前置
Say "================ V7 camera_route 开始（port=$Port）================"
if ($Port % 2 -ne 0 -or $Port -lt 6110) { throw "端口必须为偶数且 >= 6110，实得 $Port" }
$busy = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in $Port, ($Port + 2), ($Port + 4) }
if ($busy) { throw "端口 $Port 已被占用：$(($busy.LocalPort) -join ',')" }
$preProcs = Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue
Say ("开轮基线：占用端口数={0}  全机 emulator/qemu 进程数={1}" -f ($busy|Measure-Object).Count, ($preProcs|Measure-Object).Count)
Step 0 "前置检查(端口/占用)" "PASS" "port=$Port 偶数>=6110；占用=0；既有 emulator/qemu=$($preProcs.Count)"

try {
    # ------------------------------------------------------------- STEP 1 起机
    $emuLog = "$outDir\emu_$Port.log"
    Start-Process -FilePath $emu -ArgumentList @(
        "-avd", $Avd, "-port", $Port,
        "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot",
        "-gpu", "swiftshader_indirect",
        "-camera-back", "virtualscene", "-camera-front", "none",
        "-memory", "4096"
    ) -RedirectStandardOutput $emuLog -RedirectStandardError "$emuLog.err" -WindowStyle Hidden
    $script:startedInstance = $true
    Say "已发起启动 $Avd @ $Port（日志 $emuLog）"

    $deadline = (Get-Date).AddSeconds(300)
    $booted = $false
    while ((Get-Date) -lt $deadline) {
        if ((A $serial @("shell","getprop","sys.boot_completed")) -eq "1") { $booted = $true; break }
        Start-Sleep -Seconds 5
    }
    if (-not $booted) { throw "300s 内未 boot 完成" }
    Step 1 "起机 boot" "PASS" "$serial sys.boot_completed=1"

    # ------------------------------------------------------------- STEP 2 几何
    [void](A $serial @("shell","wm","size","1080x2400"))
    [void](A $serial @("shell","wm","density","420"))
    $sz = A $serial @("shell","wm","size"); $dn = A $serial @("shell","wm","density")
    if ($sz -notmatch "Override size: 1080x2400" -or $dn -notmatch "Override density: 420") { throw "几何覆盖失败: $sz / $dn" }
    Step 2 "几何覆盖 1080x2400@420" "PASS" "Override size=1080x2400, Override density=420"

    # ------------------------------------------------------------- STEP 3 装包
    $apkPath = Join-Path $repo $Apk
    $localMd5 = (Get-FileHash $apkPath -Algorithm MD5).Hash.ToLower()
    if ($localMd5 -ne $ApkMd5) { throw "本机 APK md5=$localMd5 ≠ 期望 $ApkMd5" }
    [void](A $serial @("install","-r","-g",$apkPath))
    $pkgLine = (A $serial @("shell","pm","list","packages")) -split "`n" | Where-Object { $_ -match "xiufeigo" } | Select-Object -First 1
    $pkg = ($pkgLine -replace '^package:','').Trim()
    $devApk = ((A $serial @("shell","pm","path",$pkg)) -split "`n")[0] -replace '^package:',''
    $devMd5 = ((A $serial @("shell","md5sum",$devApk.Trim())) -split '\s+')[0]
    if ($devMd5 -ne $ApkMd5) { throw "设备端 base.apk md5=$devMd5 ≠ $ApkMd5" }
    $camGranted = (A $serial @("shell","dumpsys","package",$pkg)) -match "CAMERA: granted=true"
    Step 3 "安装并核对 APK + CAMERA 授权" "PASS" "pkg=$pkg md5 一致; CAMERA granted=$camGranted"

    # ------------------------------------------------------- STEP 4 播种口令
    [void](A $serial @("reverse","tcp:4095","tcp:4095"))
    [void](A $serial @("reverse","--list"))
    [void](A $serial @("shell","am","force-stop",$pkg))
    [void](A $serial @("shell","pm","clear",$pkg))
    Start-Sleep -Seconds 2
    [void](A $serial @("reverse","tcp:4095","tcp:4095"))   # pm clear 不会清 reverse，保险起见重申
    [void](A $serial @("shell","am","start","-n","$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity"))
    Start-Sleep -Seconds 12
    # 宿主原生对话框：口令框 (540,1128)、保存并连接 (854,1399)
    [void](A $serial @("shell","input","tap","540","1128")); Start-Sleep -Seconds 2
    [void](A $serial @("shell","input","text",$Password)); Start-Sleep -Seconds 1
    $pShot = Shot "camera_route_pwd"
    [void](A $serial @("shell","input","tap","854","1399")); Start-Sleep -Seconds 18
    $homeShot = Shot "camera_route_home"
    $store = A $serial @("logcat","-d","-s","OcWebHost:*")
    $okPwd = $store -match "credentials stored"
    Step 4 "播种口令并连上后端" $(if($okPwd){"PASS"}else{"FAIL"}) "log 'credentials stored'=$okPwd ; 图: $homeShot"

    # ------------------------------------------ STEP 5 走到配对扫码入口（断言点）
    #  已知结构（V7 代码考古结论，见 V7_CLOSEOUT.md §1.3）：
    #    * ConnectServerScreen 是 **layout fallback**，仅当 servers.list 为空才渲染
    #      (_upstream/packages/app/src/shell/routes/routes.tsx:79)
    #    * "Scan QR code" 按钮在 dialog.tsx:162/176（Settings -> "Add server"）或 screen.tsx
    #    * release 包**没有**任何跳 URL 的钩子：oc_web_url 未被读取、无 deep-link
    #      intent-filter、无 setWebContentsDebuggingEnabled ⇒ 无法 CDP，也点不到 fallback
    $reachEntry = $false
    foreach ($probe in @(@(532,60,'Tabs'), @(1020,60,'top-right'), @(72,60,'top-left'))) {
        [void](A $serial @("shell","input","tap",[string]$probe[0],[string]$probe[1]))
        Start-Sleep -Seconds 2
        [void](A $serial @("shell","input","swipe","540","1200","540","1150","200"))
        Start-Sleep -Seconds 1
        [void](A $serial @("shell","uiautomator","dump","/sdcard/_v7.xml"))
        [void](A $serial @("pull","/sdcard/_v7.xml","$outDir\_v7.xml"))
        $x = [System.IO.File]::ReadAllText("$outDir\_v7.xml", [System.Text.Encoding]::UTF8)
        if ($x -match "Scan QR code|server.connect.scan|扫码") { $reachEntry = $true; Say "在 $($probe[2]) 处找到扫码入口"; break }
    }
    if ($reachEntry) {
        Step 5 "走到配对扫码入口" "PASS" "UI 上出现 'Scan QR code'"
    } else {
        Step 5 "走到配对扫码入口" "FAIL" "UI 探测 Tabs/右上/左上 均未见 'Scan QR code'；connect screen 是空 registry 的 fallback，而本部署 registry 恒非空"
    }

    # ------------------------------------- STEP 6 debug 注入通道可用性（对比 F3）
    [void](A $serial @("logcat","-c"))
    $js = "(window.location.href+'|secure='+window.isSecureContext)"
    [void](A $serial @("shell","am","force-stop",$pkg)); Start-Sleep -Seconds 2
    [void](A $serial @("shell","am","start","-n","$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity","--es","oc_debug_eval","'$js'"))
    Start-Sleep -Seconds 8
    $eval = A $serial @("logcat","-d","-s","OcWebHost/Eval:*")
    $notDebug = (A $serial @("shell","run-as",$pkg,"true")) -match "not debuggable"
    Step 6 "oc_debug_eval 注入通道（F3 的取流手段）" $(if($eval -match "eval="){"PASS"}else{"FAIL"}) `
        ("eval 响应行数={0}；run-as 判定 not debuggable={1} ⇒ BuildConfig.DEBUG=false，本包无法用 F3 的注入法取流" -f (($eval -split "`n" | Where-Object {$_ -match "eval="}).Count), $notDebug)

    # ---------------------------------------------------- STEP 7 getUserMedia 断言
    $gum = "未执行（STEP 5 未到达扫码入口，无页面会调用 getUserMedia）"
    Step 7 "getUserMedia live 流" "BLOCKED" $gum
    $preview = Shot "camera_route_preview"
    Say "预览截图（预期若无流则无相机画面）：$preview"

    # ---------------------------------------------------------------- 汇总
    Say "---------------- 汇总 ----------------"
    foreach ($s in $steps) { Say ("{0,-3} {1,-34} {2,-8} {3}" -f $s.N, $s.Step, $s.Result, $s.Detail) }
    $summary = "$outDir\camera_route_summary_$Port.json"
    $steps | ConvertTo-Json -Depth 4 | Out-File $summary -Encoding utf8
    Say "步骤汇总: $summary"
    Say "日志: $log"
}
finally {
    # --------------------------------------------------------------- 收尾
    Say "---------------- 收尾 ----------------"
    [void](A $serial @("shell","wm","size","reset"))
    [void](A $serial @("shell","wm","density","reset"))
    $sz = A $serial @("shell","wm","size"); $dn = A $serial @("shell","wm","density")
    Say "wm reset 复核 => $sz / $dn （应无 Override 行）"

    if ($script:startedInstance -and -not $KeepInstance) {
        $mine = Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue |
                Where-Object { $_.CommandLine -match "-avd\s+$Avd" -and $_.CommandLine -match "-port\s+$Port" }
        if ($mine) { Say "按 AVD+端口确认归属: $($mine.ProcessId -join ',') ⇒ MINE" }
        Say (& $adb -s $serial emu kill 2>&1 | Out-String).Trim()
        Start-Sleep -Seconds 25
    } elseif ($script:startedInstance) {
        Say "故意保留 $Avd / $serial（-KeepInstance），原因：调用方要求继续留用"
    }
    $res = Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue |
           Where-Object { $_.CommandLine -match '-avd\s+(OC_\S+|Medium_Desktop_B)' }
    Say ("复核 OC_*/Medium_Desktop_B 残留 = {0}" -f ($res|Measure-Object).Count)
    $lis = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in $Port, ($Port+2), ($Port+4) }
    Say ("复核端口 {0}/{1}/{2} LISTEN = {3}" -f $Port, ($Port+2), ($Port+4), ($lis|Measure-Object).Count)
    Say (& $adb devices 2>&1 | Out-String).Trim()
    Say "未执行 adb kill-server、未用 -a/通配符"
}
