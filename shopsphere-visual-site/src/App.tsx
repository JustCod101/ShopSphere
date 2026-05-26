import { useMemo, useState } from "react";
import { ArchitectureMap } from "./components/ArchitectureMap";
import { EvidenceList } from "./components/EvidenceList";
import { MermaidDiagram } from "./components/MermaidDiagram";
import { RequestFlow } from "./components/RequestFlow";
import { interviewQuestions, modules, pages, requestSteps, site, type Page, type PageId } from "./content/project";

const navItems = pages.map((page) => ({ id: page.id, title: page.title }));

const pageHasStageDiagram = (id: PageId) =>
  id === "overview" || id === "architecture" || id === "modules" || id === "flow";

export default function App() {
  const [activePageId, setActivePageId] = useState<PageId>("overview");
  const [activeModuleId, setActiveModuleId] = useState(modules[0].id);
  const [activeStepId, setActiveStepId] = useState(requestSteps[0].id);
  const activePage = useMemo(() => pages.find((page) => page.id === activePageId) ?? pages[0], [activePageId]);

  // first diagram becomes the hero (used by non-interactive pages)
  const heroMermaid = activePage.diagrams[0];
  const secondaryDiagrams = pageHasStageDiagram(activePage.id) ? activePage.diagrams : activePage.diagrams.slice(1);

  return (
    <div className="min-h-screen text-ink">
      {/* === Top Nav === */}
      <header className="sticky top-0 z-30 border-b border-line bg-bg/85 backdrop-blur-xl">
        <div className="mx-auto flex max-w-7xl flex-col gap-3 px-4 py-3 lg:flex-row lg:items-center lg:justify-between lg:px-6">
          <div className="flex items-center gap-3">
            <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-brand to-reco font-extrabold text-bg shadow-glow">
              S
            </span>
            <div className="leading-tight">
              <p className="text-[11px] font-semibold uppercase tracking-[0.18em] text-muted">
                {site.sourcePlan}
              </p>
              <h1 className="text-base font-bold text-ink">{site.title}</h1>
            </div>
          </div>
          <nav className="flex flex-wrap gap-1.5" aria-label="Primary">
            {navItems.map((item) => {
              const active = activePageId === item.id;
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => setActivePageId(item.id)}
                  className={`rounded-lg px-3 py-1.5 text-[14px] font-medium transition focus:outline-none focus:ring-2 focus:ring-brand/40 ${
                    active
                      ? "bg-brand text-bg shadow-glow"
                      : "border border-line bg-surface text-ink2 hover:border-brand/40 hover:text-ink"
                  }`}
                >
                  {item.title}
                </button>
              );
            })}
          </nav>
        </div>
      </header>

      {/* === Page header (slim) === */}
      <section className="mx-auto max-w-7xl px-4 pt-7 lg:px-6">
        <div className="flex items-center gap-2 text-[12px] text-muted">
          <span>ShopSphere</span>
          <span>·</span>
          <span className="font-semibold text-brand">{activePage.eyebrow}</span>
        </div>
        <h2 className="mt-2 text-3xl font-extrabold leading-tight md:text-[40px]">
          <span className="text-gradient">{activePage.title}</span>
        </h2>
        <p className="mt-2 max-w-3xl text-[15px] leading-7 text-muted">{activePage.hero}</p>
      </section>

      {/* === ★ STAGE (full bleed) === */}
      <section className="stage mt-6">
        <div className="stage-inner">
          <StageContent
            page={activePage}
            activeModuleId={activeModuleId}
            setActiveModuleId={setActiveModuleId}
            activeStepId={activeStepId}
            setActiveStepId={setActiveStepId}
            heroMermaid={heroMermaid}
          />
        </div>
      </section>

      {/* === Below-the-fold supporting content === */}
      <main className="mx-auto max-w-7xl px-4 py-10 lg:px-6">
        {/* Explain Cards */}
        <section className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
          <ExplainCard title="这个模块做什么" text={activePage.explanation.what} tone="brand" emoji="🧭" />
          <ExplainCard title="怎么运行" text={activePage.explanation.how} tone="cyan" emoji="⚙️" />
          <ExplainCard title="为什么这么设计" text={activePage.explanation.why} tone="orange" emoji="💡" />
          <ExplainCard title="面试怎么讲" text={activePage.explanation.interview} tone="purple" emoji="🎤" accent />
        </section>

        {/* Remember + Evidence */}
        <section className="mt-8 grid gap-5 xl:grid-cols-[1fr_0.85fr]">
          <div className="card p-5">
            <div className="flex items-center gap-2">
              <span className="eyebrow bg-gold/15 text-gold">Remember</span>
              <h3 className="text-base font-semibold text-ink">面试者重点记住</h3>
            </div>
            <ul className="mt-4 grid gap-3 md:grid-cols-3">
              {activePage.remember.map((item, i) => (
                <li
                  key={item}
                  className="relative rounded-xl border border-line bg-bg2 p-4 pl-11 text-[15px] leading-7 text-ink"
                >
                  <span className="absolute left-3 top-3 flex h-7 w-7 items-center justify-center rounded-md bg-gold/15 text-[12px] font-bold text-gold">
                    {String(i + 1).padStart(2, "0")}
                  </span>
                  {item}
                </li>
              ))}
            </ul>
          </div>
          <EvidenceList evidence={activePage.evidence} />
        </section>

        {/* Secondary mermaid diagrams */}
        {secondaryDiagrams.length > 0 ? (
          <section className="mt-8 grid gap-5 xl:grid-cols-2">
            {secondaryDiagrams.map((diagram) => (
              <MermaidDiagram key={diagram.title} title={diagram.title} description={diagram.description} code={diagram.code} />
            ))}
          </section>
        ) : null}

        {/* Interview mode (always at bottom on its own page) */}
        {activePage.id === "interview" ? (
          <section className="mt-8">
            <InterviewMode />
          </section>
        ) : null}

        <footer className="mt-12 flex items-center justify-between border-t border-line pt-5 text-[12px] text-muted">
          <span>{site.subtitle}</span>
          <span className="font-mono">source · {site.sourceAnalysis}</span>
        </footer>
      </main>
    </div>
  );
}

/* === Stage content selector === */
function StageContent({
  page,
  activeModuleId,
  setActiveModuleId,
  activeStepId,
  setActiveStepId,
  heroMermaid
}: {
  page: Page;
  activeModuleId: string;
  setActiveModuleId: (id: string) => void;
  activeStepId: string;
  setActiveStepId: (id: string) => void;
  heroMermaid?: Page["diagrams"][number];
}) {
  if (page.id === "overview" || page.id === "architecture" || page.id === "modules") {
    return <ArchitectureMap modules={modules} activeId={activeModuleId} onSelect={setActiveModuleId} />;
  }
  if (page.id === "flow") {
    return <RequestFlow steps={requestSteps} activeId={activeStepId} onSelect={setActiveStepId} />;
  }
  if (page.id === "interview") {
    return <InterviewStage />;
  }
  if (heroMermaid) {
    return <MermaidDiagram variant="hero" title={heroMermaid.title} description={heroMermaid.description} code={heroMermaid.code} />;
  }
  return null;
}

/* === Stage cover for interview tab === */
function InterviewStage() {
  return (
    <div className="grid h-full place-items-center">
      <div className="max-w-3xl text-center">
        <span className="eyebrow bg-reco/15 text-reco">Interview Mode</span>
        <h2 className="mt-3 text-3xl font-extrabold text-ink md:text-4xl">把项目讲成可追问的故事</h2>
        <p className="mt-3 text-[15px] leading-7 text-muted">
          下面四张卡片覆盖最高频追问：库存为什么不用纯 DB、下单为什么不直接发 MQ、推荐为什么不跨库、业务层为什么不验 JWT。
          每张卡都给出 <span className="font-semibold text-mint">标准回答</span> ·{" "}
          <span className="font-semibold text-risk">不要这么说</span> ·{" "}
          <span className="font-semibold text-brand">源码证据</span>。
        </p>
        <div className="mt-6 flex justify-center gap-2">
          {["Q1", "Q2", "Q3", "Q4"].map((q, i) => (
            <span
              key={q}
              className="flex h-10 w-10 items-center justify-center rounded-xl text-sm font-bold"
              style={{
                background: ["rgba(77,168,255,0.16)", "rgba(167,139,250,0.16)", "rgba(34,211,238,0.16)", "rgba(255,138,77,0.16)"][i],
                color: ["#4DA8FF", "#A78BFA", "#22D3EE", "#FF8A4D"][i]
              }}
            >
              {q}
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}

function ExplainCard({
  title,
  text,
  tone,
  emoji,
  accent = false
}: {
  title: string;
  text: string;
  tone: "brand" | "cyan" | "orange" | "purple";
  emoji: string;
  accent?: boolean;
}) {
  const colors: Record<typeof tone, { bar: string; text: string; tint: string }> = {
    brand: { bar: "bg-brand", text: "text-brand", tint: "bg-brand/10" },
    cyan: { bar: "bg-cyanflow", text: "text-cyanflow", tint: "bg-cyanflow/10" },
    orange: { bar: "bg-stock", text: "text-stock", tint: "bg-stock/10" },
    purple: { bar: "bg-reco", text: "text-reco", tint: "bg-reco/12" }
  } as const;
  const c = colors[tone];
  return (
    <article className={`card relative overflow-hidden p-5 ${accent ? c.tint : ""}`}>
      <span className={`absolute inset-x-0 top-0 h-0.5 ${c.bar}`} />
      <div className="flex items-start gap-3">
        <span className="text-xl" aria-hidden>
          {emoji}
        </span>
        <div>
          <h3 className={`text-base font-bold ${accent ? c.text : "text-ink"}`}>{title}</h3>
          <p className="mt-2 text-[14px] leading-7 text-ink2">{text}</p>
        </div>
      </div>
    </article>
  );
}

function InterviewMode() {
  return (
    <div className="grid gap-5 xl:grid-cols-2">
      {interviewQuestions.map((item, i) => (
        <article key={item.question} className="card relative overflow-hidden p-6">
          <span className="absolute inset-x-0 top-0 h-1 bg-gradient-to-r from-brand via-reco to-cyanflow" />
          <div className="flex items-start gap-3">
            <span className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-lg bg-brand/15 text-base font-bold text-brand">
              Q{i + 1}
            </span>
            <h3 className="text-lg font-bold leading-tight text-ink">{item.question}</h3>
          </div>

          <section className="mt-4 rounded-xl border border-mint/40 bg-mint/10 p-4">
            <div className="flex items-center gap-2">
              <span className="h-1.5 w-1.5 rounded-full bg-mint" />
              <p className="eyebrow text-mint">标准回答</p>
            </div>
            <p className="mt-2 text-[15px] leading-7 text-ink">{item.answer}</p>
          </section>

          <section className="mt-3 rounded-xl border border-risk/40 bg-risk/10 p-4">
            <div className="flex items-center gap-2">
              <span className="h-1.5 w-1.5 rounded-full bg-risk" />
              <p className="eyebrow text-risk">不要这么说</p>
            </div>
            <p className="mt-2 text-[15px] leading-7 text-ink">{item.avoid}</p>
          </section>

          <section className="mt-3 rounded-xl border border-line bg-bg2 p-4">
            <p className="eyebrow text-muted">源码证据</p>
            <ul className="mt-2 space-y-1.5">
              {item.evidence.map((path) => (
                <li
                  key={path}
                  className="flex items-center gap-2 break-all rounded-md bg-bg px-3 py-2 font-mono text-[12px] text-ink2 ring-1 ring-line"
                >
                  <span className="h-1.5 w-1.5 flex-shrink-0 rounded-full bg-brand/70" />
                  {path}
                </li>
              ))}
            </ul>
          </section>
        </article>
      ))}
    </div>
  );
}
