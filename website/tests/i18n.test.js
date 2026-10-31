import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { createLocale, DEFAULT_LOCALE, getLocaleFromPath, LOCALE_PATHS, translations } from "../src/composables/useLocale.js";

function dictionaryShape(value) {
  if (Array.isArray(value)) return value.map(dictionaryShape);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, dictionaryShape(value[key])]));
  }
  assert.equal(typeof value, "string");
  assert.ok(value.trim().length > 0);
  return "string";
}

test("Chinese is the default locale for every new app instance", () => {
  const first = createLocale();
  first.setLocale("en");
  const second = createLocale();
  assert.equal(second.locale.value, DEFAULT_LOCALE);
  assert.equal(second.copy.value, translations["zh-CN"]);
  assert.equal(first.locale.value, "en");
});

test("the English page initializes with English before hydration", () => {
  const state = createLocale(getLocaleFromPath("/en/"));
  assert.equal(state.locale.value, "en");
  assert.equal(state.copy.value, translations.en);
  assert.equal(state.alternateLocale.value, "zh-CN");
  assert.equal(state.alternateHref.value, "/");
  assert.equal(createLocale("unsupported").locale.value, DEFAULT_LOCALE);
});

test("language changes follow the address and update content reactively", () => {
  const state = createLocale();
  const chinese = state.copy.value;
  assert.equal(state.alternateHref.value, "/en/");
  state.syncLocation({ pathname: "/en/" });
  assert.equal(state.locale.value, "en");
  assert.equal(state.copy.value, translations.en);
  assert.notEqual(state.copy.value.site.meta.title, chinese.site.meta.title);
  assert.equal(state.alternateHref.value, "/");
  state.syncLocation({ pathname: "/" });
  assert.equal(state.locale.value, "zh-CN");
  assert.equal(state.copy.value, chinese);
  assert.equal(state.alternateHref.value, "/en/");
});

test("language switching preserves the current query and section anchor", () => {
  const state = createLocale();
  state.syncLocation({ pathname: "/", search: "?source=docs", hash: "#download" });
  assert.equal(state.alternateHref.value, "/en/?source=docs#download");
  state.syncLocation({ pathname: "/en/", search: "?source=docs", hash: "#download" });
  assert.equal(state.alternateHref.value, "/?source=docs#download");
  state.syncLocation({ pathname: "/en/", hash: "#features" });
  assert.equal(state.alternateHref.value, "/#features");
});

test("only exact English page paths select the English locale", () => {
  for (const pathname of ["/en", "/en/", "/en/index.html"]) {
    assert.equal(getLocaleFromPath(pathname), "en");
  }
  for (const pathname of ["/", "/index.html", "/english", "/en-us/", "/en/unknown", "/EN/"]) {
    assert.equal(getLocaleFromPath(pathname), DEFAULT_LOCALE);
  }
  assert.deepEqual(LOCALE_PATHS, { "zh-CN": "/", en: "/en/" });
});

test("only supported locales are accepted", () => {
  const state = createLocale();
  for (const value of [null, undefined, "", "fr", "en-US", "__proto__"]) {
    state.setLocale(value);
    assert.equal(state.locale.value, DEFAULT_LOCALE);
  }
  state.setLocale("en");
  state.setLocale("invalid");
  assert.equal(state.locale.value, "en");
  state.setLocale("zh-CN");
  assert.equal(state.locale.value, DEFAULT_LOCALE);
});

test("Chinese and English dictionaries have the same nonempty message structure", () => {
  assert.deepEqual(dictionaryShape(translations.en), dictionaryShape(translations["zh-CN"]));
});

test("English messages only use Chinese for the native language-switch label", () => {
  function check(value, path = "") {
    if (typeof value === "string") {
      if (path === "site.nav.language.desktop" || path === "site.nav.language.mobile") {
        assert.equal(value, "中文");
      } else {
        assert.doesNotMatch(value, /[\u4e00-\u9fff]/, path);
      }
      return;
    }
    for (const [key, item] of Object.entries(value)) check(item, path ? `${path}.${key}` : key);
  }
  check(translations.en);
});

test("the prerender template matches the default Chinese locale and metadata", () => {
  const template = readFileSync(new URL("../index.html", import.meta.url), "utf8");
  const meta = translations["zh-CN"].site.meta;
  assert.ok(template.includes('<html lang="zh-CN"'));
  assert.ok(template.includes("<title>" + meta.title + "</title>"));
  assert.ok(template.includes('content="' + meta.description + '"'));
});

test("both languages provide translated page metadata", () => {
  for (const messages of Object.values(translations)) {
    assert.match(messages.site.meta.title, /AiCode/);
    assert.ok(messages.site.meta.description.length > 30);
  }
  assert.match(translations["zh-CN"].site.meta.title, /[\u4e00-\u9fff]/);
  assert.doesNotMatch(translations.en.site.meta.title, /[\u4e00-\u9fff]/);
  assert.doesNotMatch(translations.en.site.meta.description, /[\u4e00-\u9fff]/);
});
