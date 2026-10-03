import json
d=json.load(open('research/v47_verify/tC/_ss.json',encoding='utf-8'))['data']
d.sort(key=lambda s:-s['time']['created'])
for s in d: print(s['id'], repr(s.get('title'))[:50], s.get('time',{}).get('created'))
