import type { EvidenceClaim } from "../content/project";

type EvidenceListProps = {
  evidence: EvidenceClaim[];
};

export function EvidenceList({ evidence }: EvidenceListProps) {
  return (
    <aside className="card p-5">
      <div className="flex items-center gap-2">
        <span className="eyebrow bg-mint/15 text-mint">Evidence</span>
        <h3 className="text-base font-semibold text-ink">源码证据</h3>
      </div>
      <div className="mt-4 space-y-3">
        {evidence.map((item) => (
          <div key={item.claim} className="rounded-xl border border-line bg-bg2 p-4">
            <p className="text-[15px] font-medium leading-7 text-ink">{item.claim}</p>
            <ul className="mt-3 space-y-1.5">
              {item.paths.map((path) => (
                <li
                  key={path}
                  className="flex items-center gap-2 break-all rounded-md bg-bg px-3 py-2 font-mono text-[12px] text-ink2 ring-1 ring-line"
                >
                  <span className="h-1.5 w-1.5 flex-shrink-0 rounded-full bg-brand/70" />
                  {path}
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </aside>
  );
}
