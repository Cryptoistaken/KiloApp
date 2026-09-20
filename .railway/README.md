# Railway IaC — KiloApp

`.railway/railway.ts` owns the Railway side of this repo: the Go SMS gateway
in `sms core/` (TypeScript IaC; do NOT add `railway.json`/`railway.toml` —
Config as Code is deprecated and ignored for new services).

Deploy source = `Cryptoistaken/KiloApp@master`, Root Directory = `sms core`
(the Dockerfile there is picked up automatically). The Android app in `/app`
is built by `.github/workflows/build.yml`, never by Railway.

```bash
railway login
railway link
railway config plan     # preview, safe
railway config apply    # confirm, then apply
```

Secrets (`KILO_API_KEY`, `ADMIN_KEY`, `VOLTX_API_KEY`, `MNIT_API_KEY`,
`ZENEX_API_KEY`) are `preserve()`: set them in the Railway dashboard
Variables tab, never in this file.
