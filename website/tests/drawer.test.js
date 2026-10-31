import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const css = readFileSync(new URL("../src/styles.css", import.meta.url), "utf8");

test("the closed mobile drawer has no shadow", () => {
  const rule = css.match(/\.aicode-device \.ac-drawer\s*\{([^}]+)\}/)[1];
  assert.match(rule, /box-shadow:\s*none\s*;/);
  assert.match(rule, /transform:\s*translateX\(-/);
});

test("the open mobile drawer displays its shadow", () => {
  const rule = css.match(/\.aicode-device\.drawer-open \.ac-drawer\s*\{([^}]+)\}/)[1];
  assert.match(rule, /box-shadow:\s*12px 0 32px rgba\(/);
  assert.match(rule, /transform:\s*translateX\(0\)/);
});
