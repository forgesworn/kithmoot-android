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
# KithMoot sits on white: the guide keeps its deep-blue arms off dark grounds.
# The 24% inset keeps the vortex well inside the 66 dp circle every mask keeps.
background = '#191510' if is_bothy else '#FFFFFF'
scale = .70 if is_bothy else .52
inset = '15%' if is_bothy else '24%'
ns = 'http://schemas.android.com/apk/res/android'
xlink = '{http://www.w3.org/1999/xlink}href'
viewport = source.get('viewBox', '0 0 512 512').split()[2]
defs = {element.get('id'): element for element in source.iter() if element.get('id')}

def numbers(text):
    return [float(n) for n in re.split(r'[\s,]+', text.strip()) if n]

# One nested group per SVG transform function: an Android group applies scale,
# rotation and translation in a fixed order, so combining them in one group
# would reorder an SVG list like translate() scale() translate().
def wrap(transform, inner):
    for name, args in reversed(re.findall(r'(\w+)\(([^)]*)\)', transform)):
        n = numbers(args)
        if name == 'translate':
            attrs = f' android:translateX="{n[0]}" android:translateY="{n[1] if len(n) > 1 else 0}"'
        elif name == 'scale':
            attrs = f' android:scaleX="{n[0]}" android:scaleY="{n[1] if len(n) > 1 else n[0]}"'
        elif name == 'rotate':
            attrs = f' android:rotation="{n[0]}"' + (f' android:pivotX="{n[1]}" android:pivotY="{n[2]}"' if len(n) == 3 else '')
        else:
            raise ValueError('Unsupported source transform: ' + transform)
        inner = '<group' + attrs + '>' + inner + '</group>'
    return inner

def emit(element):
    tag = element.tag.rsplit('}', 1)[-1]
    if tag == 'use':
        return wrap(element.get('transform', ''), emit(defs[element.get(xlink, element.get('href', '')).lstrip('#')]))
    if tag == 'path':
        return '<path android:fillColor="#FFFFFFFF" android:pathData="' + element.attrib['d'] + '"/>'
    if tag == 'g':
        return wrap(element.get('transform', ''), ''.join(emit(child) for child in element))
    return ''

paths = ''.join(emit(child) for child in source if child.tag.rsplit('}', 1)[-1] != 'defs')
centre = float(viewport) / 2
def write(name, text):
    p = res / name
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text + '\n')

write('drawable/ic_launcher_foreground.xml',
      f'<inset xmlns:android="{ns}" android:insetLeft="{inset}" android:insetRight="{inset}" android:insetTop="{inset}" android:insetBottom="{inset}"><bitmap android:src="@drawable/brand_artwork" android:gravity="fill" android:filter="true"/></inset>')
write('drawable/ic_launcher_monochrome.xml',
      f'<vector xmlns:android="{ns}" android:width="108dp" android:height="108dp" android:viewportWidth="{viewport}" android:viewportHeight="{viewport}"><group android:scaleX="{scale}" android:scaleY="{scale}" android:pivotX="{centre:g}" android:pivotY="{centre:g}">{paths}</group></vector>')
write('values/brand_colors.xml', f'<resources><color name="brand_icon_background">{background}</color></resources>')
for version in ('v26', 'v33'):
    mono = '<monochrome android:drawable="@drawable/ic_launcher_monochrome"/>' if version == 'v33' else ''
    xml = f'<adaptive-icon xmlns:android="{ns}"><background android:drawable="@color/brand_icon_background"/><foreground android:drawable="@drawable/ic_launcher_foreground"/>{mono}</adaptive-icon>'
    for name in ('ic_launcher', 'ic_launcher_round'):
        write(f'mipmap-anydpi-{version}/{name}.xml', xml)
print('Generated launcher wrappers and monochrome system marks for ' + root.name)
