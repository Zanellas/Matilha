$Base = "http://localhost:8080"
$Keycloak = "http://localhost:8180"
$Senhas = @{ ana = "ana123"; bruno = "bruno123" }
$Tokens = @{}

function Token($usuario) {
    $atual = $Tokens[$usuario]
    if (-not $atual -or (Get-Date) -gt $atual.Vence) {
        $resposta = Invoke-RestMethod -Method Post "$Keycloak/realms/matilha/protocol/openid-connect/token" `
            -Body @{ grant_type = "password"; client_id = "matilha-dev-cli"; username = $usuario; password = $Senhas[$usuario] }
        $atual = [pscustomobject]@{ Valor = $resposta.access_token; Vence = (Get-Date).AddSeconds($resposta.expires_in - 30) }
        $Tokens[$usuario] = $atual
    }
    $atual.Valor
}

function Autorizacao($usuario) { @{ Authorization = "Bearer $(Token $usuario)" } }

function DonoDe($conta) { if ($conta -eq "10001") { "ana" } else { "bruno" } }

function Saldo($conta) { (Invoke-RestMethod "$Base/accounts/$conta" -Headers (Autorizacao (DonoDe $conta))).balance }
