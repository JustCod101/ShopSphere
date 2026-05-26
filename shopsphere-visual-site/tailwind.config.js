/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  darkMode: "class",
  theme: {
    extend: {
      colors: {
        // Dark base
        bg: "#0B1220",
        bg2: "#0E1729",
        surface: "#131E36",
        surface2: "#1A2745",
        line: "#243352",
        line2: "#2E3F62",

        // Text — bumped contrast on dark surface
        ink: "#F1F5FB",
        ink2: "#D6DFF1",
        muted: "#B4C0DA",
        dim: "#7B8AAB",

        // Accents (ByteByteGo-ish saturated tones)
        brand: "#4DA8FF",
        blueflow: "#4DA8FF",
        cyanflow: "#22D3EE",
        stock: "#FF8A4D",
        reco: "#A78BFA",
        risk: "#F87171",
        gold: "#FBBF24",
        mint: "#34D399",
        pink: "#F472B6",

        // Legacy aliases used in old code paths
        panel: "#131E36"
      },
      fontFamily: {
        sans: [
          "Inter",
          "ui-sans-serif",
          "system-ui",
          "-apple-system",
          "Segoe UI",
          "Roboto",
          "Helvetica Neue",
          "sans-serif"
        ],
        mono: ["JetBrains Mono", "ui-monospace", "SFMono-Regular", "Menlo", "monospace"]
      },
      boxShadow: {
        soft: "0 18px 50px rgba(0, 0, 0, 0.45)",
        glow: "0 0 0 1px rgba(77, 168, 255, 0.35), 0 8px 30px rgba(77, 168, 255, 0.15)",
        card: "0 1px 0 rgba(255,255,255,0.04) inset, 0 12px 32px rgba(0,0,0,0.45)",
        ring: "0 0 0 1px rgba(255,255,255,0.06) inset"
      },
      backgroundImage: {
        grid: "radial-gradient(circle at 1px 1px, rgba(255,255,255,0.06) 1px, transparent 0)",
        "hero-glow":
          "radial-gradient(60% 60% at 20% 0%, rgba(77,168,255,0.18) 0%, rgba(11,18,32,0) 70%), radial-gradient(50% 50% at 90% 10%, rgba(167,139,250,0.16) 0%, rgba(11,18,32,0) 70%)"
      }
    }
  },
  plugins: []
};
