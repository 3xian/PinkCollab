$ErrorActionPreference='Stop'
try {
$identity=[Security.Principal.WindowsIdentity]::GetCurrent()
$existing=Get-Service -Name PinkCollab -ErrorAction SilentlyContinue
if (-not $existing) {
  $credential=Get-Credential -UserName $identity.Name -Message 'PinkCollab must run as your account. Windows requires service login credentials.'
  if (-not $credential) { exit $CancelledExit }
  $sid=(New-Object Security.Principal.NTAccount($credential.UserName)).Translate([Security.Principal.SecurityIdentifier]).Value
  if ($sid -ne $identity.User.Value) { exit $WrongAccountExit }
  New-Service -Name PinkCollab -DisplayName 'PinkCollab Gateway' -BinaryPathName $env:PINKCOLLAB_SERVICE_COMMAND -StartupType Automatic -Credential $credential | Out-Null
} else {
  sc.exe config PinkCollab binPath= $env:PINKCOLLAB_SERVICE_COMMAND | Out-Null
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
} catch {
  $exception=$_.Exception
  while ($exception) {
    if ($exception -is [ComponentModel.Win32Exception]) { exit $exception.NativeErrorCode }
    $exception=$exception.InnerException
  }
  Write-Error 'Service installation failed. Check the current account credentials and service policy.' -ErrorAction Continue
  exit 1
}
