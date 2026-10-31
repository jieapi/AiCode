import { DEFAULT_LOCALE, LOCALE_PATHS, translations } from "../src/composables/useLocale.js";

export function resolveSiteOrigin(env = process.env) {
  const explicitSiteUrl = env.SITE_URL !== undefined;
  const source = explicitSiteUrl ? "SITE_URL" : "VERCEL_PROJECT_PRODUCTION_URL";
  const value = env[source];

  if (!explicitSiteUrl && (value === undefined || value === "")) return null;

  const message = explicitSiteUrl
    ? "SITE_URL 必须是完整的 http/https 站点源，不得包含路径、查询参数、片段或凭据"
    : "VERCEL_PROJECT_PRODUCTION_URL 必须是不含协议、路径、查询参数、片段或凭据的生产域名";
  const invalid = () => new Error(message);

  if (typeof value !== "string" || /[\s\u0000-\u001f\u007f\\"<>]/.test(value)) throw invalid();
  if (explicitSiteUrl ? !/^https?:\/\/[^/?#]+\/?$/i.test(value) : !/^[^/:?#]+$/.test(value)) {
    throw invalid();
  }

  let url;
  try {
    url = new URL(explicitSiteUrl ? value : `https://${value}`);
  } catch {
    throw invalid();
  }

  if (
    !["http:", "https:"].includes(url.protocol) ||
    !url.hostname ||
    url.username ||
    url.password ||
    value.includes("@") ||
    url.pathname !== "/" ||
    url.search ||
    url.hash
  ) {
    throw invalid();
  }

  return url.origin;
}

function escapeHtml(value) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

export function createPageHead(locale, siteOrigin, meta = translations[locale].site.meta) {
  const tags = [
    `<title>${escapeHtml(meta.title)}</title>`,
    `<meta name="description" content="${escapeHtml(meta.description)}" />`
  ];

  if (siteOrigin) {
    tags.push(`<link rel="canonical" href="${escapeHtml(siteOrigin + LOCALE_PATHS[locale])}" />`);
    for (const [language, pathname] of Object.entries(LOCALE_PATHS)) {
      tags.push(`<link rel="alternate" hreflang="${language}" href="${escapeHtml(siteOrigin + pathname)}" />`);
    }
    tags.push(`<link rel="alternate" hreflang="x-default" href="${escapeHtml(siteOrigin + LOCALE_PATHS[DEFAULT_LOCALE])}" />`);
  }

  return tags.join("\n");
}
