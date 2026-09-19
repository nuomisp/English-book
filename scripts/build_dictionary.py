"""Build the reproducible offline subset; upstream CSV stays out of the APK/repo."""
import csv
import hashlib
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
REVISION = 'bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b'
source = pathlib.Path(sys.argv[1])
legacy = {}
for line in (ROOT/'app/src/main/assets/vocabulary.txt').read_text(encoding='utf-8-sig').splitlines():
    if line and not line.startswith('#'):
        f = line.split('|')
        legacy[f[0]] = f

def clean(value):
    return value.replace('\\n', '\n').strip()

def rank(row):
    numbers = [int(row.get(k) or 0) for k in ('frq','bnc')]
    return min([n for n in numbers if n > 0] or [999999])

def normal(value):
    return re.sub(r'\s+', ' ', value.strip().lower().replace('’', "'"))

words = {}
aliases = {}
tag_counts = {'zk':set(),'gk':set(),'cet4':set()}
missing = []
csv.field_size_limit(10_000_000)
with source.open(encoding='utf-8-sig', newline='') as handle:
    for row in csv.DictReader(handle):
        key = normal(row['word'])
        tags = set((row.get('tag') or '').split())
        for tag in tag_counts:
            if tag in tags: tag_counts[tag].add(key)
        selected = bool(tags & {'zk','gk','cet4'}) or key in legacy or (rank(row) <= 12000 and re.fullmatch(r"[a-z]+(?:[' -][a-z]+)*",key))
        if not selected or not key or len(key)>100:
            continue
        meaning = clean(row.get('translation') or '')
        if not meaning:
            meaning = clean(row.get('definition') or '')
        if not meaning:
            missing.append(key)
            continue
        item = {'id':key,'word':row['word'].strip(),'ipa':clean(row.get('phonetic') or ''),
                'meaning':meaning,'tags':sorted(tags & {'zk','gk','cet4'}),'rank':rank(row),
                'forms':clean(row.get('exchange') or ''),'source':'ECDICT','example':'','exampleZh':''}
        if key not in words or len(item['tags'])>len(words[key]['tags']): words[key]=item
for key,f in legacy.items():
    if key not in words:
        words[key]={'id':key,'word':key,'ipa':f[1],'meaning':f[2],'tags':['zk'],'rank':999999,'forms':'','source':'原创起步词'}
    words[key]['example'],words[key]['exampleZh']=f[3],f[4]
    if 'zk' not in words[key]['tags']: words[key]['tags'].append('zk')

for key,item in words.items():
    for form in item['forms'].split('/'):
        kind,sep,values=form.partition(':')
        if sep and kind in {'p','d','i','3','r','t','s'}:
            for value in values.split(','):
                value=normal(value)
                if value and value!=key: aliases.setdefault(value,set()).add(key)
# Explicit lemma rows fill irregular forms as well; no guessed suffix stripping.
with source.open(encoding='utf-8-sig', newline='') as handle:
    for row in csv.DictReader(handle):
        for form in (row.get('exchange') or '').split('/'):
            if form.startswith('0:'):
                for lemma in form[2:].split(','):
                    lemma=normal(lemma)
                    if lemma in words and normal(row['word'])!=lemma:
                        aliases.setdefault(normal(row['word']),set()).add(lemma)

ordered=sorted(words.values(),key=lambda w:(w['rank'],w['id']))
metadata={'name':'ECDICT 精选离线词库','revision':REVISION,'license':'MIT',
          'url':'https://github.com/skywind3000/ECDICT','csvSha256':hashlib.sha256(source.read_bytes()).hexdigest(),
          'total':len(ordered),'foundation':sum('zk' in w['tags'] for w in ordered),
          'highSchool':sum('gk' in w['tags'] for w in ordered),'cet4':sum('cet4' in w['tags'] for w in ordered),
          'upstreamCet4Tagged':len(tag_counts['cet4']),'missingDefinitions':sorted(set(missing)),
          'notes':'考试标签来自开源数据，未声称为当前官方完整考纲；例句仅保留原版100词的原创例句；音频按需生成。'}
output=ROOT/'app/src/main/assets/dictionary.json'
output.write_text(json.dumps({'metadata':metadata,'words':ordered,'aliases':{k:sorted(v) for k,v in sorted(aliases.items())}},ensure_ascii=False,separators=(',',':')),encoding='utf-8')
(ROOT/'app/src/main/assets/dictionary-info.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2),encoding='utf-8')
assert all(key in words for key in legacy)
assert metadata['cet4']==metadata['upstreamCet4Tagged'] and metadata['cet4']>=3500,metadata
assert metadata['foundation']>=1000,metadata
print(json.dumps(metadata,ensure_ascii=False))
print('Asset bytes:',output.stat().st_size)
