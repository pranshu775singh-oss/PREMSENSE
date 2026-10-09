#!/usr/bin/env bash
# Generates the test certificate chains used by the verifier's attestation tests.
#
# These are TEST certificates under a made-up root. They look like Android key attestation chains
# (a leaf carrying the 1.3.6.1.4.1.11129.2.1.17 KeyDescription extension) so the verifier's real
# parsing and chain checks run against real DER. They are never trusted by the shipped roots file.
#
# Usage: verifier/scripts/gen-attestation-fixtures.sh   (needs openssl and python)
set -euo pipefail
# Git Bash on Windows rewrites arguments that start with "/" (like -subj /CN=x) unless told not to.
export MSYS_NO_PATHCONV=1

out="$(cd "$(dirname "$0")/.." && pwd)/src/test/resources/attestation"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"

mkdir certs
: > index.txt
echo 01 > serial

cat > ca.cnf <<'CNF'
[ ca ]
default_ca = CA_default
[ CA_default ]
database = index.txt
new_certs_dir = certs
serial = serial
default_md = sha256
policy = anything
unique_subject = no
copy_extensions = none
[ anything ]
commonName = supplied
CNF

cat > ca.ext <<'EXT'
basicConstraints = critical, CA:TRUE
keyUsage = critical, keyCertSign, cRLSign
EXT

newkey() { openssl ecparam -name prime256v1 -genkey -noout -out "$1"; }

# issue <csr> <signer.key> <signer.pem|-selfsign> <ext file> <start> <end> <out.pem>
issue() {
  local csr="$1" key="$2" cert="$3" ext="$4" start="$5" end="$6" out="$7"
  if [ "$cert" = "-selfsign" ]; then
    openssl ca -config ca.cnf -batch -notext -selfsign -in "$csr" -keyfile "$key" \
      -startdate "$start" -enddate "$end" -extfile "$ext" -out "$out" 2>/dev/null
  else
    openssl ca -config ca.cnf -batch -notext -in "$csr" -keyfile "$key" -cert "$cert" \
      -startdate "$start" -enddate "$end" -extfile "$ext" -out "$out" 2>/dev/null
  fi
}

csr() { openssl req -new -key "$1" -subj "/CN=$2" -out "$3"; }

START=20200101000000Z
END=21200101000000Z

# --- the made-up root and intermediate
newkey root.key;  csr root.key "PermSense Test Attestation Root" root.csr
issue root.csr root.key -selfsign ca.ext $START $END root.pem
newkey int.key;   csr int.key "PermSense Test Attestation Intermediate" int.csr
issue int.csr root.key root.pem ca.ext $START $END int.pem

# --- an unrelated root and chain, for "not anchored to the roots you trust"
newkey otherroot.key; csr otherroot.key "Some Other Root" otherroot.csr
issue otherroot.csr otherroot.key -selfsign ca.ext $START $END otherroot.pem
newkey otherint.key;  csr otherint.key "Some Other Intermediate" otherint.csr
issue otherint.csr otherroot.key otherroot.pem ca.ext $START $END otherint.pem

# --- the key that signs reports in tests
newkey leaf.key
csr leaf.key "Android Keystore Key" leaf.csr

# challenge: 32 bytes 01 02 ... 20
python - <<'PY' > keydesc.txt
def tlv(tag, content):
    n = len(content)
    if n < 0x80: length = bytes([n])
    elif n < 0x100: length = bytes([0x81, n])
    else: length = bytes([0x82, n >> 8, n & 0xFF])
    return bytes([tag]) + length + content
def integer(v): return tlv(0x02, bytes([v]))
def enum(v): return tlv(0x0A, bytes([v]))
def octets(b): return tlv(0x04, b)
challenge = bytes(range(1, 33))
for name, level in (("tee", 1), ("strongbox", 2), ("software", 0)):
    seq = tlv(0x30, integer(4) + enum(level) + integer(41) + enum(level) + octets(challenge) + octets(b"") + tlv(0x30, b"") + tlv(0x30, b""))
    print(name, seq.hex())
bad = tlv(0x30, integer(4) + octets(b"not an enum"))
print("malformed", bad.hex())
PY

while read -r name hex; do
  printf 'basicConstraints = critical, CA:FALSE\nkeyUsage = critical, digitalSignature\n1.3.6.1.4.1.11129.2.1.17 = DER:%s\n' "$hex" > "leaf_$name.ext"
  issue leaf.csr int.key int.pem "leaf_$name.ext" $START $END "leaf_$name.pem"
done < keydesc.txt

# a leaf with no attestation record at all, one that is not yet valid, and one that has expired
printf 'basicConstraints = critical, CA:FALSE\n' > plain.ext
issue leaf.csr int.key int.pem plain.ext $START $END leaf_noext.pem
issue leaf.csr int.key int.pem leaf_tee.ext 21000101000000Z 21100101000000Z leaf_future.pem
issue leaf.csr int.key int.pem leaf_tee.ext 20100101000000Z 20150101000000Z leaf_expired.pem

# a TEE leaf issued by the unrelated chain
issue leaf.csr otherint.key otherint.pem leaf_tee.ext $START $END leaf_other.pem

# the leaf private key, PKCS#8 DER in base64, for the tests to sign with
openssl pkcs8 -topk8 -nocrypt -in leaf.key -outform DER | base64 -w0 > leaf_key.pk8.b64

cp root.pem int.pem otherroot.pem otherint.pem leaf_*.pem leaf_key.pk8.b64 "$out/"
echo "wrote fixtures to $out"
ls "$out"
