#!/bin/sh
set -eu

echo "[small-rag] starting all-in-one runtime"
exec /usr/bin/supervisord -c /app/supervisord-all-in-one.conf -n
