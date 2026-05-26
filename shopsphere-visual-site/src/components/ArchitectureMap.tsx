import { useEffect, useState } from "react";
import type { Module, ModuleIconKey } from "../content/project";
import { architectureEdges } from "../content/project";
import {
  ClientIcon,
  GatewayIcon,
  InfraIcon,
  OrderIcon,
  ProductIcon,
  RecommendationIcon,
  UserIcon
} from "./icons";

type ArchitectureMapProps = {
  modules: Module[];
  activeId: string;
  onSelect: (id: string) => void;
};

const accent: Record<Module["accent"], { stroke: string; fill: string; glow: string }> = {
  blue: { stroke: "#4DA8FF", fill: "rgba(77,168,255,0.12)", glow: "rgba(77,168,255,0.5)" },
  cyan: { stroke: "#22D3EE", fill: "rgba(34,211,238,0.12)", glow: "rgba(34,211,238,0.5)" },
  orange: { stroke: "#FF8A4D", fill: "rgba(255,138,77,0.12)", glow: "rgba(255,138,77,0.5)" },
  purple: { stroke: "#A78BFA", fill: "rgba(167,139,250,0.14)", glow: "rgba(167,139,250,0.5)" },
  red: { stroke: "#F87171", fill: "rgba(248,113,113,0.12)", glow: "rgba(248,113,113,0.5)" },
  green: { stroke: "#34D399", fill: "rgba(52,211,153,0.12)", glow: "rgba(52,211,153,0.5)" }
};

// Larger layered layout
const LAYOUT: Record<string, { x: number; y: number; w: number; h: number }> = {
  gateway: { x: 480, y: 170, w: 320, h: 120 },
  user: { x: 60, y: 360, w: 270, h: 150 },
  product: { x: 360, y: 360, w: 270, h: 150 },
  order: { x: 660, y: 360, w: 270, h: 150 },
  recommendation: { x: 960, y: 360, w: 270, h: 150 }
};

const CLIENT = { x: 480, y: 40, w: 320, h: 80 };
const INFRA = { x: 60, y: 580, w: 1170, h: 110 };

const EDGE_STYLE: Record<string, { stroke: string; dash?: string }> = {
  sync: { stroke: "#4DA8FF" },
  async: { stroke: "#A78BFA", dash: "7 6" },
  infra: { stroke: "#7B8AAB", dash: "2 5" }
};

function iconFor(key: ModuleIconKey | undefined, color: string, size = 26) {
  switch (key) {
    case "gateway":
      return <GatewayIcon size={size} color={color} />;
    case "user":
      return <UserIcon size={size} color={color} />;
    case "product":
      return <ProductIcon size={size} color={color} />;
    case "order":
      return <OrderIcon size={size} color={color} />;
    case "recommendation":
      return <RecommendationIcon size={size} color={color} />;
    default:
      return <ProductIcon size={size} color={color} />;
  }
}

export function ArchitectureMap({ modules, activeId, onSelect }: ArchitectureMapProps) {
  const moduleMap = new Map(modules.map((module) => [module.id, module]));
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [drawerId, setDrawerId] = useState<string | null>(null);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") setDrawerOpen(false);
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  const active = drawerId ? moduleMap.get(drawerId) : undefined;
  const activeColors = active ? accent[active.accent] : accent.blue;

  function handleSelect(id: string) {
    if (drawerOpen && drawerId === id) {
      setDrawerOpen(false);
      return;
    }
    setDrawerId(id);
    setDrawerOpen(true);
    onSelect(id);
  }

  function centerOf(id: string) {
    const box = LAYOUT[id];
    if (!box) return { cx: 0, cy: 0, top: 0, bottom: 0 };
    return {
      cx: box.x + box.w / 2,
      cy: box.y + box.h / 2,
      top: box.y,
      bottom: box.y + box.h
    };
  }

  return (
    <div className="relative w-full">
      {/* Top bar inside stage */}
      <div className="mb-4 flex items-start justify-between gap-4">
        <div>
          <span className="eyebrow bg-brand/15 text-brand">Architecture · Layered</span>
          <h2 className="mt-2 text-2xl font-bold text-ink md:text-3xl">系统分层架构图</h2>
          <p className="mt-1 text-sm text-muted">Client → Edge → Services → Infrastructure · 点击节点看详情</p>
        </div>
        <Legend />
      </div>

      {/* Diagram area + overlay drawer */}
      <div className="relative w-full">
        <div
          className="rounded-2xl border border-line bg-bg2/60 p-3 backdrop-blur"
          onClick={() => setDrawerOpen(false)}
        >
          <svg
            viewBox="0 0 1290 720"
            role="img"
            aria-label="ShopSphere layered architecture"
            preserveAspectRatio="xMidYMid meet"
            className="h-auto w-full min-h-[560px]"
            onClick={(e) => e.stopPropagation()}
          >
            <defs>
              <marker id="arrow-blue" markerWidth="12" markerHeight="12" refX="9" refY="3.5" orient="auto" markerUnits="strokeWidth">
                <path d="M0,0 L0,7 L10,3.5 z" fill="#4DA8FF" />
              </marker>
              <marker id="arrow-purple" markerWidth="12" markerHeight="12" refX="9" refY="3.5" orient="auto" markerUnits="strokeWidth">
                <path d="M0,0 L0,7 L10,3.5 z" fill="#A78BFA" />
              </marker>
              <marker id="arrow-dim" markerWidth="12" markerHeight="12" refX="9" refY="3.5" orient="auto" markerUnits="strokeWidth">
                <path d="M0,0 L0,7 L10,3.5 z" fill="#7B8AAB" />
              </marker>
              <linearGradient id="layer-band" x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="rgba(255,255,255,0.05)" />
                <stop offset="100%" stopColor="rgba(255,255,255,0)" />
              </linearGradient>
            </defs>

            {/* Layer bands */}
            <LayerBand y={20} h={120} label="Client Layer" sub="Web / Mobile / API Tester" />
            <LayerBand y={150} h={150} label="Edge Layer" sub="Gateway · JWT · trace" />
            <LayerBand y={340} h={200} label="Service Layer" sub="business microservices" />
            <LayerBand y={560} h={140} label="Infrastructure Layer" sub="data · messaging · governance" />

            {/* Client node */}
            <g>
              <rect
                x={CLIENT.x}
                y={CLIENT.y}
                width={CLIENT.w}
                height={CLIENT.h}
                rx={14}
                fill="rgba(255,255,255,0.04)"
                stroke="#3A4D78"
                strokeWidth={1.5}
                strokeDasharray="5 5"
              />
              <foreignObject x={CLIENT.x + 18} y={CLIENT.y + 16} width={CLIENT.w - 36} height={CLIENT.h - 28}>
                <div className="flex h-full items-center gap-3">
                  <span className="flex h-12 w-12 items-center justify-center rounded-xl bg-bg2 ring-1 ring-line">
                    <ClientIcon size={26} color="#B4C0DA" />
                  </span>
                  <div className="leading-tight">
                    <p className="text-base font-semibold text-ink">Client</p>
                    <p className="text-xs text-muted">Web · Mobile · API Tester</p>
                  </div>
                </div>
              </foreignObject>
            </g>

            {/* Client -> Gateway */}
            <Edge
              x1={CLIENT.x + CLIENT.w / 2}
              y1={CLIENT.y + CLIENT.h}
              x2={LAYOUT.gateway.x + LAYOUT.gateway.w / 2}
              y2={LAYOUT.gateway.y}
              color="#4DA8FF"
              marker="url(#arrow-blue)"
              label="HTTPS"
              width={2.4}
            />

            {/* Gateway -> services */}
            {(["user", "product", "order", "recommendation"] as const).map((sid) => {
              const target = LAYOUT[sid];
              const gx = LAYOUT.gateway.x + LAYOUT.gateway.w / 2;
              const gy = LAYOUT.gateway.y + LAYOUT.gateway.h;
              const tx = target.x + target.w / 2;
              const ty = target.y;
              const highlighted = drawerId === sid || drawerId === "gateway";
              return (
                <Edge
                  key={`gw-${sid}`}
                  x1={gx}
                  y1={gy}
                  x2={tx}
                  y2={ty}
                  color={highlighted ? "#4DA8FF" : "#3A4D78"}
                  marker="url(#arrow-blue)"
                  width={highlighted ? 2.6 : 1.6}
                />
              );
            })}

            {/* Inter-service edges */}
            {architectureEdges
              .filter((e) => e.from !== "gateway")
              .map((edge) => {
                const a = centerOf(edge.from);
                const b = centerOf(edge.to);
                const style = EDGE_STYLE[edge.kind ?? "sync"];
                const highlighted = drawerId === edge.from || drawerId === edge.to;
                const yOffset = edge.from === "user" && edge.to === "recommendation" ? -70 : -40;
                return (
                  <CurvedLabeledEdge
                    key={`${edge.from}-${edge.to}`}
                    x1={a.cx}
                    y1={a.top + 14}
                    x2={b.cx}
                    y2={b.top + 14}
                    bend={yOffset}
                    color={highlighted ? style.stroke : "#3A4D78"}
                    dash={style.dash}
                    marker={
                      style.stroke === "#4DA8FF"
                        ? "url(#arrow-blue)"
                        : style.stroke === "#A78BFA"
                        ? "url(#arrow-purple)"
                        : "url(#arrow-dim)"
                    }
                    label={edge.label ?? ""}
                    width={highlighted ? 2.6 : 1.6}
                  />
                );
              })}

            {/* Gateway node */}
            <ModuleBox
              id="gateway"
              module={moduleMap.get("gateway")!}
              box={LAYOUT.gateway}
              selected={drawerId === "gateway"}
              onSelect={handleSelect}
            />

            {/* Service nodes */}
            {(["user", "product", "order", "recommendation"] as const).map((sid) => {
              const m = moduleMap.get(sid);
              if (!m) return null;
              return (
                <ModuleBox
                  key={sid}
                  id={sid}
                  module={m}
                  box={LAYOUT[sid]}
                  selected={drawerId === sid}
                  onSelect={handleSelect}
                />
              );
            })}

            {/* Services -> infra dotted */}
            {(["user", "product", "order", "recommendation"] as const).map((sid) => {
              const target = LAYOUT[sid];
              const cx = target.x + target.w / 2;
              const cy = target.y + target.h;
              return (
                <line
                  key={`inf-${sid}`}
                  x1={cx}
                  y1={cy}
                  x2={cx}
                  y2={INFRA.y}
                  stroke="#3A4D78"
                  strokeWidth={1.3}
                  strokeDasharray="2 6"
                />
              );
            })}

            {/* Infra band */}
            <g>
              <rect
                x={INFRA.x}
                y={INFRA.y}
                width={INFRA.w}
                height={INFRA.h}
                rx={14}
                fill="rgba(255,255,255,0.03)"
                stroke="#3A4D78"
                strokeWidth={1.5}
              />
              <foreignObject x={INFRA.x + 20} y={INFRA.y + 18} width={INFRA.w - 40} height={INFRA.h - 32}>
                <div className="flex h-full items-center justify-between gap-4">
                  <div className="flex items-center gap-3">
                    <span className="flex h-12 w-12 items-center justify-center rounded-xl bg-bg2 ring-1 ring-line">
                      <InfraIcon size={26} color="#B4C0DA" />
                    </span>
                    <div>
                      <p className="text-base font-semibold text-ink">Infrastructure</p>
                      <p className="text-xs text-muted">shared platform</p>
                    </div>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {["MySQL", "Redis", "RabbitMQ", "Nacos", "Seata", "Sentinel", "Prometheus"].map((t) => (
                      <span
                        key={t}
                        className="rounded-md bg-bg2 px-2.5 py-1 text-[12px] font-medium text-ink2 ring-1 ring-line"
                      >
                        {t}
                      </span>
                    ))}
                  </div>
                </div>
              </foreignObject>
            </g>
          </svg>
        </div>

        {/* Right floating drawer */}
        {drawerOpen && active ? (
          <aside
            className="drawer-in absolute right-3 top-3 bottom-3 z-20 w-[420px] max-w-[90vw] overflow-y-auto rounded-2xl border bg-bg2/95 p-5 shadow-soft backdrop-blur"
            style={{ borderColor: `${activeColors.stroke}55` }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="absolute inset-x-0 top-0 h-1 rounded-t-2xl" style={{ background: activeColors.stroke }} />
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-center gap-3">
                <span
                  className="flex h-12 w-12 items-center justify-center rounded-xl"
                  style={{
                    background: activeColors.fill,
                    color: activeColors.stroke,
                    boxShadow: `inset 0 0 0 1px ${activeColors.stroke}55`
                  }}
                >
                  {iconFor(active.icon, activeColors.stroke, 26)}
                </span>
                <div>
                  <p className="eyebrow text-muted">Module</p>
                  <h3 className="text-xl font-bold text-ink">{active.name}</h3>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setDrawerOpen(false)}
                aria-label="Close"
                className="flex h-8 w-8 items-center justify-center rounded-lg border border-line bg-bg text-muted transition hover:text-ink"
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
                  <path d="M6 6l12 12M18 6L6 18" />
                </svg>
              </button>
            </div>

            <p className="mt-4 text-[15px] leading-7 text-ink2">{active.role}</p>

            {active.tech && active.tech.length > 0 ? (
              <div className="mt-3 flex flex-wrap gap-1.5">
                {active.tech.map((t) => (
                  <span
                    key={t}
                    className="rounded-md bg-bg px-2 py-1 text-[12px] font-medium text-ink ring-1 ring-line"
                  >
                    {t}
                  </span>
                ))}
              </div>
            ) : null}

            <DrawerSection title="职责" color={activeColors.stroke} items={active.responsibilities} />
            <DrawerSection title="调用关系" color={activeColors.stroke} items={active.calls} />
            <DrawerSection title="核心文件" color={activeColors.stroke} items={active.coreFiles} mono />

            <div
              className="mt-4 rounded-xl border p-4"
              style={{ borderColor: `${activeColors.stroke}55`, background: activeColors.fill }}
            >
              <p className="eyebrow" style={{ color: activeColors.stroke }}>
                面试怎么讲
              </p>
              <p className="mt-2 text-[14px] leading-7 text-ink">{active.interview}</p>
            </div>
          </aside>
        ) : null}

        {/* Hint when drawer closed */}
        {!drawerOpen ? (
          <div className="pointer-events-none absolute right-4 top-4 hidden rounded-lg border border-line bg-bg2/80 px-3 py-1.5 text-[12px] text-muted backdrop-blur md:block">
            点击任意模块 → 详情抽屉
          </div>
        ) : null}
      </div>

      {/* ignore unused param warning */}
      <span className="hidden">{activeId}</span>
    </div>
  );
}

function LayerBand({ y, h, label, sub }: { y: number; h: number; label: string; sub: string }) {
  return (
    <g>
      <rect x={20} y={y} width={1250} height={h} rx={16} fill="url(#layer-band)" stroke="#243352" strokeWidth={1} />
      <text x={40} y={y + 24} fill="#7B8AAB" style={{ fontSize: 12, letterSpacing: 2 }}>
        {label.toUpperCase()}
      </text>
      <text x={40} y={y + 42} fill="#7B8AAB" style={{ fontSize: 11 }}>
        {sub}
      </text>
    </g>
  );
}

function ModuleBox({
  id,
  module,
  box,
  selected,
  onSelect
}: {
  id: string;
  module: Module;
  box: { x: number; y: number; w: number; h: number };
  selected: boolean;
  onSelect: (id: string) => void;
}) {
  const colors = accent[module.accent];
  return (
    <g
      className="bbg-node cursor-pointer"
      onClick={(e) => {
        e.stopPropagation();
        onSelect(id);
      }}
    >
      <rect
        x={box.x}
        y={box.y}
        width={box.w}
        height={box.h}
        rx={14}
        fill={selected ? colors.fill : "#131E36"}
        stroke={selected ? colors.stroke : "#2E3F62"}
        strokeWidth={selected ? 3 : 1.6}
      />
      <rect x={box.x} y={box.y} width={6} height={box.h} rx={5} fill={colors.stroke} />
      <foreignObject x={box.x + 18} y={box.y + 14} width={box.w - 28} height={box.h - 22}>
        <div className="flex h-full flex-col gap-1.5">
          <div className="flex items-center gap-2.5">
            <span
              className="flex h-9 w-9 items-center justify-center rounded-lg"
              style={{
                background: colors.fill,
                color: colors.stroke,
                boxShadow: `inset 0 0 0 1px ${colors.stroke}55`
              }}
            >
              {iconFor(module.icon, colors.stroke, 22)}
            </span>
            <p className="text-[16px] font-bold leading-tight text-ink">{module.name}</p>
          </div>
          <p className="line-clamp-2 text-[12px] leading-snug text-muted">{module.role}</p>
          {module.tech && module.tech.length > 0 ? (
            <div className="mt-auto flex flex-wrap gap-1">
              {module.tech.slice(0, 3).map((t) => (
                <span
                  key={t}
                  className="rounded bg-bg2 px-1.5 py-0.5 text-[11px] font-medium leading-4 text-ink2 ring-1 ring-line"
                >
                  {t}
                </span>
              ))}
            </div>
          ) : null}
        </div>
      </foreignObject>
    </g>
  );
}

function Edge({
  x1,
  y1,
  x2,
  y2,
  color,
  marker,
  width = 1.8,
  dash,
  label
}: {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  color: string;
  marker?: string;
  width?: number;
  dash?: string;
  label?: string;
}) {
  return (
    <g>
      <line x1={x1} y1={y1} x2={x2} y2={y2} stroke={color} strokeWidth={width} strokeDasharray={dash} markerEnd={marker} />
      {label ? (
        <g>
          <rect
            x={(x1 + x2) / 2 - 26}
            y={(y1 + y2) / 2 - 11}
            width={52}
            height={22}
            rx={11}
            fill="#0E1729"
            stroke={color}
            strokeOpacity={0.6}
          />
          <text
            x={(x1 + x2) / 2}
            y={(y1 + y2) / 2 + 4}
            textAnchor="middle"
            style={{ fontSize: 12, fontWeight: 600, letterSpacing: 0.4 }}
            fill={color}
          >
            {label}
          </text>
        </g>
      ) : null}
    </g>
  );
}

function CurvedLabeledEdge({
  x1,
  y1,
  x2,
  y2,
  bend,
  color,
  marker,
  width = 1.8,
  dash,
  label
}: {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  bend: number;
  color: string;
  marker?: string;
  width?: number;
  dash?: string;
  label?: string;
}) {
  const mx = (x1 + x2) / 2;
  const my = Math.min(y1, y2) + bend;
  const d = `M ${x1} ${y1} Q ${mx} ${my} ${x2} ${y2}`;
  return (
    <g>
      <path d={d} fill="none" stroke={color} strokeWidth={width} strokeDasharray={dash} markerEnd={marker} />
      {label ? (
        <g>
          <rect
            x={mx - (label.length * 4.2 + 14)}
            y={my - 11}
            width={label.length * 8.4 + 28}
            height={22}
            rx={11}
            fill="#0E1729"
            stroke={color}
            strokeOpacity={0.6}
          />
          <text x={mx} y={my + 4} textAnchor="middle" style={{ fontSize: 12, fontWeight: 600 }} fill={color}>
            {label}
          </text>
        </g>
      ) : null}
    </g>
  );
}

function Legend() {
  const items: { color: string; dash?: string; label: string }[] = [
    { color: "#4DA8FF", label: "sync · HTTP / Feign" },
    { color: "#A78BFA", dash: "5 4", label: "async · MQ" },
    { color: "#7B8AAB", dash: "2 5", label: "infra" }
  ];
  return (
    <div className="hidden flex-col gap-2 rounded-xl border border-line bg-bg2/80 px-3.5 py-2.5 text-[12px] backdrop-blur md:flex">
      {items.map((it) => (
        <div key={it.label} className="flex items-center gap-2.5 text-ink2">
          <svg width="26" height="6" aria-hidden>
            <line
              x1="0"
              y1="3"
              x2="26"
              y2="3"
              stroke={it.color}
              strokeWidth="2.4"
              strokeDasharray={it.dash}
              strokeLinecap="round"
            />
          </svg>
          <span style={{ color: it.color }} className="font-semibold">
            {it.label}
          </span>
        </div>
      ))}
    </div>
  );
}

function DrawerSection({
  title,
  items,
  mono = false,
  color
}: {
  title: string;
  items: string[];
  mono?: boolean;
  color: string;
}) {
  return (
    <div className="mt-4">
      <div className="flex items-center gap-2">
        <span className="h-1.5 w-7 rounded-full" style={{ background: color }} />
        <h4 className="text-[12px] font-semibold uppercase tracking-[0.16em] text-muted">{title}</h4>
      </div>
      <ul className="mt-2 space-y-1.5">
        {items.map((item) => (
          <li
            key={item}
            className={`rounded-lg border border-line bg-bg px-3 py-2 leading-6 ${
              mono ? "break-all font-mono text-[12px] text-ink2" : "text-[14px] text-ink"
            }`}
          >
            {item}
          </li>
        ))}
      </ul>
    </div>
  );
}
