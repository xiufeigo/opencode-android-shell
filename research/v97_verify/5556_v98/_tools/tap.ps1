param([string]$Tag,[string]$Pattern,[string]$Attr="text",[int]$Index=0)
$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98"
$x=[xml](Get-Content "$d/$Tag.xml" -Raw -Encoding UTF8)
$rx=[regex]::new($Pattern,[System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
$hits=@()
foreach($n in $x.SelectNodes("//node")){
  $t=$n.GetAttribute("text"); $cd=$n.GetAttribute("content-desc")
  $field = switch($Attr){"text"{$t} "desc"{$cd} default{$t} }
  if($field -and $rx.IsMatch($field)){ $hits += $n }
}
if($hits.Count -le $Index){ Write-Output "NOTFOUND '$Pattern' idx=$Index (total=$($hits.Count))"; exit 2 }
$b=$hits[$Index].GetAttribute("bounds")
if($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){
  $cx=[int]( ([int]$Matches[1]+[int]$Matches[3])/2 ); $cy=[int]( ([int]$Matches[2]+[int]$Matches[4])/2 )
  $adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
  & $adb -s emulator-5556 shell input tap $cx $cy
  Write-Output "TAP ($cx,$cy) <- '$Pattern' idx=$Index text='$($hits[$Index].GetAttribute('text'))' desc='$($hits[$Index].GetAttribute('content-desc'))'"
}
