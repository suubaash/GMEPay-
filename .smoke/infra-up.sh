#!/bin/bash
# Bring up + verify the GMEPay infra subset under systemd-managed Docker (persistent).
echo "--- systemd state ---"
systemctl is-system-running 2>/dev/null || true
echo "--- enable+start docker.service ---"
systemctl enable --now docker >/dev/null 2>&1 || true
sleep 6
docker version --format 'engine={{.Server.Version}}' 2>&1

cd /mnt/d/GMEPay+/code || exit 1
echo "--- compose up: postgres-config redis zookeeper kafka ---"
docker compose up -d postgres-config redis zookeeper kafka 2>&1 | tail -8

echo "--- wait kafka healthy ---"
for i in $(seq 1 30); do
  s=$(docker inspect -f '{{.State.Health.Status}}' code-kafka-1 2>/dev/null)
  echo "kafka: $s"
  [ "$s" = "healthy" ] && break
  sleep 5
done

echo "--- KAFKA topic create + list ---"
# --partitions must match KAFKA_NUM_PARTITIONS in docker-compose.yml (3), not 1.
# Kafka assigns WHOLE partitions to consumers, so a 1-partition topic caps every consumer group at
# one working thread no matter what spring.kafka.listener.concurrency or the replica count says --
# i.e. bootstrapping through this script would silently re-impose the single-consumer ceiling the
# Kafka work removed. Replication stays 1: this is a single-broker dev stack, stated rather than
# defaulted into (same reasoning as KAFKA_DEFAULT_REPLICATION_FACTOR in compose).
docker exec code-kafka-1 kafka-topics --bootstrap-server localhost:9092 \
  --create --topic gmepay.smoke --partitions 3 --replication-factor 1 2>&1 | tail -1
docker exec code-kafka-1 kafka-topics --bootstrap-server localhost:9092 --list 2>&1

echo "--- REDIS ping ---"
docker exec code-redis-1 redis-cli ping 2>&1

echo "--- docker ps ---"
docker ps --format 'table {{.Names}}\t{{.Status}}' 2>&1
free -h | head -2
