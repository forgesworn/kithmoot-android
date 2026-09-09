#!/usr/bin/env python3
"""Regenerate Android launcher wrappers and monochrome system marks."""
from pathlib import Path
import xml.etree.ElementTree as ET
import re

root = Path(__file__).resolve().parents[1]
android = root / 'android' if (root / 'android/app').is_dir() else root
res = android / 'app/src/main/res'
source = ET.parse(root / 'art/brand/icon.svg').getroot()
is_bothy = (root / 'android/app').is_dir()
background = '#191510' if is_bothy else '#101114'
scale = .70 if is_bothy else .86
inset = '15%' if is_bothy else '12%'
ns = 'http://schemas.android.com/apk/res/android'

def emit(element):
    tag = element.tag.rsplit('}', 1)[-1]
    if tag == 'path':
        return '<path android:fillColor="#FFFFFFFF" android:pathData="' + element.attrib['d'] + '"/>'
    if tag == 'g':
        transform = element.get('transform', '')
        rotation = re.fullmatch(r'rotate\(([-\d.]+) ([-\d.]+) ([-\d.]+)\)', transform)
        if transform and not rotation:
            raise ValueError('Unsupported source transform: ' + transform)
        attrs = ''
        if rotation:
            attrs = f' android:rotation="{rotation[1]}" android:pivotX="{rotation[2]}" android:pivotY="{rotation[3]}"'
        return '<group' + attrs + '>' + ''.join(emit(child) for child in element) + '</group>'
    return ''

paths = ''.join(emit(child) for child in source)
def write(name, text):
    p = res / name
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text + '\n')

write('drawable/ic_launcher_foreground.xml',
      f'<inset xmlns:android="{ns}" android:insetLeft="{inset}" android:insetRight="{inset}" android:insetTop="{inset}" android:insetBottom="{inset}"><bitmap android:src="@drawable/brand_artwork" android:gravity="fill" android:filter="true"/></inset>')
write('drawable/ic_launcher_monochrome.xml',
      f'<vector xmlns:android="{ns}" android:width="108dp" android:height="108dp" android:viewportWidth="512" android:viewportHeight="512"><group android:scaleX="{scale}" android:scaleY="{scale}" android:pivotX="256" android:pivotY="256">{paths}</group></vector>')
write('values/brand_colors.xml', f'<resources><color name="brand_icon_background">{background}</color></resources>')
for version in ('v26', 'v33'):
    mono = '<monochrome android:drawable="@drawable/ic_launcher_monochrome"/>' if version == 'v33' else ''
    xml = f'<adaptive-icon xmlns:android="{ns}"><background android:drawable="@color/brand_icon_background"/><foreground android:drawable="@drawable/ic_launcher_foreground"/>{mono}</adaptive-icon>'
    for name in ('ic_launcher', 'ic_launcher_round'):
        write(f'mipmap-anydpi-{version}/{name}.xml', xml)
print('Generated sculptural launcher wrappers and monochrome system marks for ' + root.name)
