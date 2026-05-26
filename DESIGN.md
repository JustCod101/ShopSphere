# Design

## Source of truth
- Status: Active
- Last refreshed: 2026-05-25
- Primary product surfaces: Candidate-facing project explanation website in `shopsphere-visual-site/`.
- Evidence reviewed: `docs/site-plan.md`, `repo-analysis.md`, `README.md`, `docs/architecture.md`.

## Brand
- Personality: Clear, technical, evidence-led, interview-oriented.
- Trust signals: Source path citations, explicit risk badges, diagrams tied to implementation files.
- Avoid: Marketing landing-page language, decorative-only visuals, claims without repository evidence.

## Product goals
- Goals: Help a candidate explain ShopSphere through architecture, modules, request flows, data models, reliability patterns, and interview answers.
- Non-goals: Real ecommerce frontend, live backend operation, production cloud console.
- Success signals: Each page answers what it does, how it runs, why it is designed this way, and how to explain it in interviews.

## Personas and jobs
- Primary personas: Job candidates preparing backend or full-stack interviews.
- User jobs: Build a concise project story, trace claims to source files, rehearse follow-up answers.
- Key contexts of use: Interview preparation, portfolio review, project walkthrough.

## Information architecture
- Primary navigation: Overview, Architecture, Core Modules, Request Flow, Data Model, Scaling & Reliability, Interview Mode.
- Core routes/screens: Single-page Vite React app with section navigation.
- Content hierarchy: Page thesis, diagrams, four-question explanation cards, evidence paths, memory points.

## Design principles
- Principle 1: Diagrams first; prose supports the diagram instead of replacing it.
- Principle 2: Every architectural claim must show evidence paths.
- Tradeoffs: Static local content is preferred over backend integration to keep the site portable.

## Visual language
- Color: White and light gray base; blue/cyan for synchronous flow, orange for transactions and stock risk, purple for recommendation, red for risks.
- Typography: System sans-serif with dense but readable technical content.
- Spacing/layout rhythm: Dashboard-like sections, compact cards, consistent 8px radius.
- Shape/radius/elevation: Thin borders, soft shadows, 8px or smaller card radius.
- Motion: Minimal hover and active-step transitions only.
- Imagery/iconography: SVG and Mermaid diagrams; no stock imagery.

## Components
- Existing components to reuse: None found in the repository.
- New/changed components: Navigation, section shell, evidence list, module explorer, request flow, Mermaid renderer, interview cards.
- Variants and states: Active navigation, expanded module, active request step, risk badges.
- Token/component ownership: Local Tailwind configuration in `shopsphere-visual-site/`.

## Accessibility
- Target standard: Keyboard-readable, semantic buttons, high-contrast text.
- Keyboard/focus behavior: Interactive modules and flow steps are buttons with visible focus styles.
- Contrast/readability: Dark text on light backgrounds, accent colors not used as the only carrier of meaning.
- Screen-reader semantics: Sections have headings; diagrams are accompanied by text summaries.
- Reduced motion and sensory considerations: No autoplay or heavy animation.

## Responsive behavior
- Supported breakpoints/devices: Desktop and mobile browser widths.
- Layout adaptations: Sidebar/navigation collapses into wrapping top navigation; diagrams stack above evidence cards.
- Touch/hover differences: All hover affordances are also click/tap controls.

## Interaction states
- Loading: Mermaid diagrams render after mount and show no backend dependency.
- Empty: Not applicable; content is static.
- Error: Mermaid render failures show a readable fallback message.
- Success: Active selections update explanation and evidence panels.
- Disabled: Not applicable.
- Offline/slow network, if applicable: App runs after dependency installation and build; no runtime network calls.

## Content voice
- Tone: Candidate-facing, concise, technically precise.
- Terminology: Use repository terms such as Gateway, TCC, outbox, Feign, Nacos, Seata, RabbitMQ.
- Microcopy rules: Prefer “待确认” and “已知边界” for uncertain or incomplete claims.

## Implementation constraints
- Framework/styling system: Vite React, TypeScript, Tailwind CSS.
- Design-token constraints: Local color tokens only; no global repo styling exists.
- Performance constraints: Static content, no live backend calls, Mermaid rendered client-side.
- Compatibility constraints: Standard modern browser and Node/npm toolchain.
- Test/screenshot expectations: `npm run build` must pass.

## Open questions
- [ ] Whether to later deploy the site under `docs/` or keep it as a standalone portfolio subproject.
