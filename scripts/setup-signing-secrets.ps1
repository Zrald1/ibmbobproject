# Generates the values you paste into GitHub Actions secrets for release signing.
#
#   pwsh scripts/setup-signing-secrets.ps1 -Keystore "C:\Users\You\.argos-signing\argos-release.keystore"
#
# Then add these repository secrets (Settings → Secrets and variables → Actions):
#
#   ANDROID_KEYSTORE_BASE64      the printed base64 blob
#   ANDROID_KEYSTORE_PASSWORD    your keystore password
#   ANDROID_KEY_ALIAS            e.g. argos-key
#   ANDROID_KEY_ALIAS_PASSWORD   your key password
#
# The keystore itself is NEVER committed — only its base64 lives in GitHub's
# encrypted secret store.

param(
    [Parameter(Mandatory = $true)]
    [string]$Keystore,

    [string]$OutFile = "argos-keystore.base64.txt"
)

if (-not (Test-Path $Keystore)) {
    Write-Error "Keystore not found: $Keystore"
    exit 1
}

$bytes = [System.IO.File]::ReadAllBytes($Keystore)
$b64 = [Convert]::ToBase64String($bytes)

# No line wrapping — the workflow pipes this straight into `base64 -d`.
Set-Content -Path $OutFile -Value $b64 -NoNewline -Encoding ASCII

Write-Host ""
Write-Host "Keystore : $Keystore ($($bytes.Length) bytes)"
Write-Host "Base64   : written to $OutFile ($($b64.Length) chars)"
Write-Host ""
Write-Host "Next steps:"
Write-Host "  1. GitHub repo -> Settings -> Secrets and variables -> Actions"
Write-Host "  2. New repository secret: ANDROID_KEYSTORE_BASE64"
Write-Host "     value = the full contents of $OutFile"
Write-Host "  3. Add ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_ALIAS_PASSWORD"
Write-Host "  4. Delete $OutFile once the secret is saved"
Write-Host ""
Write-Host "Then release with:  git tag v3.34.0 && git push origin v3.34.0"
