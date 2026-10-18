# School CPU deployment

## Existing GitOps flow

Work is prepared on `main-merge-1017` and submitted to `main` by PR.
The existing Backend deploy workflow builds backend changes merged into main, publishes
`ghcr.io/snuhcs-course/swpp-2026-project-team-14-backend:sha-<source commit>`,
updates both backend and migrate image references in `infra/apps/backend.yaml`,
and commits that image update to main. ArgoCD continues watching **main / infra/apps**.
No extra deployment branch is used. The workflow needs permission to push its deployment
commit to main under the repository's existing protection/ruleset configuration.

## School A host

Public API: `http://swpp26t14.147-46-242-169.sslip.io`.
K3s Traefik receives school-forwarded HTTP on port 80 and routes the hostname to backend:8000.
MySQL runs on the same A node and is reachable only through its ClusterIP Service.
The school blocks direct A-to-B communication; B is not a database replica or failover node.
Frontend CI builds debug APKs with this fixed API address. Local Android builds still read
`frontend/local.properties`; set `stylemate.apiBaseUrl` there when building on the PC.
The supplied school endpoint is HTTP; the current release app requires HTTPS.

## One-time prerequisites on the server

- K3s and ArgoCD installed. The observed LXC kubelet needs
  `KubeletInUserNamespace=true`; this is server setup, not an application Deployment option.
- Namespace `team14` exists before adding Secrets.
- `mysql-secret`: MYSQL_DATABASE=stylemate, MYSQL_USER=stylemate,
  MYSQL_PASSWORD and MYSQL_ROOT_PASSWORD (different strong random passwords).
- `backend-secret`: matching MYSQL_PASSWORD, fixed DJANGO_SECRET_KEY and GEMINI_API_KEY.
- `ghcr-secret`: GHCR image pull credentials if the image is private; a public image can
  use a valid dockerconfigjson Secret with empty auths.
- For a private GitHub repository, ArgoCD also needs read-only repository credentials.
- Register `infra/argocd/team14.yaml` only after the main image publication and Secrets
  are ready. The Application itself is outside its managed `infra/apps` directory.

Secrets are created on the server and are never committed to Git or included in APKs.
The backend ConfigMap contains only non-secret configuration.

## Data and startup

MySQL and garment photos each request an 8Gi local-path PVC. Local-path storage shares
the server's 40Gi disk and is not a backup or necessarily a strict size quota.
PVCs and their consumers use the same sync wave for WaitForFirstConsumer provisioning.
The photo mount grants the backend's uid/gid 10001 group write access through fsGroup.
The migrate initContainer applies existing Django migrations before each new backend
starts. If MySQL is still initializing, Kubernetes retries the initContainer.
The rolling update keeps the old backend until the new one becomes ready; schema changes
must therefore remain compatible with the running version.

ArgoCD retains prune=false and selfHeal=true. Pod replacement preserves PVC data;
server deletion does not. Back up DB and photos off the school server before its scheduled deletion.
Backend limits of 2 CPU / 3Gi and MySQL limits of 1 CPU / 2Gi are initial budgets,
not measured throughput or memory guarantees.

## Validation on the target cluster

```bash
sudo k3s kubectl apply --dry-run=server -f infra/apps
sudo k3s kubectl apply -f infra/argocd/team14.yaml
sudo k3s kubectl -n argocd get application team14
sudo k3s kubectl -n team14 get pods,pvc,svc,ingress
```

Health checks establish HTTP availability; separately verify registration, login,
garment analysis/storage and outfit recommendation. Real school deployment and credentials
cannot be validated solely by inspecting repository manifests.
