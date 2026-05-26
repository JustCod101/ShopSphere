import { useEffect, useMemo, useState } from "react";

type MermaidDiagramProps = {
  title: string;
  description: string;
  code: string;
  variant?: "card" | "hero";
};

function hash(input: string) {
  let value = 0;
  for (let index = 0; index < input.length; index += 1) {
    value = (value * 31 + input.charCodeAt(index)) >>> 0;
  }
  return value.toString(16);
}

export function MermaidDiagram({ title, description, code, variant = "card" }: MermaidDiagramProps) {
  const [svg, setSvg] = useState("");
  const [error, setError] = useState("");
  const diagramId = useMemo(() => `diagram-${hash(`${title}-${code}`)}`, [code, title]);

  useEffect(() => {
    let cancelled = false;

    async function renderDiagram() {
      try {
        const mermaidModule = await import("mermaid");
        const mermaid = mermaidModule.default;
        mermaid.initialize({
          startOnLoad: false,
          securityLevel: "strict",
          theme: "base",
          themeVariables: {
            background: "#0E1729",
            primaryColor: "#1A2745",
            primaryTextColor: "#F1F5FB",
            primaryBorderColor: "#4DA8FF",
            secondaryColor: "#131E36",
            secondaryBorderColor: "#A78BFA",
            tertiaryColor: "#162238",
            tertiaryBorderColor: "#22D3EE",
            lineColor: "#B4C0DA",
            textColor: "#F1F5FB",
            mainBkg: "#1A2745",
            nodeBorder: "#4DA8FF",
            nodeTextColor: "#F1F5FB",
            clusterBkg: "#111B30",
            clusterBorder: "#243352",
            edgeLabelBackground: "#0E1729",
            labelTextColor: "#F1F5FB",
            actorBkg: "#1A2745",
            actorBorder: "#4DA8FF",
            actorTextColor: "#F1F5FB",
            actorLineColor: "#4DA8FF",
            signalColor: "#F1F5FB",
            signalTextColor: "#F1F5FB",
            sequenceNumberColor: "#0B1220",
            sectionBkgColor: "#1A2745",
            altSectionBkgColor: "#131E36",
            noteBkgColor: "#FF8A4D",
            noteTextColor: "#0B1220",
            fontFamily: "Inter, ui-sans-serif, system-ui",
            fontSize: "15px"
          }
        });
        const result = await mermaid.render(diagramId, code);
        if (!cancelled) {
          setSvg(result.svg);
          setError("");
        }
      } catch (renderError) {
        if (!cancelled) {
          setSvg("");
          setError(renderError instanceof Error ? renderError.message : "Mermaid diagram render failed.");
        }
      }
    }

    renderDiagram();

    return () => {
      cancelled = true;
    };
  }, [code, diagramId]);

  if (variant === "hero") {
    return (
      <figure className="w-full">
        <figcaption className="mb-5">
          <span className="eyebrow bg-cyanflow/15 text-cyanflow">Diagram · Hero</span>
          <h2 className="mt-2 text-2xl font-bold text-ink md:text-3xl">{title}</h2>
          <p className="mt-1 max-w-3xl text-base text-muted">{description}</p>
        </figcaption>
        {error ? (
          <pre className="overflow-auto rounded-md border border-risk/40 bg-risk/10 p-3 text-sm text-risk">{error}</pre>
        ) : (
          <div
            className="mermaid flex w-full items-center justify-center overflow-x-auto rounded-2xl border border-line bg-bg2/60 p-6 backdrop-blur"
            style={{ minHeight: "55vh" }}
            dangerouslySetInnerHTML={{ __html: svg }}
          />
        )}
      </figure>
    );
  }

  return (
    <figure className="card p-5">
      <figcaption className="mb-3 flex items-start justify-between gap-3">
        <div>
          <span className="eyebrow bg-cyanflow/15 text-cyanflow">Diagram</span>
          <h3 className="mt-2 text-base font-semibold text-ink">{title}</h3>
          <p className="mt-1 text-sm text-muted">{description}</p>
        </div>
      </figcaption>
      {error ? (
        <pre className="overflow-auto rounded-md border border-risk/40 bg-risk/10 p-3 text-xs text-risk">{error}</pre>
      ) : (
        <div
          className="mermaid overflow-x-auto rounded-xl border border-line bg-bg2 p-3"
          dangerouslySetInnerHTML={{ __html: svg }}
        />
      )}
    </figure>
  );
}
