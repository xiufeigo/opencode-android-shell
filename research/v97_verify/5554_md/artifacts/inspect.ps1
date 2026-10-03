# v140 inspector: pull a dump and list composer-relevant nodes (script form to keep CLI args simple)
param(
  [string]$Adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe",
  [string]$Ser = "emulator-5554",
  [string]$Remote = "/sdcard/v140_probe.xml",
  [string]$Local,
  [string]$Grep = ""
)
& $Adb -s $Ser shell uiautomator dump $Remote 2>&1 | Select-Object -Last 1
& $Adb -s $Ser pull $Remote $Local 2>&1 | Select-Object -Last 1
$x = Get-Content $Local -Raw
$nodes = [regex]::Matches($x, '<node\s[^>]*>') | ForEach-Object {
  $v = $_.Value
  [pscustomobject]@{
    cls  = if($v -match 'class="([^"]*)"'){$Matches[1]}else{''}
    txt  = if($v -match 'text="([^"]*)"'){$Matches[1]}else{''}
    desc = if($v -match 'content-desc="([^"]*)"'){$Matches[1]}else{''}
    clk  = if($v -match 'clickable="([^"]*)"'){$Matches[1]}else{'false'}
    bnd  = if($v -match 'bounds="([^"]*)"'){$Matches[1]}else{''}
  }
}
"nodes=" + $nodes.Count
$watch = @('Send','Stop','Queue','Steer','Rem'+'ove','Add','Model','Open terminal','Working')
$nodes | Where-Object {
  ($_.cls -eq 'android.widget.EditText') -or ($watch -contains $_.desc) -or ($_.txt -match 'E2ECHK') -or ($_.txt -match 'Working')
} | ForEach-Object { "{0,-34} desc='{1}' text='{2}' clk={3} {4}" -f $_.cls,$_.desc,$_.txt,$_.clk,$_.bnd }
