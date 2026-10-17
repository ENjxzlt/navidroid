#!/bin/sh
# Baut navidroid-extras.ndp mit TinyGo im Docker-Container.
# Das PDK kommt aus dem Navidrome-Repository in der Version, die auf dem Server läuft.
set -e
cd "$(dirname "$0")"
ND_VERSION="${ND_VERSION:-v0.64.2}"
if [ ! -d .pdk/plugins/pdk/go ] || [ "$(cat .pdk/VERSION 2>/dev/null)" != "$ND_VERSION" ]; then
  rm -rf .pdk
  git clone -q --depth 1 --branch "$ND_VERSION" --filter=blob:none --sparse https://github.com/navidrome/navidrome.git .pdk
  (cd .pdk && git sparse-checkout set --no-cone /plugins/pdk/go/)
  echo "$ND_VERSION" > .pdk/VERSION
fi
docker run --rm -v "$PWD":/src -w /src -e HOME=/tmp -u "$(id -u):$(id -g)" tinygo/tinygo:0.39.0 \
  sh -c 'go test ./... && tinygo build -o plugin.wasm -target wasip1 -buildmode=c-shared .'
rm -f navidroid-extras.ndp
python3 -c "import zipfile; z=zipfile.ZipFile('navidroid-extras.ndp','w',zipfile.ZIP_DEFLATED); z.write('manifest.json'); z.write('plugin.wasm'); z.close()"
rm -f plugin.wasm
ls -la navidroid-extras.ndp
