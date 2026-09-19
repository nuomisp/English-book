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

def long_press_text(prefix):
    for node in snapshot().iter('node'):
        if node.get('text','').startswith(prefix):
            bounds=[int(x) for x in re.findall(r'\d+',node.get('bounds',''))]
            if len(bounds)==4:
                x,y=str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2)
                adb('shell','input','swipe',x,y,x,y,'1200')
                time.sleep(1)
                return
    raise RuntimeError('Reading paragraph missing for native text selection')

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
    tap('打开导航');tap('阅读小屋');tap('A Small Start · 从小开始')
    long_press_text('Lin wants to read English books')
    capture('06-selection-menu')
    tap('词卡 / 朗读')
    assert '词卡' in ET.tostring(snapshot(),encoding='unicode') or '朗读卡' in ET.tostring(snapshot(),encoding='unicode')
    capture('07-selected-text-card')
    adb('shell','input','keyevent','4');time.sleep(1)
    tap('第 1 句');capture('08-sentence-card');tap('收藏句子 / 短语')
    adb('shell','input','keyevent','4');time.sleep(1)
    tap('打开导航');tap('生词本与收藏句');tap('收藏句子');capture('09-saved-sentences')
    tap('打开导航'); tap('凛 · 学习搭档')
    adb('shell','cmd','uimode','night','yes')
    adb('shell','am','force-stop','com.nuomisp.englishbook')
    adb('shell','am','start','-W','-n','com.nuomisp.englishbook/.MainActivity')
    time.sleep(3)
    capture('06-home-dark')
    print('Signed APK: native navigation and six unobscured screenshots verified.')
finally:
    try:
        (out/'last-window.xml').write_text(ET.tostring(snapshot(),encoding='unicode'),encoding='utf-8')
        with (out/'last-window.png').open('wb') as image:
            subprocess.run(['adb','exec-out','screencap','-p'],stdout=image,check=True,timeout=30)
    except Exception:
        pass
    (out/'logcat.txt').write_text(adb('logcat','-d','-t','500'), encoding='utf-8')
