# ADR-002 — Edge layer: Nginx (TLS/WAF) layered in front of Spring Cloud Gateway

**Status:** Accepted (user decision, 2026-06-10)
**Ticket:** 18.7-G01

## Context
Tile board says **Nginx**; the built `api-gateway` service uses **Spring Cloud Gateway** (HMAC + idempotency filters already implemented and tested). The architecture diagram shows a WAF at the edge.

## Decision
**Both, layered.**
- **Nginx** at the very edge: TLS termination, WAF (OWASP CRS), request-id injection, gzip, static/UI routing (`/admin`, `/portal`), coarse rate limiting (tickets 18.2-G01/G02).
- **Spring Cloud Gateway** behind it as the *API* gateway: partner-facing API routing, HMAC verification, idempotency, per-key rate limits (existing WBS 8.x work stands).

## Consequences
- No rework of the built SCG filters; WBS 8.x tickets remain valid.
- Nginx becomes the single public entrypoint in compose/K8s; SCG is never internet-facing.
- Matches both source images simultaneously (tile board's Nginx, diagram's WAF + gateway).
