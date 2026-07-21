import { useState } from 'react';
import { fmtRange } from '../../utils/format';
import { analysisAPI } from '../../services/api';

const SEVERITY = {
  CRITICAL: 'bg-red-100 text-red-800 border-red-200',
  ERROR: 'bg-orange-100 text-orange-800 border-orange-200',
  WARN: 'bg-yellow-100 text-yellow-800 border-yellow-200',
  INFO: 'bg-blue-100 text-blue-800 border-blue-200',
};

const SEVERITY_ORDER = ['CRITICAL', 'ERROR', 'WARN', 'INFO'];

export default function FindingsTab({ sessionId, findings }) {
  const [severity, setSeverity] = useState('');

  if (!findings || findings.length === 0) {
    return (
      <div className="bg-white rounded-xl border border-gray-200 p-10 text-center">
        <p className="text-sm font-medium text-gray-700">No findings</p>
        <p className="mt-1 text-sm text-gray-400">
          The enrichment pass didn't flag anything for this session.
        </p>
      </div>
    );
  }

  const present = SEVERITY_ORDER.filter((s) => findings.some((f) => f.severity === s));
  const shown = severity ? findings.filter((f) => f.severity === severity) : findings;

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2">
        <button
          onClick={() => setSeverity('')}
          className={`px-2.5 py-1 rounded-md text-xs font-medium border ${
            severity === '' ? 'bg-gray-800 text-white border-gray-800' : 'bg-white text-gray-600 border-gray-200'
          }`}
        >
          All ({findings.length})
        </button>
        {present.map((s) => (
          <button
            key={s}
            onClick={() => setSeverity(s)}
            className={`px-2.5 py-1 rounded-md text-xs font-medium border ${
              severity === s ? 'ring-2 ring-offset-1 ring-gray-400' : ''
            } ${SEVERITY[s]}`}
          >
            {s} ({findings.filter((f) => f.severity === s).length})
          </button>
        ))}
      </div>

      {shown.map((f) => (
        <FindingCard key={f.id} sessionId={sessionId} finding={f} />
      ))}
    </div>
  );
}

function FindingCard({ sessionId, finding: f }) {
  const [open, setOpen] = useState(false);
  const [evidence, setEvidence] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const count = f.evidenceChunkIds?.length || 0;

  const toggle = async () => {
    const next = !open;
    setOpen(next);
    if (next && evidence == null && count > 0) {
      setLoading(true);
      setError('');
      try {
        const res = await analysisAPI.evidence(sessionId, f.evidenceChunkIds);
        setEvidence(res.data || []);
      } catch {
        setError('Could not load evidence.');
        setEvidence([]);
      } finally {
        setLoading(false);
      }
    }
  };

  return (
    <div className="bg-white rounded-xl border border-gray-200 p-4">
      <div className="flex items-center gap-2 flex-wrap">
        <span
          className={`px-2 py-0.5 rounded-full text-[11px] font-semibold border ${
            SEVERITY[f.severity] || SEVERITY.INFO
          }`}
        >
          {f.severity}
        </span>
        {f.category && (
          <span className="text-[11px] text-gray-500 bg-gray-100 rounded px-1.5 py-0.5">
            {f.category}
          </span>
        )}
        {f.occurrenceCount > 1 && (
          <span className="text-[11px] text-gray-500">×{f.occurrenceCount}</span>
        )}
      </div>
      <h4 className="mt-1.5 text-sm font-semibold text-gray-900">{f.title}</h4>
      {f.explanation && (
        <p className="mt-2 text-sm text-gray-600 leading-relaxed">{f.explanation}</p>
      )}
      <div className="mt-2 flex items-center gap-3 text-[11px] text-gray-400">
        <span>{fmtRange(f.timeRangeStart, f.timeRangeEnd)}</span>
        {count > 0 && (
          <button
            onClick={toggle}
            className="text-indigo-600 hover:text-indigo-700 font-medium"
          >
            {open ? 'Hide evidence' : `${count} evidence`}
          </button>
        )}
      </div>

      {open && count > 0 && (
        <div className="mt-3 space-y-1">
          {loading && <p className="text-[11px] text-gray-400">Loading evidence…</p>}
          {error && <p className="text-[11px] text-red-500">{error}</p>}
          {evidence?.map((ev) => (
            <div key={ev.chunkId}>
              {(ev.lineStart != null || ev.lineEnd != null) && (
                <p className="text-[10px] text-gray-400 mb-0.5">
                  lines {ev.lineStart}–{ev.lineEnd}
                </p>
              )}
              <pre className="text-[11px] bg-gray-900 text-gray-100 rounded-md p-2 overflow-x-auto whitespace-pre-wrap break-words">
                {ev.content}
              </pre>
            </div>
          ))}
          {evidence?.length === 0 && !loading && (
            <p className="text-[11px] text-gray-400">No evidence lines found.</p>
          )}
        </div>
      )}
    </div>
  );
}
