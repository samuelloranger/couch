import { defineConfig } from "vitepress";

const base = process.env.DOCS_BASE || "/";
const hostname = process.env.DOCS_HOSTNAME;

export default defineConfig({
  lang: "en-US",
  base,
  title: "Couch",
  description: "A light, fast Android TV launcher built on plain Android Views.",
  head: [
    ["link", { rel: "icon", type: "image/svg+xml", href: `${base}logo.svg` }],
    ["meta", { name: "theme-color", content: "#0a0d0c" }],
  ],
  ...(hostname ? { sitemap: { hostname } } : {}),
  cleanUrls: true,
  markdown: { theme: "vitesse-dark" },
  appearance: false,
  themeConfig: {
    logo: { src: "/logo.svg", alt: "Couch" },
    nav: [
      { text: "Setup", link: "/setup" },
      { text: "Configuration", link: "/configuration" },
      { text: "Releases", link: "https://github.com/samuelloranger/couch/releases" },
    ],
    sidebar: [
      {
        text: "Guide",
        items: [
          { text: "Setup", link: "/setup" },
          { text: "Configuration", link: "/configuration" },
          { text: "Other TVs", link: "/sync" },
          { text: "Godmode", link: "/godmode" },
        ],
      },
      {
        text: "Under the hood",
        items: [
          { text: "Architecture", link: "/architecture" },
          { text: "Decisions", link: "/decisions" },
          { text: "Development", link: "/development" },
        ],
      },
    ],
    socialLinks: [{ icon: "github", link: "https://github.com/samuelloranger/couch" }],
    outline: [2, 3],
    search: { provider: "local" },
    footer: {
      message: "Brand marks from Simple Icons (CC0) and Wikimedia Commons (public domain). Trademarks belong to their owners.",
    },
  },
});
