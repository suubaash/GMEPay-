#!/bin/bash
# Infra smoke: prove Kafka + Redis + Postgres work inside the gmepay-docker WSL distro.
service docker start >/dev/null 2>&1
cd /mnt/d/GMEPay+/code || exit 1

echo "--- wait for kafka healthy ---"
for i in $(seq 1 24); do
  s=$(docker inspect -f '{{.State.Health.Status}}' code-kafka-1 2>/dev/null)
  echo "kafka health: $s"
  [ "$s" = "healthy" ] && break
  sleep 5
done

echo "--- KAFKA: create + list topic ---"
docker exec code-kafka-1 kafka-topics --bootstrap-server localhost:9092 \
  --create --topic gmepay.smoke --partitions 1 --replication-factor 1 2>&1 | tail -2
docker exec code-kafka-1 kafka-topics --bootstrap-server localhost:9092 --list 2>&1

echo "--- REDIS: ping ---"
docker exec code-redis-1 redis-cli ping 2>&1

echo "--- POSTGRES: version ---"
docker exec code-postgres-config-1 sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "select version()"' 2>&1 | head -1

echo "--- RAM ---"
free -h | head -2
