# AWS backend handoff

`deploy.sh --target=aws` uses `scripts/deploy-blue-green.py`, not the legacy stop-first path. Run from a committed production-composition checkout retaining the currently deployed source ancestry. The tool clones the server-side container environment; it does not upload a new secret file or reset feature flags. Non-AWS deployment behavior is unchanged.

The root-only host tool takes release directory, expected current JAR SHA-256, candidate JAR SHA-256 and source revision. The release directory must be a new direct child of `/opt/logicomanet-be/releases`. It locks both backend and frontend release files, checks the current artifact and proxy configuration again before switching, pins the current JVM image, preserves JVM/resource/mount/log settings, and requires at least 512 MiB available host memory. Backups are verified gzip SQL dumps with binary data and root-only permissions.

This handoff accepts **identical Flyway migration inventories and hashes only**. Schema changes require a separate compatibility plan. Candidate traffic binds to Docker's host bridge at port 18080/18081, not an additional public listener.

The candidate starts with a server-local `-Dlogicoma.release.activation-file=...` marker absent. Health GETs remain available, while business requests wait up to 45 seconds before authentication and execution. Heartbeat, Playground matching and expiry schedulers remain passive. No client header can activate the instance. The normal runtime without this property behaves as before.

After candidate readiness, the tool updates the existing bind-mounted nginx configuration inode and validates it. It reloads nginx without restarting it, waits for the captured old worker PIDs to exit (maximum 20 seconds), then stops the old JVM (15-second bound), disables its restart policy and activates the candidate. This preserves the single-business-writer condition required by process-local Skill publication locks. There is no speculative retry of POSTs or replay of committed requests. New requests can wait briefly during the drain; this is not a promise of zero added latency.

An unready candidate never receives public traffic and is removed while the old service continues. Errors after routing trigger automatic restoration of the old JAR/container/proxy; such a fault recovery may add latency or fail requests and is not advertised as zero downtime. Previously stopped containers have restart disabled so a host reboot cannot bring back a second writer. The successful active instance is named `logicomanet-be`; `/opt/logicomanet-be/active-release.json` records its port and rollback container. Operational health probes must use the nginx/public health route or that active port, not assume localhost:8080.

All Docker snapshots and `runtime.env` stay private on the server. Do not download or commit them. `/opt/logicomanet-be/app.jar` remains the current artifact fingerprint, while the JVM loads its immutable release-specific candidate JAR. Retain old release directories and migration data for recovery.

Checks: `python3 scripts/test-deploy-blue-green.py`, `bash scripts/test-deploy-runtime.sh`, `bash scripts/test-deploy-backup-gate.sh`, and Maven tests. Production acceptance must separately include an intentional readiness failure and continuous nginx/public requests across an actual successful switch.
