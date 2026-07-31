#!/bin/bash
# Recreate Keycloak with the fixed realm (kafka stopped to free RAM) and verify import.
systemctl is-active docker >/dev/null 2>&1 || systemctl start docker
cd /mnt/d/GMEPay+/code || exit 1

echo "--- stop heavy kafka stack to free RAM for keycloak ---"
docker compose stop kafka zookeeper 2>&1 | tail -3

echo "--- recreate keycloak with corrected realm-gmepay.json ---"
docker compose up -d --force-recreate keycloak 2>&1 | tail -6

echo "--- wait keycloak healthy ---"
for i in $(seq 1 48); do
  s=$(docker inspect -f '{{.State.Health.Status}}' code-keycloak-1 2>/dev/null)
  echo "keycloak: $s"
  [ "$s" = "healthy" ] && break
  sleep 5
done

echo "--- realm import log lines ---"
docker logs code-keycloak-1 2>&1 | grep -iE "import|realm 'gmepay'|gmepay|Listening|started in|ERROR" | tail -10

echo "--- serves the gmepay realm OIDC discovery? (HTTP status line) ---"
docker exec code-keycloak-1 sh -c 'exec 3<>/dev/tcp/127.0.0.1/8080; printf "GET /realms/gmepay/.well-known/openid-configuration HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3; head -1 <&3' 2>&1

echo "--- docker ps ---"
docker ps --format 'table {{.Names}}\t{{.Status}}' 2>&1
free -h | head -2
