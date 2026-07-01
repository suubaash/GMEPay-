import { useEffect, useMemo, useState } from 'react';
import {
  fetchHealth,
  fetchUseCases,
  runOne,
  runStream,
  type ServiceHealth,
  type Status,
  type UseCaseMeta,
  type UseCaseResult,
} from './api';

const STATUS_ORDER: Status[] = ['PASS', 'FAIL', 'BLOCKED', 'NOT_AUTOMATED'];

export function App() {
  const [useCases, setUseCases] = useState<UseCaseMeta[]>([]);
  const [results, setResults] = useState<Record<string, UseCaseResult>>({});
  const [running, setRunning] = useState<Set<string>>(new Set());
  const [health, setHealth] = useState<ServiceHealth[]>([]);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [view, setView] = useState<'all' | 'use-case' | 'feature'>('all');

  useEffect(() => {
    fetchUseCases().then((d) => {
      setUseCases(d.useCases);
      setResults(d.lastResults);
    });
    refreshHealth();
  }, []);

  function refreshHealth() {
    fetchHealth().then(setHealth);
  }

  function statusOf(id: string): Status {
    if (running.has(id)) return 'RUNNING';
    return results[id]?.status ?? 'IDLE';
  }

  async function single(id: string) {
    setRunning((s) => new Set(s).add(id));
    const r = await runOne(id);
    setResults((m) => ({ ...m, [id]: r }));
    setRunning((s) => {
      const n = new Set(s);
      n.delete(id);
      return n;
    });
  }

  function runMany(query: string, ids: string[]) {
    setBusy(true);
    setRunning(new Set(ids));
    runStream(query, {
      onResult: (r) => {
        setResults((m) => ({ ...m, [r.id]: r }));
        setRunning((s) => {
          const n = new Set(s);
          n.delete(r.id);
          return n;
        });
      },
      onDone: () => {
        setRunning(new Set());
        setBusy(false);
      },
    });
  }

  const counts = useMemo(() => {
    const c: Record<string, number> = { PASS: 0, FAIL: 0, BLOCKED: 0, NOT_AUTOMATED: 0 };
    for (const uc of useCases) {
      const s = results[uc.id]?.status;
      if (s && s in c) c[s]++;
    }
    return c;
  }, [useCases, results]);

  const upCount = health.filter((h) => h.up).length;

  // Group use cases by business scenario, honouring the view filter.
  const groups = useMemo(() => {
    const m = new Map<string, { title: string; items: UseCaseMeta[] }>();
    for (const uc of useCases) {
      if (view !== 'all' && (uc.kind ?? 'use-case') !== view) continue;
      if (!m.has(uc.bs)) m.set(uc.bs, { title: uc.bsTitle, items: [] });
      m.get(uc.bs)!.items.push(uc);
    }
    return [...m.entries()];
  }, [useCases, view]);

  return (
    <div className="app">
      <header>
        <div>
          <h1>GMEPay+ Test Platform</h1>
          <p className="sub">
            {useCases.length} tests ·{' '}
            {(['all', 'use-case', 'feature'] as const).map((v) => (
              <button key={v} className={`vt ${view === v ? 'active' : ''}`} onClick={() => setView(v)}>
                {v === 'all' ? 'All' : v === 'use-case' ? 'Use Cases' : 'Features'}
              </button>
            ))}
          </p>
        </div>
        <div className="actions">
          <button disabled={busy} onClick={() => runMany('filter=mvp', useCases.filter((u) => u.mvp).map((u) => u.id))}>
            ▶ Run MVP
          </button>
          <button disabled={busy} onClick={() => runMany('filter=all', useCases.map((u) => u.id))}>
            ▶ Run All
          </button>
        </div>
      </header>

      <section className="bars">
        <div className="summary">
          {STATUS_ORDER.map((s) => (
            <span key={s} className={`pill ${s}`}>
              {label(s)}: <b>{counts[s] ?? 0}</b>
            </span>
          ))}
        </div>
        <div className="health" title="Click to refresh" onClick={refreshHealth}>
          <span className={upCount === health.length && health.length ? 'dot up' : 'dot down'} />
          Fleet: <b>{upCount}</b>/{health.length || '—'} services up
          <span className="hint">(click to refresh)</span>
        </div>
      </section>

      {upCount === 0 && health.length > 0 && (
        <div className="warn-banner">
          No services are reachable. Start the platform first:{' '}
          <code>cd D:\GMEPay+\code ; .\run-fleet.ps1 -Subset money</code> — then click the fleet badge to refresh.
        </div>
      )}

      {groups.map(([bs, g]) => (
        <div key={bs} className="group">
          <h2>
            <span className="bs">{bs}</span> {g.title}
          </h2>
          {g.items.map((uc) => {
            const st = statusOf(uc.id);
            const res = results[uc.id];
            const open = expanded === uc.id;
            return (
              <div key={uc.id} className={`row ${st}`}>
                <div className="row-main" onClick={() => setExpanded(open ? null : uc.id)}>
                  <span className={`badge ${st}`}>{label(st)}</span>
                  <span className="uc-id">{uc.id}</span>
                  <span className="uc-title">{uc.title}</span>
                  {uc.mvp && <span className="chip mvp">MVP</span>}
                  {!uc.mvp && <span className="chip phase2">Phase 2</span>}
                  {res && <span className="dur">{res.durationMs} ms</span>}
                  <button
                    className="run"
                    disabled={running.has(uc.id) || busy}
                    onClick={(e) => {
                      e.stopPropagation();
                      single(uc.id);
                    }}
                  >
                    {running.has(uc.id) ? '…' : 'Run'}
                  </button>
                </div>
                {open && (
                  <div className="detail">
                    <p className="intent">
                      <b>Intended function:</b> {uc.intent}
                    </p>
                    <p className="services">
                      {uc.services.map((s) => (
                        <span key={s} className="chip svc">
                          {s}
                        </span>
                      ))}
                    </p>
                    {res ? (
                      <div className="steps">
                        {res.steps.map((step, i) => (
                          <div key={i} className={`step ${step.level}`}>
                            <span className="step-msg">{stepIcon(step.level)} {step.msg}</span>
                            {step.detail != null && (
                              <pre className="step-detail">{format(step.detail)}</pre>
                            )}
                          </div>
                        ))}
                        {res.error && <div className="step fail">✗ {res.error}</div>}
                      </div>
                    ) : (
                      <p className="muted">Not run yet — click Run.</p>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      ))}
      <footer>
        Targets the live GMEPay+ fleet over HTTP · separate from the Pay+ codebase ·
        run headless with <code>npm run cli</code>
      </footer>
    </div>
  );
}

function label(s: Status): string {
  return {
    PASS: 'PASS',
    FAIL: 'FAIL',
    BLOCKED: 'BLOCKED',
    NOT_AUTOMATED: 'TODO',
    RUNNING: '…',
    IDLE: 'IDLE',
  }[s];
}

function stepIcon(level: Step['level']): string {
  return { info: '•', pass: '✓', fail: '✗', warn: '⚠', request: '→' }[level] ?? '•';
}
type Step = UseCaseResult['steps'][number];

function format(d: unknown): string {
  if (typeof d === 'string') return d;
  try {
    return JSON.stringify(d, null, 2);
  } catch {
    return String(d);
  }
}
