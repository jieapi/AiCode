'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const sample = path.join(__dirname, '..', 'plugin-samples', 'desktop-pet');
const html = fs.readFileSync(path.join(sample, 'pages/pet.html'), 'utf8');
const pageSource = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)]
  .map(match => match[1]).find(source => source.trim());
const runtimeSource = fs.readFileSync(path.join(sample, 'dist/main.js'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
const plain = value => JSON.parse(JSON.stringify(value));

function loadPage({ width = 110, height = 130, reducedMotion = false } = {}) {
  const elements = new Map();
  const env = {
    info: { width, height, x: 400 - width, y: 800 - height, density: 3 },
    styles: new Map(), resizes: [], messages: [], dragRegions: [], timers: new Map(), pending: null
  };
  const element = id => {
    if (!elements.has(id)) {
      const classes = new Set();
      elements.set(id, {
        dataset: {}, disabled: false, textContent: '', attributes: {}, listeners: {},
        classList: {
          add: (...names) => names.forEach(name => classes.add(name)),
          remove: (...names) => names.forEach(name => classes.delete(name)),
          toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); },
          contains: name => classes.has(name)
        },
        setAttribute(name, value) { this.attributes[name] = value; },
        addEventListener(name, handler) { this.listeners[name] = handler; }
      });
    }
    return elements.get(id);
  };
  const actions = ['feed', 'sleep', 'hide'].map(action => {
    const button = element(action);
    button.dataset.action = action;
    return button;
  });
  element('menu').querySelectorAll = selector => {
    assert.equal(selector, 'button');
    return [element('menuClose'), ...actions];
  };
  env.finish = (change = env.pending) => {
    assert.ok(change, '应有待完成的尺寸请求');
    env.pending = null;
    env.info.x = Math.max(0, Math.min(400 - change.width, change.options.x == null ? env.info.x : change.options.x));
    env.info.y = Math.max(0, Math.min(800 - change.height, change.options.y == null ? env.info.y : change.options.y));
    env.info.width = change.width;
    env.info.height = change.height;
    env.receive({ type: 'resize', ...plain(env.info) });
  };
  vm.runInNewContext(pageSource, {
    window: { matchMedia: () => ({ matches: reducedMotion }) },
    document: {
      body: element('body'),
      documentElement: { style: { setProperty: (name, value) => env.styles.set(name, value) } },
      getElementById: element,
      querySelector(selector) { assert.equal(selector, '.pet-wrap'); return element('wrap'); },
      querySelectorAll(selector) { assert.equal(selector, '[data-action]'); return actions; }
    },
    aicode: {
      overlay: {
        async self() { return plain(env.info); },
        async setDragRegion(rect) { env.dragRegions.push(plain(rect)); },
        async resize(nextWidth, nextHeight, options) {
          if (env.failResize) { env.failResize = false; throw new Error('resize failed'); }
          const change = { width: nextWidth, height: nextHeight, options: plain(options) };
          env.resizes.push(change);
          if (options.animate) env.pending = change;
          else env.finish(change);
        },
        async postMessage(payload) { env.messages.push(plain(payload)); },
        onMessage(handler) { env.receive = handler; }
      }
    },
    setTimeout(callback, delay) { const id = Symbol('timer'); env.timers.set(id, { callback, delay }); return id; },
    clearTimeout(id) { env.timers.delete(id); },
    console
  }, { filename: 'desktop-pet/pet.html' });
  env.element = element;
  env.click = id => element(id).listeners.click();
  env.open = async () => {
    env.receive({ type: 'longPress' });
    await flush();
    if (env.pending) env.finish();
    await flush();
  };
  return env;
}

test('菜单与宠物共用固定宽度舞台，贴边旋转使用独立且固定的原点', () => {
  assert.match(html, /#stage\s*\{[^}]*width: var\(--pet-width\)/);
  assert.match(html, /\.pet-wrap\s*\{[^}]*width: var\(--pet-width\)/);
  assert.match(html, /\.menu\s*\{[^}]*position: absolute;[^}]*top: calc\(var\(--pet-height\) \+ 8px\)/);
  assert.match(html, /\.pet-pose\s*\{[^}]*transform-origin: 50% 65%;[^}]*transition: transform/);
  for (const edge of ['left', 'right', 'top', 'bottom']) {
    const rule = html.match(new RegExp('\\.pet-wrap\\.edge-' + edge + ' \\.pet-pose\\s*\\{([^}]*)\\}'));
    assert.ok(rule, edge);
    assert.doesNotMatch(rule[1], /transform-origin/);
  }
});

test('互动动画优先于思考状态，后续状态更新会清理落地动画', async () => {
  const animationFor = classes => {
    let active = null;
    for (const rule of html.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
      const animation = rule[2].match(/(?:^|;)\s*animation:\s*([\w-]+)/);
      if (!animation) continue;
      for (const selector of rule[1].split(',').map(value => value.trim())) {
        if (!/^\.pet(?:\.[\w-]+)+$/.test(selector)) continue;
        if (selector.slice(1).split('.').every(name => classes.includes(name))) active = animation[1];
      }
    }
    return active;
  };
  assert.equal(animationFor(['pet', 'thinking', 'happy']), 'hop');
  assert.equal(animationFor(['pet', 'thinking', 'dragging']), 'dangle');
  assert.equal(animationFor(['pet', 'thinking', 'landing']), 'land');
  const env = loadPage();
  await flush();
  env.receive({ type: 'state', state: 'thinking' });
  env.receive({ type: 'dragEnd', x: 30, y: 40 });
  env.receive({ type: 'state', state: 'sleep' });
  assert.equal(env.element('pet').classList.contains('landing'), false);
});

for (const [width, height] of [[90, 110], [110, 130], [150, 180], [240, 320]]) {
  test(`${width}×${height} 菜单仅扩展高度，保持宠物缩放与拖拽区`, async () => {
    const env = loadPage({ width, height });
    await flush();
    const scale = env.styles.get('--pet-scale');
    await env.open();
    assert.deepEqual(env.resizes[0], {
      width, height: height + 146, options: { animate: true, x: null, y: null }
    });
    assert.equal(env.styles.get('--pet-width'), width + 'px');
    assert.equal(env.styles.get('--pet-height'), height + 'px');
    assert.equal(env.styles.get('--pet-scale'), scale);
    assert.deepEqual(env.dragRegions.at(-1), { left: 0, top: 0, width, height });
    assert.equal(env.element('menu').attributes['aria-hidden'], 'false');
    assert.equal(env.element('feed').disabled, false);
  });
}

for (const action of ['feed', 'sleep', 'hide']) {
  test(`${action} 等收起动画完成后才派发，并恢复展开前的 dp 坐标`, async () => {
    const env = loadPage();
    await flush();
    const anchor = { x: env.info.x, y: env.info.y };
    await env.open();
    assert.equal(env.info.y, anchor.y - 146);
    const result = env.click(action);
    await flush();
    assert.equal(env.messages.length, 0);
    assert.equal(env.element('menu').attributes['aria-hidden'], 'true');
    assert.equal(env.element('feed').disabled, true);
    assert.deepEqual(env.resizes.at(-1), {
      width: 110, height: 130, options: { animate: true, ...anchor }
    });
    env.finish();
    await result;
    assert.deepEqual(env.messages, [{ type: 'menuAction', action }]);
    assert.equal(env.info.x, anchor.x);
    assert.equal(env.info.y, anchor.y);
    assert.equal(env.styles.get('--pet-width'), '110px');
  });
}

test('展开和收起途中重复点击、长按不会打断尺寸动画或重复提交', async () => {
  const env = loadPage();
  await flush();
  env.receive({ type: 'longPress' });
  env.receive({ type: 'longPress' });
  await flush();
  await env.click('feed');
  env.receive({ type: 'click' });
  assert.equal(env.resizes.length, 1);
  assert.equal(env.element('menu').attributes['aria-hidden'], 'true');
  env.finish();
  await flush();
  const result = env.click('sleep');
  await env.click('feed');
  env.receive({ type: 'longPress' });
  await flush();
  assert.equal(env.resizes.length, 2);
  assert.equal(env.messages.length, 0);
  env.finish();
  await result;
  assert.deepEqual(env.messages, [{ type: 'menuAction', action: 'sleep' }]);
});

test('菜单展开后拖动并吸附会同步更新收起锚点', async () => {
  const env = loadPage();
  await flush();
  await env.open();
  env.receive({ type: 'dragStart' });
  env.info.x -= 35;
  env.info.y -= 20;
  env.receive({ type: 'dragEnd', x: env.info.x, y: env.info.y });
  env.info.x = 290;
  env.receive({ type: 'snap', edge: 'right', x: env.info.x, y: env.info.y });
  env.click('menuClose');
  await flush();
  assert.deepEqual(env.resizes.at(-1).options, { animate: true, x: 290, y: 650 });
  env.finish();
  assert.equal(env.info.x, 290);
  assert.equal(env.info.y, 650);
});

test('关闭菜单按钮和点击宠物仅收起，不触发菜单动作', async () => {
  const env = loadPage();
  await flush();
  await env.open();
  env.click('menuClose');
  await flush();
  env.finish();
  await flush();
  await env.open();
  env.receive({ type: 'click' });
  await flush();
  env.finish();
  await flush();
  assert.equal(env.messages.length, 0);
  assert.equal(env.element('menu').attributes['aria-hidden'], 'true');
});

test('配置页修改尺寸会重置菜单并更新下一次展开尺寸', async () => {
  const env = loadPage();
  await flush();
  await env.open();
  env.finish({ width: 150, height: 180, options: {} });
  assert.equal(env.element('menu').attributes['aria-hidden'], 'true');
  assert.equal(env.styles.get('--pet-width'), '150px');
  assert.equal(env.styles.get('--pet-height'), '180px');
  await env.open();
  assert.equal(env.resizes.at(-1).width, 150);
  assert.equal(env.resizes.at(-1).height, 326);
});

test('收起失败保留可操作菜单，不派发动作，重试后可完成', async () => {
  const env = loadPage();
  await flush();
  await env.open();
  env.failResize = true;
  await env.click('feed');
  assert.equal(env.messages.length, 0);
  assert.equal(env.element('menu').attributes['aria-hidden'], 'false');
  assert.equal(env.element('feed').disabled, false);
  const result = env.click('feed');
  await flush();
  env.finish();
  await result;
  assert.deepEqual(env.messages, [{ type: 'menuAction', action: 'feed' }]);
});

test('拖拽恢复直立、松手回弹、四边贴附与状态动画独立切换', async () => {
  const env = loadPage();
  await flush();
  const pet = env.element('pet');
  const wrap = env.element('wrap');
  for (const edge of ['left', 'right', 'top', 'bottom']) {
    env.receive({ type: 'snap', edge, x: 20, y: 30 });
    assert.equal(wrap.classList.contains('edge-' + edge), true);
    env.receive({ type: 'dragStart' });
    assert.equal(wrap.classList.contains('edge-' + edge), false);
    assert.equal(pet.classList.contains('dragging'), true);
    env.receive({ type: 'dragEnd', x: 30, y: 40 });
    assert.equal(pet.classList.contains('landing'), true);
    pet.listeners.animationend({ target: pet, animationName: 'land' });
    assert.equal(pet.classList.contains('landing'), false);
  }
  env.receive({ type: 'click' });
  assert.equal(pet.classList.contains('happy'), true);
  pet.listeners.animationend({ target: pet, animationName: 'hop' });
  assert.equal(pet.classList.contains('happy'), false);
  env.receive({ type: 'state', state: 'feeding' });
  assert.equal(pet.classList.contains('feeding'), true);
  assert.equal(env.element('stateLabel').textContent, '吃饭中');
  env.receive({ type: 'state', state: 'sleep' });
  assert.equal(pet.classList.contains('feeding'), false);
  assert.equal(pet.classList.contains('sleep'), true);
});

test('减少动态效果时菜单立即完成，交互不依赖 CSS 动画结束', async () => {
  const env = loadPage({ reducedMotion: true });
  await flush();
  await env.open();
  assert.equal(env.resizes[0].options.animate, false);
  await env.click('feed');
  assert.equal(env.pending, null);
  assert.deepEqual(env.messages, [{ type: 'menuAction', action: 'feed' }]);
});

function loadRuntime() {
  let onLine;
  let seq = 0;
  const pending = new Map();
  const env = { calls: [], timers: new Map() };
  const receive = message => onLine(JSON.stringify(message));
  vm.runInNewContext(runtimeSource, {
    require(name) {
      assert.equal(name, 'readline');
      return { createInterface: () => ({ on: (_event, handler) => { onLine = handler; } }) };
    },
    process: {
      stdin: {},
      stdout: {
        write(line) {
          const message = JSON.parse(line);
          if (!message.method) {
            const callback = pending.get(message.id);
            pending.delete(message.id);
            if (message.error) callback.reject(message.error);
            else callback.resolve(message.result);
          } else {
            env.calls.push(message);
            const respond = () => receive({ id: message.id, result: { ok: true } });
            if (env.holdResponse && env.holdResponse(message)) env.releaseResponse = respond;
            else queueMicrotask(respond);
          }
        }
      }
    },
    setTimeout(callback, delay) { const id = Symbol('timer'); env.timers.set(id, { callback, delay }); return id; },
    clearTimeout(id) { env.timers.delete(id); }
  }, { filename: 'desktop-pet/main.js' });
  env.invoke = (handler, args = {}) => new Promise((resolve, reject) => {
    const id = 'invoke-' + (++seq);
    pending.set(id, { resolve, reject });
    receive({ id, method: 'hook.invoke', params: { handler, args } });
  });
  env.action = action => env.invoke('overlay.message', { payload: { type: 'menuAction', action } });
  env.states = () => env.calls.filter(call => call.method === 'host.overlay.postMessage')
    .map(call => call.params.payload).filter(payload => payload.type === 'state').map(payload => payload.state);
  return env;
}

test('喂食派发专用动画，三秒后恢复待机', async () => {
  const env = loadRuntime();
  await env.action('feed');
  assert.deepEqual(env.states(), ['feeding']);
  assert.equal(env.timers.size, 1);
  const [id, timer] = [...env.timers][0];
  assert.equal(timer.delay, 3000);
  env.timers.delete(id);
  timer.callback();
  await flush();
  assert.deepEqual(env.states(), ['feeding', 'idle']);
});

test('喂食或运行结束后休息，会取消之前的待机定时器', async () => {
  const env = loadRuntime();
  await env.action('feed');
  await env.action('sleep');
  assert.equal(env.timers.size, 0);
  assert.deepEqual(env.states(), ['feeding', 'sleep']);
  await env.invoke('hooks.agentRunFinished', { status: 'success' });
  assert.equal(env.timers.size, 1);
  await env.action('sleep');
  assert.equal(env.timers.size, 0);
  assert.equal(env.states().at(-1), 'sleep');
});

for (const phase of ['state', 'hint']) {
  for (const next of ['sleep', 'thinking', 'working']) {
    test(`喂食 ${phase} 响应延迟时，${next} 状态不会被旧请求重新安排待机`, async () => {
      const env = loadRuntime();
      env.holdResponse = call => call.params.payload && call.params.payload.type === phase;
      const feeding = env.action('feed');
      await flush();
      const release = env.releaseResponse;
      assert.equal(typeof release, 'function');
      env.holdResponse = null;
      if (next === 'sleep') await env.action('sleep');
      else await env.invoke(next === 'thinking' ? 'hooks.agentRunStarted' : 'hooks.agentToolStarted');
      assert.equal(env.timers.size, 0);
      release();
      await feeding;
      assert.equal(env.timers.size, 0);
      assert.equal(env.states().at(-1), next);
    });
  }
}

test('新一轮思考和工具工作不会被上一轮的定时器覆盖', async () => {
  const env = loadRuntime();
  await env.invoke('hooks.agentRunFinished', { status: 'success' });
  await env.invoke('hooks.agentRunStarted');
  assert.equal(env.timers.size, 0);
  assert.equal(env.states().at(-1), 'thinking');
  await env.action('feed');
  await env.invoke('hooks.agentToolStarted');
  assert.equal(env.timers.size, 0);
  assert.equal(env.states().at(-1), 'working');
});
