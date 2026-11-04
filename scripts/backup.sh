#!/usr/bin/env bash
# Nightly database backup, on the VPS, from cron:
#   17 3 * * * /home/USER/htdocs/SITE/lodugame/scripts/backup.sh >> /home/USER/backups/backup.log 2>&1
# A consistent dump (no locks on InnoDB), checked before it counts, keeping the last 14. The database
# user needs no extra rights. This copy is on the same disk: also copy ~/backups off the server.
set -euo pipefail
cd "$(dirname "$0")/.."
set +u
. "$HOME/.nvm/nvm.sh" >/dev/null
set -u
OUT_DIR="$HOME/backups"
mkdir -p "$OUT_DIR"
# Credentials go to the client through the environment, never on a command line.
eval "$(node -e '
  const u = new URL(require("fs").readFileSync(".env", "utf8").match(/^DATABASE_URL=(.*)$/m)[1].trim());
  const q = (s) => `\x27${decodeURIComponent(s).replace(/\x27/g, `\x27\\\x27\x27`)}\x27`;
  console.log(`export MYSQL_PWD=${q(u.password)} DBU=${q(u.username)} DBH=${q(u.hostname)} DBP=${u.port || 3306} DBN=${q(u.pathname.slice(1))}`);
')"
FILE="$OUT_DIR/ludo-nightly-$(date -u +%Y-%m-%dT%H-%M-%SZ).sql.gz"
umask 077
mariadb-dump --single-transaction --quick -h "$DBH" -P "$DBP" -u "$DBU" "$DBN" 2>/dev/null | gzip > "$FILE"
gzip -t "$FILE"
zcat "$FILE" | tail -1 | grep -q "Dump completed" || { echo "$(date -u) backup INCOMPLETE: $FILE" >&2; exit 1; }
ls -1t "$OUT_DIR"/ludo-nightly-*.sql.gz | tail -n +15 | xargs -r rm --
echo "$(date -u) backup ok: $FILE ($(du -h "$FILE" | cut -f1))"
