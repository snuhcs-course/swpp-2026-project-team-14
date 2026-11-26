<!-- AI-generated with Claude Code (Claude Opus 5.5), 2026-11-26, review pending (Kihwan Kim) -->
# Monitoring, alerts and backup

## Scope

The school deployment has one K3s node, one backend replica and one MySQL instance.
The school blocks A-to-B traffic and the release APK accepts only the fixed school host,
so there is **no failover**. Process failures are restarted by Kubernetes (probes,
Deployment controller, ArgoCD selfHeal). Data failures are covered by a daily backup,
a KakaoTalk alert and the manual restore steps below.

```
exporters ──▶ Prometheus (1 hour, emptyDir) ──▶ Alertmanager ──▶ kakao-notifier ──▶ KakaoTalk "send to me"
```

| Manifest | Namespace | Purpose |
|---|---|---|
| `infra/apps/backup.yaml` | team14 | `backup-data` PVC, `mysql-backup` and `media-backup` CronJobs |
| `infra/apps/mysqld-exporter.yaml` | team14 | `mysql_up`, reusing the app account in `mysql-secret` |
| `infra/monitoring/*` | monitoring | Prometheus, rules, Alertmanager, kube-state-metrics, node-exporter, kakao-notifier |
| `infra/argocd/monitoring.yaml` | argocd | ArgoCD Application for `infra/monitoring` |

## Alerts

| Alert | Condition | Severity |
|---|---|---|
| NodeNotReady | node not Ready for 1m (inhibits every other alert) | critical |
| BackendDown | no backend Pod passing `/healthz/` for 1m | critical |
| MySQLDown | `mysql_up == 0` for 1m | critical |
| TraefikDown | no available Traefik replica for 1m | critical |
| TargetDown | a scrape target is down for 2m | warning |
| Backend5xxHigh | 5xx ratio > 5 % for 5m, with at least 20 requests in 5m | critical |
| BackendRestarting | more than 2 backend restarts in 15m | warning |
| NodeDiskLow | less than 15 % free on the node root filesystem for 10m | critical |
| BackupFailed | a backup CronJob has not succeeded for 2 days | warning |

Alerts are grouped by name and repeated every 4 hours; a resolved message is sent on recovery.

## Backup

| Target | Schedule (KST) | Kept | File |
|---|---|---|---|
| MySQL `stylemate` (all tables) | daily 04:00 | 7 | `/backup/mysql/stylemate-<UTC time>.sql` |
| `wardrobe-media` photos | daily 04:30 | 1 | `/backup/media/wardrobe-media.tar.gz` |

Both jobs write a temporary file, verify it (`-- Dump completed` last line / `tar -tzf`) and only
then replace the previous backup. Backups share the node disk: they protect against data
corruption, accidental deletion and bad migrations, **not** against loss of the server.
Data loss is at most one day. DB and photo backups are taken at different times, so a few restored
garments may point to a missing photo file. Virtual fitting results live only in backend memory
and are not backed up.

## One-time setup on the server

1. Check headroom before enabling: `df -h /`, `sudo k3s kubectl top node`, and the photo size:
   `sudo k3s kubectl -n team14 exec deploy/backend -- du -sh /app/private/wardrobe-media`.
2. Confirm the bundled Traefik exposes a container port named `metrics`:
   `sudo k3s kubectl -n kube-system get pod -l app.kubernetes.io/name=traefik -o jsonpath='{.items[*].spec.containers[*].ports}'`.
3. Create the Kakao Secret (never commit it). Issue the refresh token for the account that
   should receive alerts, with the `talk_message` consent item enabled.
   ```bash
   sudo k3s kubectl -n monitoring create secret generic kakao-secret \
     --from-literal=KAKAO_REST_API_KEY=... \
     --from-literal=KAKAO_REFRESH_TOKEN=...
   # add --from-literal=KAKAO_CLIENT_SECRET=... only if Client Secret is enabled
   ```
   The namespace is created by the Application; create it first with
   `sudo k3s kubectl create namespace monitoring` if the Secret is made before the first sync.
4. Register the Application: `sudo k3s kubectl apply -f infra/argocd/monitoring.yaml`.
5. `backup-data` uses WaitForFirstConsumer, so it stays Pending and the team14 Application shows
   Progressing until the first backup Job runs. Run the manual backup Jobs (Verification) right after sync.
6. Prometheus, Alertmanager and kakao-notifier do not reload ConfigMaps. After changing them:
   `sudo k3s kubectl -n monitoring rollout restart deploy/prometheus deploy/alertmanager deploy/kakao-notifier`.

## Verification

```bash
# Run a backup now instead of waiting for the schedule
sudo k3s kubectl -n team14 create job --from=cronjob/mysql-backup mysql-backup-manual
sudo k3s kubectl -n team14 create job --from=cronjob/media-backup media-backup-manual
sudo k3s kubectl -n team14 get jobs

# Inspect the files (temporary Pod mounting the backup PVC)
sudo k3s kubectl -n team14 run backup-shell --rm -it --restart=Never --image=busybox:1.37 \
  --overrides='{"spec":{"securityContext":{"runAsUser":10001,"runAsGroup":10001},"containers":[{"name":"backup-shell","image":"busybox:1.37","stdin":true,"tty":true,"volumeMounts":[{"name":"b","mountPath":"/backup"}]}],"volumes":[{"name":"b","persistentVolumeClaim":{"claimName":"backup-data"}}]}}'
#   ls -l /backup/mysql /backup/media
#   tail -n 1 /backup/mysql/stylemate-*.sql      # -- Dump completed on ...
#   tar -tzf /backup/media/wardrobe-media.tar.gz > /dev/null && echo ok

# Prometheus targets and firing alerts
sudo k3s kubectl -n monitoring port-forward svc/prometheus 9090:9090   # open /targets and /alerts

# Alert path: stop MySQL briefly (announce to the team, never during alpha/beta)
sudo k3s kubectl -n team14 scale deploy/mysql --replicas=0   # KakaoTalk firing within ~2 minutes
sudo k3s kubectl -n team14 scale deploy/mysql --replicas=1   # KakaoTalk resolved
```

## Runbook

| Alert | Action |
|---|---|
| BackendDown / BackendRestarting / Backend5xxHigh | `kubectl -n team14 logs deploy/backend --previous`. If caused by a deploy, `git revert` the `deploy: backend sha-…` commit on main; ArgoCD rolls back to the previous image. |
| MySQLDown | Check `kubectl -n team14 describe pod -l app=mysql` and logs. Restore the DB only if data is corrupted. |
| NodeDiskLow | `sudo k3s crictl rmi --prune` to remove unused images, then check `du` on the PVCs. |
| BackupFailed | `kubectl -n team14 logs job/<failed job>`; rerun with `create job --from=cronjob/...`. |
| NodeNotReady | Ask the school to restart the server. local-path data stays on the disk and the apps start again with K3s. |
| TargetDown | Check the named exporter Pod; alerts depending on it are silent until it recovers. |

### Restore the database

```bash
sudo k3s kubectl -n team14 scale deploy/backend --replicas=0          # stop writes
# copy the chosen dump out of backup-data (backup-shell above), then:
sudo k3s kubectl -n team14 exec -i deploy/mysql -- \
  sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE"' < stylemate-<time>.sql
sudo k3s kubectl -n team14 scale deploy/backend --replicas=1
```

### Restore the photos

```bash
sudo k3s kubectl -n team14 scale deploy/backend --replicas=0
# in a temporary Pod mounting both wardrobe-media and backup-data as uid/gid 10001:
#   tar -xzf /backup/media/wardrobe-media.tar.gz -C /media
sudo k3s kubectl -n team14 scale deploy/backend --replicas=1
```

## Limits

- Prometheus, Alertmanager and the notifier run on the same node: if the node or server is lost,
  no alert is sent.
- Only the owner of the Kakao refresh token receives alerts. Refresh tokens expire (about two
  months); replace `kakao-secret` before expiry and when the notifier logs that a new token was issued.
- Prometheus keeps one hour of data in an emptyDir (rules look back at most 15 minutes); history is lost on restart.
