# ADR-004 — Container base images: Rocky Linux 9

**Status:** Accepted by default (tile board mandate; uncontested — flag to user if it ever bites)
**Ticket:** 18.7-G01

## Context
The tile board specifies **Rocky Linux** as the OS. Current `docker-compose.yml` uses upstream images (eclipse-temurin, node, postgres…), which is fine for local dev.

## Decision
Production/staging **application images** are built on `rockylinux:9-minimal` + Temurin 21 JRE (Java services) and Node 20 (UIs). Local-dev compose may keep upstream convenience images; the Dockerfiles introduced in R5 (`platform-infra` 14.x) use the Rocky base.

## Consequences
- Satisfies the tile-board OS requirement where it matters (what GME operates in prod).
- Slightly larger images vs alpine/distroless; accepted for glibc compatibility and enterprise patching cadence.
- Off-the-shelf infra (postgres, kafka, redis, mongo, nginx) runs official upstream images — Rocky applies to *our* services only.
