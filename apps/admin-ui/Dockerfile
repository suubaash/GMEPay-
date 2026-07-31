FROM node:25-alpine AS deps
WORKDIR /app
COPY apps/admin-ui/package.json apps/admin-ui/package-lock.json* ./
RUN npm ci --no-audit --no-fund || npm install --no-audit --no-fund
FROM node:25-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY apps/admin-ui ./
RUN npm run build
FROM node:25-alpine AS runtime
WORKDIR /app

# ---------------------------------------------------------------------------
# Run unprivileged — gap T5-5. This was the 23rd of the 24 images running as
# root (CISO audit 2026-07-28 §14); apps/partner-portal-ui was the only one that
# already dropped privileges, and this block deliberately mirrors its
# nextjs:1001 identity so the chart needs ONE runAsUser value for both SPAs.
# ---------------------------------------------------------------------------
RUN addgroup --system --gid 1001 nodejs \
 && adduser --system --uid 1001 --ingroup nodejs nextjs

# `next start` writes into .next/cache at runtime (ISR + the image optimiser),
# so .next must be owned by the run user. Everything else is read-only to it.
COPY --from=build --chown=nextjs:nodejs /app/.next ./.next
COPY --from=build /app/public ./public
COPY --from=build /app/package.json ./
COPY --from=build /app/node_modules ./node_modules

USER 1001:1001

EXPOSE 3000
ENV NODE_ENV=production
CMD ["npm","start"]
