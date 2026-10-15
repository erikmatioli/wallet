# Cria a VM de desenvolvimento (Ampere A1, Ubuntu 24.04) na Oracle Cloud a partir do Windows, tentando ate haver
# capacidade na regiao. Precisa do OCI CLI configurado (oci setup config). Guia: docs/ambiente-remoto-oracle.md.
#
#   powershell -ExecutionPolicy Bypass -File scripts\oci-criar-vm.ps1 -Subnet <OCID da sub-rede publica>
#
# A cada rodada tenta 2 OCPUs / 12 GB e, sem vaga, 1 OCPU / 6 GB (depois, Edit -> Shape aumenta). Enquanto roda, o
# Windows nao suspende. O andamento fica em %USERPROFILE%\oci-criar-vm.log; quando a VM for criada, abre um aviso.
param(
    [Parameter(Mandatory = $true)][string]$Subnet,
    [string]$PublicKey = "$HOME\.ssh\oci_wallet.pub",
    [string]$Name = "wallet-dev",
    [int]$IntervalSeconds = 60
)

$ErrorActionPreference = "Stop"
$oci = (Get-Command oci -ErrorAction SilentlyContinue).Source
if (-not $oci) { $oci = "C:\Program Files (x86)\Oracle\oci_cli\oci.exe" }
$log = "$HOME\oci-criar-vm.log"

function Write-Log([string]$message) {
    $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $message"
    Write-Host $line
    Add-Content -Path $log -Value $line
}

function Invoke-Oci([string[]]$arguments) {
    # In PowerShell 5.1, what a native command writes to stderr becomes an error record: with "Stop" it would end the
    # script on the first "Out of host capacity".
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $out = & $oci @arguments 2>&1 | Out-String
        return @{ Ok = ($LASTEXITCODE -eq 0); Text = $out }
    } finally {
        $ErrorActionPreference = $previous
    }
}

# Mantem o Windows acordado enquanto este processo roda (ES_CONTINUOUS | ES_SYSTEM_REQUIRED).
Add-Type -Namespace Win32 -Name Power -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint esFlags);'
[void][Win32.Power]::SetThreadExecutionState([uint32]2147483649)

if (-not (Test-Path $PublicKey)) { throw "chave publica nao encontrada: $PublicKey" }

# A VM vai no compartimento da sub-rede.
$compartment = ((Invoke-Oci @("network", "subnet", "get", "--subnet-id", $Subnet)).Text | ConvertFrom-Json).data.'compartment-id'
$ad = ((Invoke-Oci @("iam", "availability-domain", "list", "--compartment-id", $compartment)).Text | ConvertFrom-Json).data[0].name
$images = (Invoke-Oci @("compute", "image", "list", "--compartment-id", $compartment, "--operating-system", "Canonical Ubuntu",
        "--operating-system-version", "24.04", "--shape", "VM.Standard.A1.Flex", "--sort-by", "TIMECREATED")).Text | ConvertFrom-Json
$image = $images.data[0].id

Write-Log "inicio: compartment=$compartment AD=$ad imagem=$image"
if (-not $compartment -or -not $ad -or -not $image) { throw "algum valor veio vazio: confira o OCID da sub-rede e o oci setup config" }

$shapes = @(@{ ocpus = 2; memory = 12 }, @{ ocpus = 1; memory = 6 })
$attempt = 0
while ($true) {
    foreach ($s in $shapes) {
        $attempt++
        $config = "{`"ocpus`":$($s.ocpus),`"memoryInGBs`":$($s.memory)}"
        $tmp = New-TemporaryFile
        Set-Content -Path $tmp -Value $config -Encoding ascii
        $r = Invoke-Oci @("compute", "instance", "launch", "--availability-domain", $ad, "--compartment-id", $compartment,
            "--shape", "VM.Standard.A1.Flex", "--shape-config", "file://$tmp", "--image-id", $image,
            "--subnet-id", $Subnet, "--assign-public-ip", "true", "--boot-volume-size-in-gbs", "200",
            "--display-name", $Name, "--ssh-authorized-keys-file", $PublicKey, "--no-retry")
        Remove-Item $tmp -ErrorAction SilentlyContinue
        if ($r.Ok) {
            Write-Log "CRIADA na tentativa $attempt com $($s.ocpus) OCPU / $($s.memory) GB. Veja o IP em Compute > Instances > $Name."
            Add-Type -AssemblyName PresentationFramework
            [void][System.Windows.MessageBox]::Show("VM $Name criada com $($s.ocpus) OCPU / $($s.memory) GB. Veja o IP publico no painel da Oracle.", "Oracle Cloud")
            [void][Win32.Power]::SetThreadExecutionState([uint32]2147483648)
            exit 0
        }
        $message = if ($r.Text -match '"message":\s*"([^"]*)"') { $Matches[1] } else { ($r.Text -split "`n")[0] }
        Write-Log "tentativa $attempt ($($s.ocpus)/$($s.memory)): $message"
        if ($message -notmatch "Out of capacity|Out of host capacity|TooManyRequests|Too many requests") {
            Write-Log "erro diferente de falta de capacidade: parando. Resposta completa:"
            Add-Content -Path $log -Value $r.Text
            exit 1
        }
    }
    Start-Sleep -Seconds $IntervalSeconds
}



