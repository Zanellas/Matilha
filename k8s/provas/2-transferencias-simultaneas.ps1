# Prova (ADR-0003): transferências simultâneas em sentidos opostos, com várias réplicas do account,
# não causam deadlock nem criam ou somem com dinheiro.
#
# Uso, na raiz do repositório, com o cluster no ar:
#   .\k8s\provas\2-transferencias-simultaneas.ps1
#   .\k8s\provas\2-transferencias-simultaneas.ps1 -Quantidade 100
#   .\k8s\provas\2-transferencias-simultaneas.ps1 -ContarPorPod
#
# -ContarPorPod liga o access log do Tomcat no account (por variável de ambiente, sem mudar código),
# conta quantas chamadas cada réplica recebeu e desliga o access log no fim. Cada ligar e desligar
# reinicia os pods do account, então essa opção leva cerca de 1 minuto a mais.
#
# Esperado: só COMPLETED, nenhum HTTP 500, a soma dos saldos igual antes e depois e,
# com -ContarPorPod, as chamadas divididas entre os dois pods do account.

param(
    [int]$Quantidade = 40,
    [switch]$ContarPorPod
)

$ErrorActionPreference = "Stop"
$Base = "http://localhost:8080"
$AccessLog = @(
    "SERVER_TOMCAT_ACCESSLOG_ENABLED", "SERVER_TOMCAT_ACCESSLOG_DIRECTORY", "SERVER_TOMCAT_ACCESSLOG_PREFIX",
    "SERVER_TOMCAT_ACCESSLOG_SUFFIX", "SERVER_TOMCAT_ACCESSLOG_FILE_DATE_FORMAT", "SERVER_TOMCAT_ACCESSLOG_ROTATE"
)

function Saldo($conta) { (Invoke-RestMethod "$Base/accounts/$conta").balance }

if ($ContarPorPod) {
    Write-Host "Ligando o access log do account..." -ForegroundColor Cyan
    kubectl set env deployment/account SERVER_TOMCAT_ACCESSLOG_ENABLED=true SERVER_TOMCAT_ACCESSLOG_DIRECTORY=/dev `
        SERVER_TOMCAT_ACCESSLOG_PREFIX=stdout SERVER_TOMCAT_ACCESSLOG_SUFFIX= SERVER_TOMCAT_ACCESSLOG_FILE_DATE_FORMAT= `
        SERVER_TOMCAT_ACCESSLOG_ROTATE=false | Out-Null
    kubectl rollout status deployment/account
}

$antes = @{ a = Saldo "10001"; b = Saldo "10002" }
$inicio = Get-Date

$envio = {
    param($base, $origem, $destino)
    $body = @{ sourceAccountNumber = $origem; targetAccountNumber = $destino; amount = 10.00 } | ConvertTo-Json
    try {
        (Invoke-RestMethod -Method Post -Uri "$base/transfers" -ContentType "application/json" `
            -Headers @{ "Idempotency-Key" = [guid]::NewGuid().ToString() } -Body $body).status
    } catch {
        if ($_.Exception.Response) { "HTTP " + [int]$_.Exception.Response.StatusCode } else { "sem resposta" }
    }
}

Write-Host "Disparando $Quantidade transferências em paralelo (metade em cada sentido)..." -ForegroundColor Cyan
$pool = [runspacefactory]::CreateRunspacePool(1, 20)
$pool.Open()
$tarefas = 1..$Quantidade | ForEach-Object {
    $origem, $destino = if ($_ % 2) { "10001", "10002" } else { "10002", "10001" }
    $ps = [powershell]::Create().AddScript($envio).AddArgument($Base).AddArgument($origem).AddArgument($destino)
    $ps.RunspacePool = $pool
    [pscustomobject]@{ PS = $ps; Handle = $ps.BeginInvoke() }
}
$resultados = $tarefas | ForEach-Object { $_.PS.EndInvoke($_.Handle); $_.PS.Dispose() }
$pool.Close()

$depois = @{ a = Saldo "10001"; b = Saldo "10002" }

Write-Host "`nResultados:" -ForegroundColor Cyan
$resultados | Group-Object | Format-Table Count, Name -AutoSize
"antes:  10001=$($antes.a)  10002=$($antes.b)  soma=$($antes.a + $antes.b)"
"depois: 10001=$($depois.a)  10002=$($depois.b)  soma=$($depois.a + $depois.b)"

if ($ContarPorPod) {
    $desde = [int]((Get-Date) - $inicio).TotalSeconds + 10
    Write-Host "`nChamadas ao /internal/transfers por pod do account:" -ForegroundColor Cyan
    # --tail=-1: com seletor (-l), o kubectl mostra só as últimas 10 linhas de cada pod por padrão.
    kubectl logs -l app=account --prefix --tail=-1 --since="$($desde)s" |
        Select-String "POST /internal/transfers" |
        ForEach-Object { ($_.Line -split "/")[1] } |
        Group-Object | Format-Table Count, Name -AutoSize

    Write-Host "Desligando o access log do account..." -ForegroundColor Cyan
    $remover = $AccessLog | ForEach-Object { "$_-" }
    kubectl set env deployment/account @remover | Out-Null
    kubectl rollout status deployment/account
}
