#!/bin/sh
set -eu

runtime="${SMALL_RAG_RUNTIME:-all-in-one}"
case "${runtime}" in
    all-in-one)
        config="/app/supervisord-all-in-one.conf"
        ;;
    app-only)
        config="/app/supervisord-app-only.conf"
        ;;
    *)
        echo "[small-rag] unknown SMALL_RAG_RUNTIME: ${runtime} (expected all-in-one|app-only)" >&2
        exit 64
        ;;
esac

echo "[small-rag] runtime=${runtime} config=${config}"
exec /usr/bin/supervisord -c "${config}" -n
