$ErrorActionPreference = "Stop"

Write-Host "Pods do notification:" -ForegroundColor Cyan
kubectl get pods -l app=notification -o wide

Write-Host "`nGrupo matilha-notification:" -ForegroundColor Cyan
kubectl exec kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group matilha-notification
