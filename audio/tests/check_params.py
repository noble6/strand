#!/usr/bin/env python3
"""Checks that the app's parameter indices match the engine header."""
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[2]
h = (root / 'audio/resona/resona_shm.h').read_text()
body = h[h.index('P_ENABLE = 0'):h.index('P_COUNT')]
header = [n[2:] for n in re.findall(r'\bP_[A-Z0-9_]+', body)]
java = (root / 'app/src/org/strand/engine/resona/ResonaParams.java').read_text()
app = {m[0]: int(m[1]) for m in re.findall(r'static final int (\w+) = (\d+);', java)}
bad = [n for i, n in enumerate(header) if app.get(n) != i]
if bad or app.get('COUNT') != len(header):
    sys.exit(f'ResonaParams.java out of sync with resona_shm.h: {bad or "COUNT"}')
print(f'{len(header)} parameters match')
