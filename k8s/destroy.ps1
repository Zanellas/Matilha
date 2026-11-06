# Apaga o cluster kind do Matilha, com todos os dados dos bancos e do Kafka.
# Para só pausar, sem perder nada, use: docker stop matilha-control-plane
#
# Uso:
#   .\k8s\destroy.ps1           # pede confirmação
#   .\k8s\destroy.ps1 -Force    # sem confirmação

param([switch]$Force)

$Cluster = "matilha"
$env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")

if (-not ((kind get clusters 2>$null) -contains $Cluster)) {
    Write-Host "O cluster $Cluster não existe. Nada a fazer."
    exit 0
}

if (-not $Force) {
    $resposta = Read-Host "Apagar o cluster $Cluster e todos os dados dele? (s/N)"
    if ($resposta -ne "s") { Write-Host "Cancelado."; exit 0 }
}

kind delete cluster --name $Cluster
