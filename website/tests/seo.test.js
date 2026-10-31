import assert from "node:assert/strict";
import test from "node:test";
import { translations } from "../src/composables/useLocale.js";
import { createPageHead, resolveSiteOrigin } from "../scripts/seo.mjs";

test("SITE_URL takes priority over Vercel production and preview URLs", () => {
  assert.equal(resolveSiteOrigin({
    SITE_URL: "http://localhost:5173/",
    VERCEL_PROJECT_PRODUCTION_URL: "example.com",
    VERCEL_URL: "preview.example.com"
  }), "http://localhost:5173");
  assert.equal(resolveSiteOrigin({
    SITE_URL: "https://example.com",
    VERCEL_PROJECT_PRODUCTION_URL: "not a domain"
  }), "https://example.com");
});

test("site origins accept HTTP or HTTPS and normalize a root slash", () => {
  for (const value of ["https://example.com", "https://example.com/"]) {
    assert.equal(resolveSiteOrigin({ SITE_URL: value }), "https://example.com");
  }
  assert.equal(resolveSiteOrigin({ SITE_URL: "HTTP://LOCALHOST:5173" }), "http://localhost:5173");
});

test("invalid explicit SITE_URL values fail instead of falling back", () => {
  const values = [
    "", " ", "example.com", "//example.com", "ftp://example.com", "https://",
    "https:///example.com", "https://example.com/en/", "https://example.com/./",
    "https://example.com?lang=en", "https://example.com?", "https://example.com#top",
    "https://example.com#", "https://user:password@example.com", "https://@example.com",
    "https://example.com\\en", "https://exam\nple.com", " https://example.com",
    "https://example.com ", "https://example.com\"", "https://example.com:invalid",
    "javascript:alert(1)", null
  ];
  for (const SITE_URL of values) {
    assert.throws(() => resolveSiteOrigin({
      SITE_URL,
      VERCEL_PROJECT_PRODUCTION_URL: "example.com"
    }), /SITE_URL/, String(SITE_URL));
  }
});

test("the stable Vercel production domain gets HTTPS and rejects malformed domains", () => {
  assert.equal(resolveSiteOrigin({ VERCEL_PROJECT_PRODUCTION_URL: "example.com" }), "https://example.com");
  for (const value of [
    "https://example.com", "http://example.com", "//example.com", "example.com/",
    "example.com/en/", "example.com?lang=en", "example.com#top", "example.com:",
    "user@example.com", "@example.com", "example.com:5173", "example.com\\en",
    "example.com\"", "example.com ", "exam\nple.com", null
  ]) {
    assert.throws(() => resolveSiteOrigin({ VERCEL_PROJECT_PRODUCTION_URL: value }), /VERCEL_PROJECT_PRODUCTION_URL/, String(value));
  }
});

test("missing production configuration never uses a preview URL", () => {
  assert.equal(resolveSiteOrigin({}), null);
  assert.equal(resolveSiteOrigin({ VERCEL_PROJECT_PRODUCTION_URL: "" }), null);
  assert.equal(resolveSiteOrigin({ VERCEL_URL: "preview.example.com" }), null);
});

test("each locale head has translated metadata and its own canonical", () => {
  const origin = resolveSiteOrigin({ SITE_URL: "https://example.com" });
  const chinese = createPageHead("zh-CN", origin);
  const english = createPageHead("en", origin);

  for (const [locale, head] of [["zh-CN", chinese], ["en", english]]) {
    const meta = translations[locale].site.meta;
    assert.ok(head.includes(`<title>${meta.title}</title>`));
    assert.ok(head.includes(`<meta name="description" content="${meta.description}" />`));
    assert.equal([...head.matchAll(/rel="canonical"/g)].length, 1);
  }
  assert.ok(chinese.includes('<link rel="canonical" href="https://example.com/" />'));
  assert.ok(english.includes('<link rel="canonical" href="https://example.com/en/" />'));
  assert.notEqual(chinese, english);
});

test("both locale heads contain reciprocal, self-referencing hreflang and Chinese x-default", () => {
  const origin = resolveSiteOrigin({ SITE_URL: "https://example.com" });
  const expected = [
    '<link rel="alternate" hreflang="zh-CN" href="https://example.com/" />',
    '<link rel="alternate" hreflang="en" href="https://example.com/en/" />',
    '<link rel="alternate" hreflang="x-default" href="https://example.com/" />'
  ];
  for (const locale of ["zh-CN", "en"]) {
    const head = createPageHead(locale, origin);
    assert.deepEqual(head.match(/<link rel="alternate"[^>]+>/g), expected);
    for (const [, href] of head.matchAll(/href="([^"]+)"/g)) {
      const url = new URL(href);
      assert.equal(url.origin, origin);
      assert.ok(url.pathname === "/" || url.pathname === "/en/");
    }
  }
});

test("a local build without an origin still gets translated metadata but no SEO URLs", () => {
  const origin = resolveSiteOrigin({ VERCEL_URL: "preview.example.com" });
  for (const locale of ["zh-CN", "en"]) {
    const head = createPageHead(locale, origin);
    assert.ok(head.includes(`<title>${translations[locale].site.meta.title}</title>`));
    assert.match(head, /<meta name="description"/);
    assert.doesNotMatch(head, /rel="canonical"|hreflang=|https?:\/\//);
  }
});

test("metadata and environment-derived href attributes are HTML-escaped", () => {
  const text = `AiCode & <test> "quoted" 'text'`;
  const escaped = "AiCode &amp; &lt;test&gt; &quot;quoted&quot; &#39;text&#39;";
  const head = createPageHead("en", null, { title: text, description: text });
  assert.equal(head, `<title>${escaped}</title>\n<meta name="description" content="${escaped}" />`);

  const origin = resolveSiteOrigin({ SITE_URL: "https://site&'test.example.com" });
  const links = createPageHead("en", origin);
  assert.ok(links.includes('href="https://site&amp;&#39;test.example.com/en/"'));
  assert.equal([...links.matchAll(/href="https:\/\/site&amp;&#39;test\.example\.com\//g)].length, 4);
  assert.doesNotMatch(links, /href="https:\/\/site&'test/);
});
