#!/bin/sh
# Installiert NaviDroid für den aktuellen Benutzer (ohne root):
#   ~/.local/opt/navidroid  +  Starter im Anwendungsmenü.
# Voraussetzung: VLC (Arch: pacman -S vlc, Debian/Ubuntu: apt install vlc, Fedora: dnf install vlc).
set -e
SRC="$(cd "$(dirname "$0")" && pwd)/navidroid"
[ -d "$SRC" ] || SRC="$(cd "$(dirname "$0")" && pwd)"
DEST="$HOME/.local/opt/navidroid"
rm -rf "$DEST"
mkdir -p "$DEST" "$HOME/.local/share/applications" "$HOME/.local/share/icons/hicolor/256x256/apps" "$HOME/.local/bin"
cp -r "$SRC"/. "$DEST"/
cp "$DEST/lib/navidroid.png" "$HOME/.local/share/icons/hicolor/256x256/apps/navidroid.png" 2>/dev/null || true
ln -sf "$DEST/bin/navidroid" "$HOME/.local/bin/navidroid"
cat > "$HOME/.local/share/applications/navidroid.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=NaviDroid
GenericName=Musik-Player
Comment=Navidrome-Client
Exec=$DEST/bin/navidroid
Icon=navidroid
Categories=AudioVideo;Audio;Player;
StartupWMClass=dev-navidroid-desktop-MainKt
Terminal=false
DESKTOP
command -v update-desktop-database >/dev/null && update-desktop-database "$HOME/.local/share/applications" || true
command -v vlc >/dev/null || echo "Hinweis: VLC ist nicht installiert – ohne VLC kann NaviDroid nichts abspielen."
echo "NaviDroid installiert: $DEST (Start über das Anwendungsmenü oder 'navidroid')"
