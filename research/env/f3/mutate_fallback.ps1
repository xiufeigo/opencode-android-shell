# F3 fallback mutation verification.
#
# WHY THIS EXISTS: the Gradle route is blocked because a CONCURRENT agent's
# file (webapp/.../host/RoundActivityTracker.kt) does not compile right now
# (Unresolved reference DEFAULT_ROUND_HOLD_TIMEOUT_MS / Missing '}' /
# Unclosed comment). That file is NOT ours. We do not touch it.
#
# So this script replicates the EXACT assertion logic of our two tripwire test
# classes (CameraPermissionHostTest / NotificationPolicyBridgeTest) in
# PowerShell, and runs it against mutated copies of the sources.
#
# HONEST LIMITATION: this does NOT go through the JUnit runner, so it proves
# "the assertion flips from pass to fail", not "the JUnit task turns red".
# Section 5 of research/web/FIX_F3.md states this explicitly.

$root = "C:\sensorsdata\main\program\opencode-android-shell"
$bk = "$root\research\env\f3\revert_backup"
$enc = [System.Text.Encoding]::GetEncoding(936, [System.Text.EncoderFallback]::ReplacementFallback, [System.Text.DecoderFallback]::ReplacementFallback)

function Checks($html, $activity, $manifest) {
  $r = @{}
  # --- CameraPermissionHostTest ---
  $r['manifestDeclaresCameraPermission']        = $manifest.Contains('android.permission.CAMERA')
  $r['manifestCameraFeatureIsNotRequired']      = ($manifest -match '<uses-feature\s+android:name="android\.hardware\.camera"\s+android:required="false"\s*/>') -and -not ($manifest -match 'android\.hardware\.camera"\s+android:required="true"')
  $r['onPermissionRequestIsOverridden']         = $activity.Contains('override fun onPermissionRequest(')
  $r['onlyVideoCaptureIsGranted']               = $activity.Contains('PermissionRequest.RESOURCE_VIDEO_CAPTURE') -and $activity.Contains('arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)') -and -not ($activity -match 'request\.grant\(\s*request\.resources')
  $r['deniedWhenRuntimePermissionNotHeld']      = $activity.Contains('cameraPermission.launch(Manifest.permission.CAMERA)') -and $activity.Contains('request.deny()')
  $r['grantIsRestrictedToTrustedOrigins']       = $activity.Contains('isTrustedWebOrigin(origin)') -and $activity.Contains('proxy?.publicOrigin()') -and $activity.Contains('SecretStore.KEY_BACKEND_URL')
  $r['untrustedOriginIsDenied']                 = ($activity -match 'if \(!trusted \|\| wanted\.isEmpty\(\)\) \{[\s\S]{0,400}?request\.deny\(\)[\s\S]{0,200}?return') -and ($activity.IndexOf('camera permission DENIED origin=') -lt $activity.IndexOf('camera permission GRANTED (already held)'))
  $r['trustedOriginComparisonIsExactNotSubstring'] = $activity.Contains('private fun sameOrigin(a: String, b: String)') -and -not ($activity -match 'origin\.startsWith\(|origin\.contains\(')
  $r['mediaPlaybackDoesNotRequireUserGesture']   = $activity.Contains('mediaPlaybackRequiresUserGesture = false')
  # --- NotificationPolicyBridgeTest ---
  $bypass = [bool]($html -match '\boc\.postMessage\s*\(')
  $r['onlyTheShimMayCallTheHostDeliveryMouth']   = -not $bypass
  $r['selfCheckPageRoutesThroughTheShimNotDirectly'] = $html.Contains('new window.Notification(')
  return $r
}

function Report($tag, $html, $act, $man) {
  $r = Checks $html $act $man
  $fails = @($r.GetEnumerator() | Where-Object { -not $_.Value } | ForEach-Object { $_.Key })
  Write-Output ("  [{0}] checks={1} FAIL={2}" -f $tag, $r.Count, $fails.Count)
  foreach ($f in $fails) { Write-Output "     FAIL -> $f" }
}

Write-Output "=== BASELINE (current sources) ==="
$html0 = [System.IO.File]::ReadAllText("$root\webapp\src\main\assets\www\index.html", $enc)
$act0  = [System.IO.File]::ReadAllText("$root\webapp\src\main\java\com\xiufeigo\opencodeshell\web\WebHostActivity.kt")
$man0  = [System.IO.File]::ReadAllText("$root\webapp\src\main\AndroidManifest.xml")
Report "BASELINE" $html0 $act0 $man0

Write-Output "=== M1: index.html direct oc.postMessage (self-made bypass resurrected) ==="
$h1 = $html0.Replace('new window.Notification(', ('oc.postMessage(' + [char]34 + 'MUTBYPASS' + [char]34 + ',' + [char]34 + 'MUT' + [char]34 + ',sess_mut); new window.Notification('))
Report "M1" $h1 $act0 $man0

Write-Output "=== M2: onPermissionRequest trusted-origin gate removed ==="
$a2 = $act0.Replace('val trusted = origin != null && isTrustedWebOrigin(origin)', 'val trusted = true')
Report "M2" $html0 $a2 $man0

Write-Output "=== M3: manifest CAMERA declaration removed ==="
$m3 = [regex]::Replace($man0, '\s*<uses-permission android:name="android\.permission\.CAMERA" />\s*<uses-feature android:name="android\.hardware\.camera" android:required="false" />\s*', "`r`n")
Report "M3" $html0 $act0 $m3

Write-Output "=== M4: whole-array grant (whitelist removed) ==="
$a4 = $act0.Replace('request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))', 'request.grant(request.resources)')
Report "M4" $html0 $a4 $man0

Write-Output "=== M5: mediaPlaybackRequiresUserGesture flipped to true ==="
$a5 = $act0.Replace('mediaPlaybackRequiresUserGesture = false', 'mediaPlaybackRequiresUserGesture = true')
Report "M5" $html0 $a5 $man0
