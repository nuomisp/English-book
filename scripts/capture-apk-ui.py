"""Smoke-test the existing signed APK with real Android UI input and screenshots."""
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

out = pathlib.Path('visual-check')
out.mkdir(exist_ok=True)

def adb(*args):
    return subprocess.check_output(['adb', *args], timeout=45).decode('utf-8', errors='replace').strip()

def snapshot():
    adb('shell', 'uiautomator', 'dump', '/sdcard/englishbook-window.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/englishbook-window.xml'))

def tap(label):
    for attempt in range(4):
        root = snapshot()
        for node in root.iter('node'):
            if label in (node.get('text'), node.get('content-desc')):
                bounds = [int(x) for x in re.findall(r'\d+', node.get('bounds', ''))]
                if len(bounds) == 4 and bounds[2]>bounds[0] and bounds[3]>bounds[1]:
                    adb('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))
                    time.sleep(1)
                    return
        adb('shell','input','swipe','300','1400','300','500','350')
    raise RuntimeError(f'Visible native control not found: {label}')

def capture(name):
    root = snapshot()
    xml = ET.tostring(root, encoding='unicode')
    (out / f'{name}.xml').write_text(xml, encoding='utf-8')
    if 'isn\'t responding' in xml or 'not responding' in xml:
        raise RuntimeError('System or application ANR dialog obscures UI')
    if 'com.nuomisp.englishbook' not in xml:
        raise RuntimeError('Application is not the visible foreground window')
    with (out / f'{name}.png').open('wb') as image:
        subprocess.run(['adb','exec-out','screencap','-p'],stdout=image,check=True,timeout=30)

try:
    launcher = adb('shell','cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.HOME').splitlines()[-1].split('/')[0]
    if launcher.startswith(('com.android.', 'com.google.')):
        adb('shell','am','force-stop',launcher)
    if pathlib.Path('previous.apk').exists():
        adb('install','previous.apk')
        adb('shell','am','start','-W','-n','com.nuomisp.englishbook/.MainActivity')
        time.sleep(4)
        tap('打开导航');tap('单词练习');tap('揭晓释义');tap('认识')
        tap('打开导航');tap('凛 · 学习搭档')
        assert '新词 1' in ET.tostring(snapshot(),encoding='unicode'),'Old APK did not save progress'
        capture('00-before-upgrade')
    adb('install', '-r', 'release-apk/app-release.apk')
    adb('shell','am','force-stop','com.nuomisp.englishbook')
    adb('shell','am','start','-W','-n','com.nuomisp.englishbook/.MainActivity')
    time.sleep(4)
    if pathlib.Path('previous.apk').exists():
        assert '新词 1' in ET.tostring(snapshot(),encoding='unicode'),'Upgrade lost existing progress'
    capture('01-home')
    tap('打开导航'); tap('单词练习')
    tap('揭晓释义')
    capture('02-word')
    for destination,name in [('阅读小屋','03-reading'),('听力与听写','04-listening'),('设置','05-settings')]:
        tap('打开导航'); tap(destination); capture(name)
    tap('打开导航'); tap('凛 · 学习搭档')
    adb('shell','cmd','uimode','night','yes')
    adb('shell','am','force-stop','com.nuomisp.englishbook')
    adb('shell','am','start','-W','-n','com.nuomisp.englishbook/.MainActivity')
    time.sleep(3)
    capture('06-home-dark')
    print('Signed APK: native navigation and six unobscured screenshots verified.')
finally:
    (out/'logcat.txt').write_text(adb('logcat','-d','-t','500'), encoding='utf-8')
