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
