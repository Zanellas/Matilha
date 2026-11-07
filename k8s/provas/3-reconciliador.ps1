# Prova (ADR-0005 e ADR-0006): com o account fora do ar, a transferência fica PENDING, e não FAILED.
# Quando o account volta, o reconciliador de UMA das réplicas do transfer completa a transferência.
#
# Uso, na raiz do repositório, com o cluster no ar:
#   .\k8s\provas\3-reconciliador.ps1
#
# Esperado: 202 PENDING com o account parado, COMPLETED em até ~2 minutos depois que ele volta,
# e a linha "reconciled as COMPLETED" no log de um pod do transfer só.
# Se o script for interrompido no meio, suba o account de novo: kubectl scale deployment/account --replicas=2

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "comum.ps1")
$Replicas = (kubectl get deployment account -o jsonpath="{.spec.replicas}")
$inicio = Get-Date

Write-Host "Derrubando o account (replicas=0)..." -ForegroundColor Cyan
kubectl scale deployment/account --replicas=0 | Out-Null
kubectl wait --for=delete pod -l app=account --timeout=90s | Out-Null

Write-Host "Criando uma transferência com o account fora do ar..." -ForegroundColor Cyan
$body = @{ sourceAccountNumber = "10001"; targetAccountNumber = "10002"; amount = 25.00 } | ConvertTo-Json
$cabecalhos = Autorizacao "ana"
$cabecalhos["Idempotency-Key"] = [guid]::NewGuid().ToString()
$resposta = Invoke-WebRequest -Method Post -Uri "$Base/transfers" -ContentType "application/json" `
    -Headers $cabecalhos -Body $body -UseBasicParsing
$id = ($resposta.Content | ConvertFrom-Json).id
"HTTP $($resposta.StatusCode), Location: $($resposta.Headers.Location)"
"status: $((Invoke-RestMethod "$Base/transfers/$id" -Headers (Autorizacao "ana")).status)"

Write-Host "`nSubindo o account de novo (replicas=$Replicas)..." -ForegroundColor Cyan
kubectl scale deployment/account --replicas=$Replicas | Out-Null
kubectl rollout status deployment/account

Write-Host "`nEsperando o reconciliador (roda a cada 60s e só pega pendentes com mais de 30s)..." -ForegroundColor Cyan
$limite = (Get-Date).AddMinutes(3)
do {
    Start-Sleep -Seconds 10
    $status = (Invoke-RestMethod "$Base/transfers/$id" -Headers (Autorizacao "ana")).status
    "$(Get-Date -Format HH:mm:ss)  $status"
} while ($status -eq "PENDING" -and (Get-Date) -lt $limite)

$desde = [int]((Get-Date) - $inicio).TotalSeconds + 10
Write-Host "`nQuem reconciliou:" -ForegroundColor Cyan
kubectl logs -l app=transfer --prefix --tail=-1 --since="$($desde)s" | Select-String "reconcil"
