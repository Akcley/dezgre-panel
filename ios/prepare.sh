#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
import json, pathlib, xml.etree.ElementTree as ET
from xml.sax.saxutils import escape
root=pathlib.Path("ios/Assets.xcassets")
root.mkdir(parents=True,exist_ok=True)
(root/"Contents.json").write_text(json.dumps({"info":{"author":"xcode","version":1}}))
ns="{http://schemas.android.com/apk/res/android}"
vector=ET.parse("app/src/main/res/drawable/ic_launcher.xml").getroot()
paths=[]
for p in vector.findall("path"):
    color=p.attrib[ns+"fillColor"].lstrip("#")
    if len(color)==8: color=color[2:]
    paths.append(f'<path fill="#{color}" d="{escape(p.attrib[ns+"pathData"])}"/>')
svg='<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 108 108"><rect width="108" height="108" fill="white"/>'+"".join(paths)+"</svg>"
pathlib.Path("ios/brand.svg").write_text(svg)
for folder,images in [
    ("AppIcon.appiconset",[{"filename":"icon.png","idiom":"universal","platform":"ios","size":"1024x1024"}]),
    ("Brand.imageset",[{"filename":"icon.png","idiom":"universal","scale":"1x"}])]:
    d=root/folder
    d.mkdir(exist_ok=True)
    (d/"Contents.json").write_text(json.dumps({"images":images,"info":{"author":"xcode","version":1}}))
PY
rsvg-convert -w 1024 -h 1024 --background-color white ios/brand.svg -o ios/Assets.xcassets/AppIcon.appiconset/icon.png
sips -s format jpeg ios/Assets.xcassets/AppIcon.appiconset/icon.png --out ios/icon-opaque.jpg >/dev/null
sips -s format png ios/icon-opaque.jpg --out ios/Assets.xcassets/AppIcon.appiconset/icon.png >/dev/null
cp ios/Assets.xcassets/AppIcon.appiconset/icon.png ios/Assets.xcassets/Brand.imageset/icon.png
(cd ios && xcodegen generate)
