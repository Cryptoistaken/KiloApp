import { defineRailway, github, preserve, project, service } from "railway/iac";

// KiloApp project: the Go SMS gateway lives in the "sms core" folder
// (Root Directory), built from its Dockerfile. The Android app in /app
// is built by GitHub Actions, not Railway — this file only owns the gateway.
// Secrets stay in Railway Variables (preserve() = keep dashboard values,
// never write them into source).
export default defineRailway(() => {
  const gateway = service("kilosms-gateway", {
    source: github("Cryptoistaken/KiloApp", {
      branch: "master",
      rootDirectory: "sms core",
    }),
    healthcheck: "/v1/health",
    healthcheckTimeout: 30,
    env: {
      KILO_API_KEY: preserve(),
      ADMIN_KEY: preserve(),
      VOLTX_API_KEY: preserve(),
      MNIT_API_KEY: preserve(),
      ZENEX_API_KEY: preserve(),
    },
  });

  return project("kilosms", {
    resources: [gateway],
  });
});
