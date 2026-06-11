# syntax=docker/dockerfile:1.7
# Multi-stage build for @gmepay/partner-portal-ui (Next.js 14 standalone)
# Mirrors the admin-ui template; only difference is the exposed port (3001 vs 3000).

# ---------- deps ----------
FROM node:20-alpine AS deps
WORKDIR /app
COPY package.json ./
# package-lock.json is generated outside this scaffold; copy if present.
COPY package-lock.json* ./
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi

# ---------- builder ----------
FROM node:20-alpine AS builder
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

# ---------- runner ----------
FROM node:20-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3001
ENV HOSTNAME=0.0.0.0

RUN addgroup --system --gid 1001 nodejs \
 && adduser --system --uid 1001 nextjs

COPY --from=builder /app/public ./public
COPY --from=builder --chown=nextjs:nodejs /app/.next ./.next
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json ./package.json

USER nextjs

EXPOSE 3001

CMD ["npm", "run", "start"]
