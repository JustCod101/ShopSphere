import type { RequestStep } from "../content/project";

type RequestFlowProps = {
  steps: RequestStep[];
  activeId: string;
  onSelect: (id: string) => void;
};

const accentMap: Record<RequestStep["accent"], { bg: string; stroke: string; soft: string; text: string }> = {
  blue: { bg: "rgba(77,168,255,0.16)", stroke: "#4DA8FF", soft: "rgba(77,168,255,0.08)", text: "#A8D3FF" },
  cyan: { bg: "rgba(34,211,238,0.16)", stroke: "#22D3EE", soft: "rgba(34,211,238,0.08)", text: "#88EEFB" },
  orange: { bg: "rgba(255,138,77,0.16)", stroke: "#FF8A4D", soft: "rgba(255,138,77,0.08)", text: "#FFC7A6" },
  purple: { bg: "rgba(167,139,250,0.16)", stroke: "#A78BFA", soft: "rgba(167,139,250,0.08)", text: "#D4C6FC" },
  red: { bg: "rgba(248,113,113,0.16)", stroke: "#F87171", soft: "rgba(248,113,113,0.08)", text: "#FBC4C4" },
  green: { bg: "rgba(52,211,153,0.16)", stroke: "#34D399", soft: "rgba(52,211,153,0.08)", text: "#9DEDC9" }
};

export function RequestFlow({ steps, activeId, onSelect }: RequestFlowProps) {
  const active = steps.find((step) => step.id === activeId) ?? steps[0];
  const activeIndex = steps.findIndex((step) => step.id === active.id);
  const activeColor = accentMap[active.accent];

  return (
    <div className="space-y-6">
      {/* === Top header === */}
      <div className="flex items-end justify-between gap-4">
        <div>
          <span className="eyebrow bg-mint/15 text-mint">Request Flow · 下单主链路</span>
          <h2 className="mt-2 text-2xl font-bold text-ink md:text-3xl">从客户端到库存 TCC 的 6 步</h2>
          <p className="mt-1 text-sm text-muted">点击任意编号或步骤卡 → 右侧切换讲解。</p>
        </div>
        <span className="rounded-lg border border-line bg-bg2 px-3 py-1.5 font-mono text-[13px] text-ink">
          step {activeIndex + 1} / {steps.length}
        </span>
      </div>

      {/* === Big horizontal timeline === */}
      <div className="rounded-2xl border border-line bg-bg2/60 p-5 backdrop-blur">
        <div className="relative">
          <div className="absolute left-2 right-2 top-6 h-px bg-line" />
          <div
            className="absolute left-2 top-6 h-px transition-all"
            style={{
              width: `calc(${((activeIndex + 1) / steps.length) * 100}% - 16px)`,
              background: `linear-gradient(90deg, ${accentMap[steps[0].accent].stroke}, ${activeColor.stroke})`
            }}
          />
          <div className="relative grid gap-2" style={{ gridTemplateColumns: `repeat(${steps.length}, minmax(0, 1fr))` }}>
            {steps.map((step, index) => {
              const c = accentMap[step.accent];
              const isActive = step.id === active.id;
              const isPast = index < activeIndex;
              return (
                <button
                  key={step.id}
                  type="button"
                  onClick={() => onSelect(step.id)}
                  className="group flex flex-col items-center gap-2.5 px-1 text-left"
                >
                  <span
                    className="flex h-12 w-12 items-center justify-center rounded-full text-[15px] font-extrabold transition"
                    style={{
                      background: isActive || isPast ? c.stroke : "#131E36",
                      color: isActive || isPast ? "#0B1220" : "#B4C0DA",
                      boxShadow: isActive ? `0 0 0 5px ${c.bg}, 0 8px 24px ${c.bg}` : `inset 0 0 0 1.5px ${"#2E3F62"}`
                    }}
                  >
                    {index + 1}
                  </span>
                  <span
                    className={`text-center text-[13px] font-semibold leading-tight transition ${
                      isActive ? "text-ink" : "text-muted group-hover:text-ink"
                    }`}
                  >
                    {step.label.replace(/^\d+\.\s*/, "")}
                  </span>
                </button>
              );
            })}
          </div>
        </div>
      </div>

      <div className="grid gap-5 lg:grid-cols-[1.2fr_1fr]">
        {/* === Vertical timeline === */}
        <div className="rounded-2xl border border-line bg-bg2/60 p-5 backdrop-blur">
          <h3 className="text-base font-semibold text-ink">分步骤展开</h3>
          <p className="mb-4 text-[13px] text-muted">点击任意步骤切换右侧讲解。</p>
          <ol className="relative space-y-3">
            {steps.map((step, index) => {
              const c = accentMap[step.accent];
              const isActive = step.id === active.id;
              return (
                <li key={step.id} className="grid grid-cols-[52px_1fr] gap-4">
                  <div className="flex flex-col items-center">
                    <span
                      className="flex h-12 w-12 items-center justify-center rounded-full text-base font-extrabold transition"
                      style={{
                        background: isActive ? c.stroke : "#131E36",
                        color: isActive ? "#0B1220" : c.text,
                        boxShadow: isActive
                          ? `0 0 0 5px ${c.bg}, 0 12px 30px ${c.bg}`
                          : `inset 0 0 0 1.5px ${c.stroke}55`
                      }}
                    >
                      {index + 1}
                    </span>
                    {index < steps.length - 1 ? (
                      <span
                        className="mt-1.5 w-px flex-1"
                        style={{
                          background: isActive ? c.stroke : "#243352",
                          opacity: isActive ? 0.6 : 1
                        }}
                      />
                    ) : null}
                  </div>
                  <button
                    type="button"
                    onClick={() => onSelect(step.id)}
                    className="rounded-xl border p-4 text-left transition focus:outline-none"
                    style={{
                      borderColor: isActive ? c.stroke : "#243352",
                      background: isActive ? c.soft : "#131E36",
                      boxShadow: isActive ? `0 12px 32px ${c.bg}` : "none"
                    }}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <p className="text-[15px] font-semibold text-ink">{step.label.replace(/^\d+\.\s*/, "")}</p>
                      <span
                        className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold uppercase tracking-wider"
                        style={{ background: c.bg, color: c.text }}
                      >
                        {step.actor}
                      </span>
                    </div>
                    <p className="mt-2 text-[14px] leading-7 text-ink2">{step.summary}</p>
                  </button>
                </li>
              );
            })}
          </ol>
        </div>

        {/* === Detail panel === */}
        <aside className="relative overflow-hidden rounded-2xl border border-line bg-bg2/60 p-6 backdrop-blur">
          <div className="absolute inset-x-0 top-0 h-1" style={{ background: activeColor.stroke }} />
          <div className="flex items-center gap-3">
            <span
              className="flex h-14 w-14 items-center justify-center rounded-2xl text-lg font-extrabold"
              style={{
                background: activeColor.bg,
                color: activeColor.stroke,
                boxShadow: `inset 0 0 0 1.5px ${activeColor.stroke}55`
              }}
            >
              {activeIndex + 1}
            </span>
            <div>
              <p className="eyebrow text-muted">Current step</p>
              <h3 className="text-xl font-bold text-ink">{active.label.replace(/^\d+\.\s*/, "")}</h3>
            </div>
          </div>

          <p
            className="mt-3 inline-flex items-center gap-2 rounded-lg px-3 py-1.5 text-[13px] font-medium"
            style={{ background: activeColor.bg, color: activeColor.text }}
          >
            <span className="h-1.5 w-1.5 rounded-full" style={{ background: activeColor.stroke }} />
            {active.actor}
          </p>

          <DetailSection title="HOW · 怎么运行" body={active.summary} color={activeColor.stroke} />
          <DetailSection title="WHY · 为什么这么设计" body={active.why} color={activeColor.stroke} />

          <section className="mt-5">
            <div className="flex items-center gap-2">
              <span className="h-1.5 w-7 rounded-full" style={{ background: activeColor.stroke }} />
              <h4 className="text-[12px] font-semibold uppercase tracking-[0.16em] text-muted">Evidence · 源码证据</h4>
            </div>
            <ul className="mt-2 space-y-1.5">
              {active.evidence.map((path) => (
                <li
                  key={path}
                  className="break-all rounded-md border border-line bg-bg px-3 py-2 font-mono text-[12px] text-ink2"
                >
                  {path}
                </li>
              ))}
            </ul>
          </section>
        </aside>
      </div>
    </div>
  );
}

function DetailSection({ title, body, color }: { title: string; body: string; color: string }) {
  return (
    <section className="mt-5">
      <div className="flex items-center gap-2">
        <span className="h-1.5 w-7 rounded-full" style={{ background: color }} />
        <h4 className="text-[12px] font-semibold uppercase tracking-[0.16em] text-muted">{title}</h4>
      </div>
      <p className="mt-2 rounded-xl border border-line bg-bg p-3.5 text-[15px] leading-7 text-ink">{body}</p>
    </section>
  );
}
