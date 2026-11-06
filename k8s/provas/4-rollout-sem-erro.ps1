# Prova: um rollout restart troca as réplicas sem erro para o cliente.
#
# Uso, na raiz do repositório, com o cluster no ar:
#   .\k8s\provas\4-rollout-sem-erro.ps1                       # reinicia o account (o mais exigente)
#   .\k8s\provas\4-rollout-sem-erro.ps1 -Deployment transfer
#
# Manda um POST /transfers a cada 200 ms durante 60 segundos, alternando os sentidos, enquanto o
# Kubernetes troca as réplicas. O account é o padrão porque está no caminho de toda transferência.
#
# Esperado: nenhum HTTP 5xx, nenhum "sem resposta" e nenhum FAILED. Alguns PENDING são aceitáveis:
# uma chamada pode pegar uma réplica no meio do desligamento, e o transfer responde 202 em vez de
# errar (ADR-0005). O script espera o reconciliador e confere que todos viraram COMPLETED,
# e que a soma dos saldos não mudou.

param(
    [ValidateSet("account", "transfer", "gateway", "notification")]
    [string]$Deployment = "account",
    [int]$Segundos = 60
)

$ErrorActionPreference = "Stop"
$Base = "http://localhost:8080"

function Saldo($conta) { (Invoke-RestMethod "$Base/accounts/$conta").balance }

$antes = @{ a = Saldo "10001"; b = Saldo "10002" }

Write-Host "Reiniciando o $Deployment e mandando transferências por $Segundos segundos..." -ForegroundColor Cyan
kubectl rollout restart "deployment/$Deployment" | Out-Null

$fim = (Get-Date).AddSeconds($Segundos)
$resultados = @()
$i = 0
while ((Get-Date) -lt $fim) {
    $i++
    $origem, $destino = if ($i % 2) { "10001", "10002" } else { "10002", "10001" }
    $body = @{ sourceAccountNumber = $origem; targetAccountNumber = $destino; amount = 1.00 } | ConvertTo-Json
    try {
        $r = Invoke-RestMethod -Method Post -Uri "$Base/transfers" -ContentType "application/json" `
            -Headers @{ "Idempotency-Key" = [guid]::NewGuid().ToString() } -Body $body -TimeoutSec 15
        $resultados += [pscustomobject]@{ id = $r.id; resultado = $r.status }
    } catch {
        $codigo = if ($_.Exception.Response) { "HTTP " + [int]$_.Exception.Response.StatusCode } else { "sem resposta" }
        $resultados += [pscustomobject]@{ id = $null; resultado = $codigo }
    }
    Start-Sleep -Milliseconds 200
}
kubectl rollout status "deployment/$Deployment"

Write-Host "`nResultados durante o rollout:" -ForegroundColor Cyan
$resultados | Group-Object resultado | Format-Table Count, Name -AutoSize

$pendentes = @($resultados | Where-Object resultado -eq "PENDING")
if ($pendentes.Count -gt 0) {
    Write-Host "Esperando o reconciliador resolver $($pendentes.Count) pendente(s)..." -ForegroundColor Cyan
    Start-Sleep -Seconds 90
    $pendentes | ForEach-Object { (Invoke-RestMethod "$Base/transfers/$($_.id)").status } |
        Group-Object | Format-Table Count, Name -AutoSize
}

$depois = @{ a = Saldo "10001"; b = Saldo "10002" }
"antes:  soma=$($antes.a + $antes.b)"
"depois: soma=$($depois.a + $depois.b)"
