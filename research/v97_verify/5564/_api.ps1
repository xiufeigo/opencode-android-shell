# curl-equivalent for backend + tap/text helpers
function Get-Backend($path) {
  $h = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('opencode:opencode')) }
  Invoke-RestMethod -Uri ("http://127.0.0.1:4095" + $path) -Headers $h
}
function Show-Projects {
  (Get-Backend '/api/project') | ForEach-Object { $_.id + ' | ' + $_.canonical + ' | sandboxes=' + (($_.sandboxes) -join ',') }
}
function Dump-Net {
  (Get-Backend '/api/project') | ForEach-Object { $_.id + ' | ' + $_.canonical + ' | sandboxes=' + (($_.sandboxes) -join ',') }
}
function Dump-Dir {
  param([string]$path)
  (Get-Backend $path) | ConvertTo-Json -Depth 5
}
