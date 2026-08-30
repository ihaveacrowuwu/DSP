#!/usr/bin/env sh
# Generate a self-signed certificate for the demo stack (NFR4).
#
# NFR4 requires TLS in the deployed/demo configuration. NFR9 forbids depending on any
# external service that needs an account, which rules out Let's Encrypt and every
# managed certificate authority - so the demo terminates TLS with a certificate it
# generates itself. A browser will warn about it, and that warning is the honest
# consequence of the key-free constraint rather than a defect, and is documented as such.
#
# Idempotent. Existing certificates are left alone, so `make up-tls` can call this
# every time without invalidating a certificate a browser has already been told to
# trust.
set -eu

DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)/certs"
CRT="$DIR/server.crt"
KEY="$DIR/server.key"
DAYS="${TLS_DAYS:-365}"

mkdir -p "$DIR"

if [ -f "$CRT" ] && [ -f "$KEY" ]; then
    echo "certificate already present: $CRT"
    openssl x509 -in "$CRT" -noout -subject -dates
    exit 0
fi

# The SANs matter more than the subject: browsers and Go's TLS stack have ignored
# commonName for years, so a certificate without subjectAltName is rejected outright
# rather than merely warned about.
openssl req -x509 -newkey rsa:2048 -sha256 -days "$DAYS" -nodes \
    -keyout "$KEY" -out "$CRT" \
    -subj "/C=MV/O=Muraka Reef Watch/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,DNS:muraka.local,IP:127.0.0.1,IP:::1" \
    -addext "keyUsage=digitalSignature,keyEncipherment" \
    -addext "extendedKeyUsage=serverAuth" 2>/dev/null

chmod 600 "$KEY"
echo "generated a self-signed certificate valid for $DAYS days:"
openssl x509 -in "$CRT" -noout -subject -dates -ext subjectAltName
