# Run SQL queries (separated by ;;) from stdin against a Perfetto trace:
#   python trace-query.py trace.pftrace < queries.sql
# Needs the perfetto package (pip install perfetto).
import sys
from perfetto.trace_processor import TraceProcessor
tp = TraceProcessor(trace=sys.argv[1])
for q in sys.stdin.read().split(';;'):
    q = q.strip()
    if not q: continue
    print('>>', ' '.join(q.split())[:110])
    try:
        rows = list(tp.query(q))
        if rows:
            cols = list(rows[0].__dict__.keys())
            print(' | '.join(cols))
            for r in rows[:60]:
                print(' | '.join(str(getattr(r, c)) for c in cols))
    except Exception as e:
        print('ERR', e)
