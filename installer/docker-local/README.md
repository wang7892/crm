# Custom Cordys CRM 1.6.0 on Docker Desktop

This setup packages the current modified source, including the desktop and mobile frontends. It uses the project's existing Windows MySQL configuration and runs its own Redis container.

Requirements: Docker Desktop with Linux containers, JDK 21, Maven, Node.js compatible with the project, pnpm, and the locally installed `rtk` command.

Run from this directory in PowerShell:

```powershell
./build.ps1
./backup-database.ps1
./start.ps1
```

The URL is http://localhost:8081/. Use the accounts already stored in your existing CRM database. The separate official `cordys-crm` container uses host port 8181 for its internal port 8081.

`build.ps1` checks and builds both frontends, packages the backend, and builds `cordys-crm-custom:1.6.0`. Backend tests are skipped during packaging. The Docker build context contains only the application JAR; host configuration, credentials, and business files are not included in the image.

`start.ps1` mounts `../../cordys-crm.properties` read-only and maps loopback database and LLM URLs to `host.docker.internal`. Logs, uploads, and task attachments use the project's `runtime` directory. Existing general CRM files are mounted from `E:/opt/cordys/data/files` when present. Alternate settings are available through `-ConfigPath`, `-FilesDirectory`, and `-Port`.

The Redis container has no published port. Its generated password is stored in the ignored `.env` file and reused on subsequent starts.

Startup connects to the existing MySQL database and applies the project's Flyway migrations. Back up that database before a first startup or upgrade. Do not run an older CRM version against the same database at the same time. Knowledge documents whose stored paths are Windows absolute paths may require a separate path migration before opening them in Linux.

```powershell
docker compose stop
docker compose up -d
docker compose logs --tail 100 crm
```

After changing source code, run `build.ps1` and then `start.ps1` again. Stopping containers preserves the host files and Redis data volume.
