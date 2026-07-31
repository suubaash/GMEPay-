#!/bin/bash
# Bring up + verify Keycloak (Postgres-backed) under systemd-managed Docker.
systemctl is-active docker >/dev/null 2>&1 || systemctl start docker
cd /mnt/d/GMEPay+/code || exit 1
echo "--- compose up postgres-keycloak + keycloak ---"
docker compose up -d postgres-keycloak keycloak 2>&1 | tail -10
echo "--- wait keycloak healthy (start-dev, ~60-120s) ---"
for i in $(seq 1 48); do
  s=$(docker inspect -f '{{.State.Health.Status}}' code-keycloak-1 2>/dev/null)
  echo "keycloak: $s"
  [ "$s" = "healthy" ] && break
  sleep 5
done
echo "--- KEYCLOAK serves master realm? (HTTP status line) ---"
docker exec code-keycloak-1 sh -c 'exec 3<>/dev/tcp/127.0.0.1/8080; printf "GET /realms/master HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3; head -1 <&3' 2>&1
echo "--- docker ps ---"
docker ps --format 'table {{.Names}}\t{{.Status}}' 2>&1
free -h | head -2
