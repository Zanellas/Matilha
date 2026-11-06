# Prova: as duas réplicas do notification dividem as 3 partições do tópico (mesmo grupo de consumidores).
#
# Uso, na raiz do repositório, com o cluster no ar:
#   .\k8s\provas\1-particoes-notification.ps1
#
# Esperado: as partições 0, 1 e 2 do matilha.transfer.events aparecem com dois HOSTs diferentes,
# que são os IPs dos dois pods do notification. O LAG deve ser 0: nenhum evento esperando.

$ErrorActionPreference = "Stop"

Write-Host "Pods do notification:" -ForegroundColor Cyan
kubectl get pods -l app=notification -o wide

Write-Host "`nGrupo matilha-notification:" -ForegroundColor Cyan
kubectl exec kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group matilha-notification
