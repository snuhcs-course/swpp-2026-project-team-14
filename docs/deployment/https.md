# HTTPS deployment

The Android release variant uses `https://swpp26t14.147-46-242-169.sslip.io`.
`infra/apps/https.yaml` adds a separate TLS ingress to the existing backend;
the existing HTTP ingress remains available for previously distributed debug apps.
There is no forced HTTP redirect: several Android clients deliberately reject redirects.

## Infrastructure prerequisites

- Public DNS must resolve the hostname to the school's public frontend.
- Public TCP 443 must reach this team's Traefik `websecure` entrypoint, using
  SNI passthrough to `10.226.0.24:443` if the school frontend is shared.
- HTTP requests to `/.well-known/acme-challenge/*` must reach the team's HTTP
  ingress on port 80 without a login, bot challenge or redirect loop.
- The cluster must have cert-manager installed before ArgoCD syncs `https.yaml`.

The certificate and ACME account keys stay in Kubernetes Secrets in `team14`.
They are not repository files. The namespaced Issuer uses Let's Encrypt HTTP-01;
cert-manager handles certificate issuance, renewal and key rotation.
An operator may add an ACME contact email to the Issuer; it is optional.

## One-time certificate controller installation

The observed cluster is K3s/Kubernetes 1.36.5. cert-manager 1.21 supports Kubernetes
1.33–1.36; pin the tested patch version below rather than a floating latest URL.
See [supported releases](https://cert-manager.io/docs/releases/) and
[installation](https://cert-manager.io/docs/installation/kubectl/).

On the team server:

```bash
sudo k3s kubectl apply --server-side -f https://github.com/cert-manager/cert-manager/releases/download/v1.21.3/cert-manager.yaml
sudo k3s kubectl -n cert-manager rollout status deployment/cert-manager
sudo k3s kubectl -n cert-manager rollout status deployment/cert-manager-cainjector
sudo k3s kubectl -n cert-manager rollout status deployment/cert-manager-webhook
```

This is a cluster prerequisite outside ArgoCD's existing `main/infra/apps` scope.
Do not install a second controller if one already exists.

## Issue and route the certificate

First verify HTTP-01 using a separate staging Issuer/Certificate with
`https://acme-staging-v02.api.letsencrypt.org/directory` and distinct resource/Secret
names. Do not attach a staging certificate to a distributed app's endpoint.
If staging validation fails, fix that cause before requesting production certificates.

After staging succeeds and the public network prerequisites are satisfied,
from a checkout of this change on the server:

```bash
sudo k3s kubectl apply --dry-run=server -f infra/apps/https.yaml
sudo k3s kubectl apply -f infra/apps/https.yaml
sudo k3s kubectl -n team14 get issuer,certificate,ingress
sudo k3s kubectl -n team14 describe certificate backend-tls
```

The expected resources are Issuer `letsencrypt-http`, Certificate/Secret
`backend-tls`, and Ingress `backend-https`. Wait for the Issuer and Certificate
to become Ready. The HTTPS ingress forwards to the same `backend:8000` service;
MySQL and photo storage are unchanged.

Validate from outside the school network, with normal certificate verification:

```bash
curl --fail https://swpp26t14.147-46-242-169.sslip.io/healthz/
curl -i https://swpp26t14.147-46-242-169.sslip.io/api/users/me/
```

Expect health JSON with HTTP 200 and HTTP 401 for the unauthenticated account
request. Also verify login and authenticated photo/media requests using a test
account. Do not use `curl -k`, trust-all code or disabled hostname checks as proof
that store-distributed clients can connect.

Only merge the endpoint switch and distribute the rebuilt app after the public
checks pass. ArgoCD can then manage the new resources from `main/infra/apps`.

## Android configuration and checks

- Debug: `stylemate.apiBaseUrl`, default `http://10.0.2.2:8000`.
- Release: `stylemate.releaseApiBaseUrl`, default the public HTTPS hostname above.
- Both accept Gradle `-P` overrides or `frontend/local.properties`; `-P` wins.
- A release URL must have an HTTPS scheme and valid host, without credentials,
  query or fragment. The release manifest explicitly blocks cleartext traffic.
- Existing debug HTTP development remains available. API/image paths are relative,
  so individual repositories do not need scheme changes.

From `frontend/` (PowerShell):

```powershell
.\gradlew.bat assembleDebug assembleRelease testDebugUnitTest lintDebug lintRelease -Pstylemate.apiBaseUrl=https://swpp26t14.147-46-242-169.sslip.io
```

`assembleRelease` currently produces an unsigned APK. Store upload still needs a
separate release signing setup; this change does not create or replace signing keys.

## Validation and remaining blocker — 19 November 2026

- SSH confirmed the real team14 backend runs image `sha-a996ee6f7c91629949de6988910e17e31e647dd4`.
- cert-manager 1.21.3 installed; all three controller pods are Ready.
- Production and staging manifests passed Kubernetes server-side dry-run validation.
- Existing external HTTP health remains 200. Internal port 443 routes health to the
  backend, but the current certificate is the self-signed Traefik default; that
  diagnostic is not trusted-TLS verification.
- A separate temporary-CA integration check verified the real ingress's TLS chain
  and hostname with normal verification (`--cacert`, no `-k`), health 200 and
  unauthenticated account/wardrobe 401 responses. Its temporary Ingress, Secret and
  keys were removed immediately. This tests internal wiring, not public CA trust
  or authenticated/media success.
- The staging ACME attempt failed: Let's Encrypt reported `redirect loop detected`
  while fetching the HTTP-01 challenge. Team Traefik recorded successful challenge
  responses and no 3xx responses during that attempt. The upstream/public validation
  path requires investigation; no production certificate was requested.
- External port 443 connections were refused. Public trusted HTTPS, authenticated
  HTTPS/media and a release-device session remain unverified.
- Debug/release APK builds and all 375 JVM tests passed. Both lint variants passed
  with 0 errors (38 warnings and 1 hint each). The release APK's compiled
  code contains the HTTPS endpoint and its manifest has `usesCleartextTraffic=false`.
  Default/local debug URLs, release HTTPS overrides and HTTP-release rejection
  were checked with real Gradle configuration tasks.

The staging test Certificate, Issuer and ACME account Secret were removed after diagnosis.
The certificate controller remains installed for the eventual production rollout.
Keep this PR in draft and leave the working HTTP deployment in place until the
school frontend prerequisites are resolved.

Request to the school infrastructure operator:

> Please enable HTTPS for `swpp26t14.147-46-242-169.sslip.io`, either with SNI TCP
> passthrough to team14's `10.226.0.24:443` or a supported TLS endpoint. Please also
> allow unauthenticated HTTP-01 requests on port 80 at `/.well-known/acme-challenge/*`;
> Let's Encrypt staging currently fails with a redirect loop.

## Renewal and rollback

Monitor `kubectl -n team14 get certificate backend-tls` for Ready state and
`kubectl -n team14 describe certificate backend-tls` for renewal events. Port 80's
challenge path must continue to work for renewals.

If the new route must be withdrawn after merging, first remove its Ingress from Git
and sync ArgoCD. Because `prune` is false, then manually delete only Ingress
`backend-https`; deleting it before changing Git allows self-heal to recreate it.
For an urgent rollback, pause automated sync/self-heal before a temporary deletion.
Keep the certificate/account Secrets for recovery. Release clients must use a last
known working HTTPS endpoint/release or have distribution held; HTTP fallback is
for debug builds only. The existing HTTP ingress, database and media PVCs need no reset.
