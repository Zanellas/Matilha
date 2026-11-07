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
    }
    catch {
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
