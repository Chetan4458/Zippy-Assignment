#!/bin/sh
set -eu

port="${SERVER_PORT:-8080}"
exec curl --fail --silent --show-error "http://127.0.0.1:${port}/api/health"
