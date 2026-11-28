"""Taps a control on the emulator by its visible text. Usage: ui.py tap 'text' [seconds]"""
import re, subprocess, sys, time
import xml.etree.ElementTree as ET

def nodes():
    subprocess.run(['adb', 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml'], capture_output=True)
    text = subprocess.run(['adb', 'shell', 'cat', '/sdcard/ui.xml'], capture_output=True, text=True).stdout
    try:
        return list(ET.fromstring(text).iter('node'))
    except ET.ParseError:
        return []

wanted, limit = sys.argv[2].lower(), float(sys.argv[3]) if len(sys.argv) > 3 else 40
end = time.time() + limit
seen = []
while time.time() < end:
    found = nodes()
    seen = [n.get('text') for n in found if n.get('text')]
    for node in found:
        if wanted in (node.get('text') or '').lower() and node.get('clickable') == 'true':
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
            if y2 - y1 > 4:
                subprocess.run(['adb', 'shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2)])
                print('tapped', node.get('text'))
                sys.exit(0)
    # The test phone is held sideways, so swipe within whatever size the screen is right now.
    if found:
        _, _, width, height = map(int, re.findall(r'\d+', found[0].get('bounds')))
        x, y = str(width // 2), (height * 3 // 4, height // 3)
        subprocess.run(['adb', 'shell', 'input', 'swipe', x, str(y[0]), x, str(y[1]), '400'])
    time.sleep(2)
print('not found:', wanted, '| on screen:', seen[:30])
sys.exit(1)
