#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: install-native.sh [--bin-dir DIRECTORY] [EXECUTABLE]

Install the native Syleks executable as syleks in ~/.local/bin.
EXECUTABLE defaults to target/syleks in this checkout.
An existing syleks installation is replaced. No sudo is required.

Options:
  --bin-dir DIRECTORY  Choose a different installation directory.
  -h, --help           Show this help.
EOF
}

fail() {
    printf 'Error: %s\n' "$*" >&2
    exit 1
}

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
source_binary=
bin_dir=
while (($#)); do
    case "$1" in
        -h|--help) usage; exit 0 ;;
        --bin-dir)
            (($# >= 2)) && [[ -n "$2" ]] || fail '--bin-dir requires a directory.'
            bin_dir=$2
            shift 2
            ;;
        -*) fail "Unknown option: $1" ;;
        *)
            [[ -z "$source_binary" ]] || fail 'Only one executable may be supplied.'
            source_binary=$1
            shift
            ;;
    esac
done

source_binary=${source_binary:-"$repo_root/target/syleks"}
if [[ -z "$bin_dir" ]]; then
    [[ -n "${HOME:-}" ]] || fail 'HOME is unset; supply --bin-dir DIRECTORY.'
    bin_dir=$HOME/.local/bin
fi
[[ -f "$source_binary" && -x "$source_binary" ]] ||
    fail "Native executable not found or not executable: $source_binary. Build it first with mvn -Pnative clean package."

mkdir -p -- "$bin_dir"
bin_dir=$(cd -- "$bin_dir" && pwd -P)
destination=$bin_dir/syleks
[[ ! -d "$destination" ]] || fail "Destination is a directory: $destination"

# Copy beside the destination, then rename so an interrupted copy leaves the old binary intact.
temporary=$(mktemp "$bin_dir/.syleks-install.XXXXXXXX")
trap 'rm -f -- "$temporary"' EXIT
install -m 755 -- "$source_binary" "$temporary"
mv -f -- "$temporary" "$destination"
printf 'Installed %s\n' "$destination"

case ":${PATH:-}:" in
    *":$bin_dir:"*) ;;
    *)
        printf '\nAdd this line to your shell startup file (for Bash: ~/.bashrc), then reload it:\n'
        printf 'export PATH=%q:"$PATH"\n' "$bin_dir"
        ;;
esac
printf '\nRun: syleks --help\n'
