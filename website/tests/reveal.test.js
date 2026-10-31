import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { vReveal } from "../src/directives/reveal.js";

function createElement() {
  const classes = new Set();
  return { classList: { add: (name) => classes.add(name), contains: (name) => classes.has(name) } };
}

test("prerendered content is visible before JavaScript starts", () => {
  const css = readFileSync(new URL("../src/styles.css", import.meta.url), "utf8");
  const rule = css.match(/\.reveal\s*\{([^}]+)\}/)[1];
  assert.match(rule, /opacity:\s*1\s*;/);
  assert.doesNotMatch(rule, /translateY/);
});

test("content stays visible when IntersectionObserver is unavailable", () => {
  const previous = globalThis.IntersectionObserver;
  delete globalThis.IntersectionObserver;
  try {
    const el = createElement();
    vReveal.mounted(el);
    assert.equal(el.classList.contains("is-visible"), true);
    assert.equal(el.classList.contains("is-pending"), false);
    vReveal.unmounted(el);
  } finally {
    if (previous !== undefined) globalThis.IntersectionObserver = previous;
  }
});

test("client-side observation enables the animation and cleans up", () => {
  const previous = globalThis.IntersectionObserver;
  let callback;
  let observed;
  let unobserved;
  let disconnected = false;
  globalThis.IntersectionObserver = class {
    constructor(handler) { callback = handler; }
    observe(el) { observed = el; }
    unobserve(el) { unobserved = el; }
    disconnect() { disconnected = true; }
  };
  try {
    const el = createElement();
    vReveal.mounted(el);
    assert.equal(el.classList.contains("is-pending"), true);
    assert.equal(observed, el);
    callback([{ isIntersecting: false }]);
    assert.equal(el.classList.contains("is-visible"), false);
    callback([{ isIntersecting: true }]);
    assert.equal(el.classList.contains("is-visible"), true);
    assert.equal(unobserved, el);
    vReveal.unmounted(el);
    assert.equal(disconnected, true);
  } finally {
    if (previous === undefined) delete globalThis.IntersectionObserver;
    else globalThis.IntersectionObserver = previous;
  }
});
