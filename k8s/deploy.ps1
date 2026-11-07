# Sobe ou atualiza o Matilha no cluster kind local.
#
# Uso, de qualquer pasta:
#   .\k8s\deploy.ps1                      # cria o cluster se não existir, gera as 4 imagens e aplica tudo
#   .\k8s\deploy.ps1 -Servicos transfer   # só gera e reinicia o transfer (depois de mudar o código dele)
#   .\k8s\deploy.ps1 -SemBuild            # só aplica os manifests (depois de mudar um YAML)
#
# Pré-requisitos: Docker Desktop rodando (em cgroup v2), kind, kubectl e os três
# k8s/infra/*-db-secret.yaml criados a partir dos .example.

param(
    [ValidateSet("account", "transfer", "notification", "gateway")]
    [string[]]$Servicos = @("account", "transfer", "notification", "gateway"),
    [switch]$SemBuild
)

# "Continue", e não "Stop": no PowerShell 5.1, redirecionar o stderr de um programa externo (2>$null)
# com "Stop" vira exceção. Cada comando é conferido pelo código de saída na função Executar.
$ErrorActionPreference = "Continue"
$Cluster = "matilha"
$Raiz = Split-Path $PSScriptRoot -Parent
$KafkaUi = "ghcr.io/kafbat/kafka-ui:v1.5.0"

function Passo($texto) { Write-Host "`n==> $texto" -ForegroundColor Cyan }
function Falhou($texto) { Write-Host $texto -ForegroundColor Red; exit 1 }
function Executar {
    & $args[0] $args[1..($args.Count - 1)]
    if ($LASTEXITCODE -ne 0) { Falhou "Falhou: $($args -join ' ')" }
}

# O terminal do VS Code pode ter um PATH antigo, sem o kind instalado pelo winget.
$env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")

Passo "Conferindo o ambiente"
docker info --format "{{.CgroupVersion}}" *> $null
if ($LASTEXITCODE -ne 0) { Falhou "O Docker não está rodando. Abra o Docker Desktop e tente de novo." }
if (-not (Get-Command kind -ErrorAction SilentlyContinue)) { Falhou "kind não encontrado. Instale com: winget install Kubernetes.kind" }
foreach ($banco in "account", "transfer", "notification") {
    $secret = Join-Path $Raiz "k8s/infra/$banco-db-secret.yaml"
    if (-not (Test-Path $secret)) {
        Falhou "Falta $secret. Copie o $banco-db-secret.example.yaml e defina usuário e senha."
    }
}

$existia = (kind get clusters 2>$null) -contains $Cluster
if (-not $existia) {
    Passo "Criando o cluster $Cluster"
    Executar kind create cluster --config (Join-Path $Raiz "k8s/kind-config.yaml")
}
Executar kubectl config use-context "kind-$Cluster"

if (-not $SemBuild) {
    foreach ($servico in $Servicos) {
        Passo "Gerando a imagem matilha-$($servico):dev"
        Executar docker build -t "matilha-$($servico):dev" (Join-Path $Raiz "matilha-$servico")
    }
    Passo "Carregando as imagens no cluster"
    $imagens = $Servicos | ForEach-Object { "matilha-$($_):dev" }
    Executar kind load docker-image @imagens --name $Cluster
}

docker image inspect $KafkaUi *> $null
if ($LASTEXITCODE -ne 0) {
    Passo "Baixando a imagem da Kafka UI"
    Executar docker pull $KafkaUi
}
Executar kind load docker-image $KafkaUi --name $Cluster

Passo "Aplicando os manifests"
Executar kubectl apply -k (Join-Path $Raiz "k8s")
Executar kubectl config set-context --current --namespace=matilha

if ($existia -and -not $SemBuild) {
    foreach ($servico in $Servicos) {
        Passo "Reiniciando o $servico com a imagem nova"
        Executar kubectl rollout restart "deployment/$servico"
    }
}

Passo "Esperando os serviços ficarem prontos"
foreach ($servico in "account", "transfer", "notification", "gateway") {
    Executar kubectl rollout status "deployment/$servico" --timeout=5m
}

kubectl get pods
Write-Host "`nPronto: http://localhost:8080" -ForegroundColor Green
