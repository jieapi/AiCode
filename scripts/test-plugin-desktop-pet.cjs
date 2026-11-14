'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const sample = path.join(__dirname, '..', 'plugin-samples', 'desktop-pet');
const html = fs.readFileSync(path.join(sample, 'pages/pet.html'), 'utf8');
const configHtml = fs.readFileSync(path.join(sample, 'pages/config.html'), 'utf8');
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
  env.menuActions = () => env.messages.filter(message => message.type === 'menuAction');
  env.click = id => element(id).listeners.click();
  env.open = async () => {
    env.receive({ type: 'longPress' });
    await flush();
    if (env.pending) env.finish();
    await flush();
  };
  return env;
}

test('配置页接入宿主主题与组件库，不再使用临时页面结构', () => {
  assert.match(configHtml, /\/__aicode\/theme\.css/);
  assert.match(configHtml, /\/__aicode\/ui\.css/);
  assert.match(configHtml, /\/__aicode\/aicode-ui-vue\.js/);
  assert.match(configHtml, /<aicode-group\b/);
  assert.match(configHtml, /<aicode-switch\b/);
  assert.doesNotMatch(configHtml, /<pre id="log"/);
});

test('菜单与宠物共用固定宽度舞台，贴边旋转使用独立且固定的原点', () => {
  assert.match(html, /#stage\s*\{[^}]*width: var\(--pet-width\)/);
  assert.match(html, /\.pet-wrap\s*\{[^}]*width: var\(--pet-width\)/);
  assert.match(html, /\.menu\s*\{[^}]*position: absolute;[^}]*top: calc\(var\(--pet-height\) \+ 8px\)/);
  assert.match(html, /\.pet-pose\s*\{[^}]*transform-origin: 50% 50%;[^}]*transition: transform/);
  for (const edge of ['left', 'right', 'top', 'bottom']) {
    const rule = html.match(new RegExp('\\.pet-wrap\\.edge-' + edge + ' \\.pet-pose\\s*\\{([^}]*)\\}'));
    assert.ok(rule, edge);
    assert.doesNotMatch(rule[1], /transform-origin/);
  }
});

test('左右贴边后的呼吸、跳跃与落地动作不会裁切耳朵', () => {
  const rotate = (point, degrees) => {
    const angle = degrees * Math.PI / 180;
    return [point[0] * Math.cos(angle) - point[1] * Math.sin(angle),
      point[0] * Math.sin(angle) + point[1] * Math.cos(angle)];
  };
  const transform = (point, source, origin) => {
    let result = [point[0] - origin[0], point[1] - origin[1]];
    const functions = [...source.matchAll(/(translateX|translateY|translate|scaleX|scaleY|scale|rotate)\(([^)]*)\)/g)];
    for (const [, name, args] of functions.reverse()) {
      const values = args.split(',').map(value => parseFloat(value));
      if (name === 'rotate') result = rotate(result, values[0]);
      else if (name === 'translateX') result[0] += values[0];
      else if (name === 'translateY') result[1] += values[0];
      else if (name === 'translate') { result[0] += values[0]; result[1] += values[1] || 0; }
      else if (name === 'scaleX') result[0] *= values[0];
      else if (name === 'scaleY') result[1] *= values[0];
      else { result[0] *= values[0]; result[1] *= values[1] == null ? values[0] : values[1]; }
    }
    return [result[0] + origin[0], result[1] + origin[1]];
  };
  const states = ['translateY(0)'];
  for (const name of ['breathe', 'hop', 'land', 'think', 'work', 'eat']) {
    const frames = html.match(new RegExp('@keyframes ' + name + ' \\{([\\s\\S]*?)\\n    \\}'))[1];
    states.push(...[...frames.matchAll(/transform:\s*([^;]+);/g)].map(match => match[1]));
  }
  const radius = [12, 46, 16, 46].map(value => value * 54 / 62);
  const arcs = [[radius[0], radius[0], 180], [54 - radius[1], radius[1], 270],
    [54 - radius[2], 66 - radius[2], 0], [radius[3], 66 - radius[3], 90]];
  const outline = arcs.flatMap(([x, y, start], index) => Array.from({ length: 9 }, (_, step) => {
    const angle = (start + step * 90 / 8) * Math.PI / 180;
    return [x + radius[index] * Math.cos(angle), y + radius[index] * Math.sin(angle)];
  }));
  const points = [];
  for (const state of states) {
    for (const side of ['left', 'right']) {
      for (const earAngle of [10, 18, 30]) {
        const origin = side === 'left' ? [40.5, 56.1] : [13.5, 56.1];
        const ear = side === 'left' ? `rotate(-${earAngle}deg)` : `rotate(${earAngle}deg) scaleX(-1)`;
        for (const vertex of outline) {
          let point = transform(vertex, ear, origin);
          point = [point[0] + (side === 'left' ? 10 : 108), point[1] + 34];
          points.push({ point: transform(point, state, [85, 190]), state, side });
        }
      }
    }
  }
  for (const edge of ['left', 'right']) {
    const rule = html.match(new RegExp('\\.pet-wrap\\.edge-' + edge + ' \\.pet-pose\\s*\\{([^}]*)\\}'))[1];
    const pose = rule.match(/translate\(0, 0\) rotate\((-?[\d.]+)deg\) scale\(([\d.]+)\)/);
    assert.ok(pose);
    const angle = Number(pose[1]);
    const sideScale = Number(pose[2]);
    for (const [width, height] of [[90, 110], [110, 130], [150, 180], [240, 320]]) {
      const scale = Math.min(width / 170, height / 190) * 0.95;
      for (let step = 20; step <= 21; step++) {
        const progress = step / 20;
        const shrink = 1 + (sideScale - 1) * progress;
        for (const { point, state, side } of points) {
          const turned = rotate([(point[0] - 85) * shrink, (point[1] - 95) * shrink], angle * progress);
          const x = width / 2 + turned[0] * scale;
          const y = height - 6 + (turned[1] - 95) * scale;
          assert.ok(x >= 0 && x <= width && y >= 0 && y <= height,
            `${edge}/${width}x${height}/${side}/${state}/${progress}: (${x}, ${y})`);
        }
      }
    }
  }
});

test('睡眠标志仅在睡眠时出现，默认不运行改变透明度的浮动动画', async () => {
  assert.match(html, /id="sleepMark" hidden aria-hidden="true"/);
  assert.match(html, /\.pet \.zzz\s*\{[^}]*animation: none/);
  assert.match(html, /\.pet\.sleep \.zzz\s*\{[^}]*animation: float/);
  const env = loadPage();
  await flush();
  assert.equal(env.element('sleepMark').hidden, true);
  for (const state of ['sleep', 'idle', 'thinking', 'working', 'feeding', 'success', 'error', 'sleep']) {
    env.receive({ type: 'state', state });
    assert.equal(env.element('sleepMark').hidden, state !== 'sleep');
    assert.equal(env.element('sleepMark').attributes['aria-hidden'], String(state !== 'sleep'));
  }
});

test('睡眠时抚摸只上报唤醒尝试，不先播放清醒动作', async () => {
  const env = loadPage();
  await flush();
  assert.equal(env.messages[0].type, 'petReady');
  env.receive({ type: 'state', state: 'sleep' });
  env.receive({ type: 'click' });
  assert.equal(env.messages.at(-1).type, 'pet');
  assert.equal(env.element('pet').classList.contains('sleep'), true);
  assert.equal(env.element('pet').classList.contains('happy'), false);
  assert.equal(env.element('sleepMark').hidden, false);
  env.receive({ type: 'state', state: 'idle' });
  assert.equal(env.element('sleepMark').hidden, true);
});

test('菜单和拖拽交互上报活动，供空闲计时重置', async () => {
  const env = loadPage();
  await flush();
  await env.open();
  assert.equal(env.messages.at(-1).type, 'activity');
  env.receive({ type: 'dragStart' });
  assert.equal(env.messages.at(-1).type, 'activity');
  env.receive({ type: 'dragEnd', x: 30, y: 40 });
  assert.equal(env.messages.at(-1).type, 'activity');
  env.click('menuClose');
  await flush();
  assert.equal(env.messages.at(-1).type, 'activity');
  env.finish();
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
    assert.equal(env.menuActions().length, 0);
    assert.equal(env.element('menu').attributes['aria-hidden'], 'true');
    assert.equal(env.element('feed').disabled, true);
    assert.deepEqual(env.resizes.at(-1), {
      width: 110, height: 130, options: { animate: true, ...anchor }
    });
    env.finish();
    await result;
    assert.deepEqual(env.menuActions(), [{ type: 'menuAction', action }]);
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
  assert.equal(env.menuActions().length, 0);
  env.finish();
  await result;
  assert.deepEqual(env.menuActions(), [{ type: 'menuAction', action: 'sleep' }]);
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
  assert.equal(env.menuActions().length, 0);
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
  assert.equal(env.menuActions().length, 0);
  assert.equal(env.element('menu').attributes['aria-hidden'], 'false');
  assert.equal(env.element('feed').disabled, false);
  const result = env.click('feed');
  await flush();
  env.finish();
  await result;
  assert.deepEqual(env.menuActions(), [{ type: 'menuAction', action: 'feed' }]);
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
  assert.deepEqual(env.menuActions(), [{ type: 'menuAction', action: 'feed' }]);
});

function loadRuntime(options = {}) {
  let onLine;
  let seq = 0;
  const pending = new Map();
  const env = { calls: [], timers: new Map(), now: 0, random: 0.9, settings: options.settings };
  const receive = message => onLine(JSON.stringify(message));
  const resultFor = message => {
    if (message.method === 'host.fs.read') {
      return env.settings == null ? { ok: true } : { content: JSON.stringify(env.settings) };
    }
    if (message.method === 'host.overlay.create') {
      return {
        overlayId: 'demo.desktop-pet:pet',
        width: message.params.width,
        height: message.params.height,
        visibilityPolicy: message.params.visibilityPolicy
      };
    }
    return { ok: true };
  };
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
            const respond = error => receive(error
              ? { id: message.id, error }
              : { id: message.id, result: resultFor(message) });
            if (env.holdResponse && env.holdResponse(message)) env.releaseResponse = respond;
            else queueMicrotask(respond);
          }
        }
      }
    },
    setTimeout(callback, delay) {
      const id = Symbol('timer');
      env.timers.set(id, { callback, delay, due: env.now + delay });
      return id;
    },
    clearTimeout(id) { env.timers.delete(id); },
    Math: Object.assign(Object.create(Math), { random: () => env.random })
  }, { filename: 'desktop-pet/main.js' });
  env.invoke = (handler, args = {}) => new Promise((resolve, reject) => {
    const id = 'invoke-' + (++seq);
    pending.set(id, { resolve, reject });
    receive({ id, method: 'hook.invoke', params: { handler, args } });
  });
  env.action = action => env.invoke('overlay.message', { payload: { type: 'menuAction', action } });
  env.message = payload => env.invoke('overlay.message', { overlayId: 'demo.desktop-pet:pet', payload });
  env.advance = async milliseconds => {
    const target = env.now + milliseconds;
    for (let step = 0; step < 32; step++) {
      const next = [...env.timers].filter(([, timer]) => timer.due <= target)
        .sort((a, b) => a[1].due - b[1].due)[0];
      if (!next) { env.now = target; return; }
      env.now = next[1].due;
      env.timers.delete(next[0]);
      next[1].callback();
      await flush();
    }
    assert.fail('定时器执行次数超过上限');
  };
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

test('桌宠显示后空闲满三分钟才自动睡眠', async () => {
  const env = loadRuntime();
  await env.message({ type: 'petReady' });
  assert.deepEqual(env.states(), ['idle']);
  assert.equal([...env.timers.values()][0].delay, 180000);
  await env.advance(179999);
  assert.deepEqual(env.states(), ['idle']);
  await env.advance(1);
  assert.deepEqual(env.states(), ['idle', 'sleep']);
  assert.equal(env.timers.size, 0);
});

for (const type of ['activity', 'pet']) {
  test(`${type} 交互会从最后一次活动重新计算三分钟`, async () => {
    const env = loadRuntime();
    await env.message({ type: 'petReady' });
    await env.advance(120000);
    await env.message({ type });
    await env.advance(179999);
    assert.equal(env.states().at(-1), 'idle');
    await env.advance(1);
    assert.equal(env.states().at(-1), 'sleep');
  });
}

for (const hook of ['hooks.agentRunStarted', 'hooks.agentToolStarted', 'hooks.agentToolFinished']) {
  test(`${hook} 期间不会自动睡觉，任务完成待机后重新计时`, async () => {
    const env = loadRuntime();
    await env.message({ type: 'petReady' });
    await env.advance(179000);
    await env.invoke(hook);
    await env.advance(300000);
    assert.equal(env.states().includes('sleep'), false);
    await env.invoke('hooks.agentRunFinished', { status: 'success' });
    await env.advance(2999);
    assert.equal(env.states().at(-1), 'success');
    await env.advance(1);
    assert.equal(env.states().at(-1), 'idle');
    await env.advance(180000);
    assert.equal(env.states().at(-1), 'sleep');
  });
}

test('进食结束进入待机后开始三分钟计时', async () => {
  const env = loadRuntime();
  await env.message({ type: 'petReady' });
  await env.action('feed');
  await env.advance(2999);
  assert.equal(env.states().at(-1), 'feeding');
  await env.advance(1);
  assert.equal(env.states().at(-1), 'idle');
  await env.advance(180000);
  assert.equal(env.states().at(-1), 'sleep');
});

for (const random of [0, 0.299, 0.3, 0.9]) {
  test(`睡眠抚摸按30%概率唤醒，随机值${random}`, async () => {
    const env = loadRuntime();
    env.random = random;
    await env.message({ type: 'petReady' });
    await env.action('sleep');
    await env.message({ type: 'pet' });
    const awake = random < 0.3;
    assert.equal(env.states().at(-1), awake ? 'idle' : 'sleep');
    assert.equal(env.timers.size, awake ? 1 : 0);
    if (awake) {
      await env.advance(180000);
      assert.equal(env.states().at(-1), 'sleep');
    }
  });
}

test('长按、拖动不直接唤醒，明确喂食或新任务会切换状态', async () => {
  const env = loadRuntime();
  env.random = 0;
  await env.message({ type: 'petReady' });
  await env.action('sleep');
  await env.message({ type: 'activity' });
  assert.equal(env.states().at(-1), 'sleep');
  assert.equal(env.timers.size, 0);
  await env.action('feed');
  assert.equal(env.states().at(-1), 'feeding');
  await env.action('sleep');
  await env.invoke('hooks.agentRunStarted');
  assert.equal(env.states().at(-1), 'thinking');
  assert.equal(env.timers.size, 0);
});

test('页面重新就绪会同步已有睡眠状态，关闭后停止定时器', async () => {
  const env = loadRuntime();
  await env.invoke('overlay.created', { overlayId: 'demo.desktop-pet:pet' });
  await env.action('sleep');
  await env.message({ type: 'petReady' });
  assert.deepEqual(env.states(), ['sleep', 'sleep']);
  await env.action('feed');
  await env.invoke('overlay.closed');
  assert.equal(env.timers.size, 0);
  await env.advance(300000);
  assert.equal(env.states().at(-1), 'feeding');
});

for (const state of ['feeding', 'success', 'error']) {
  test(`${state} 期间关闭再重开会回到待机并自动睡眠`, async () => {
    const env = loadRuntime();
    await env.message({ type: 'petReady' });
    if (state === 'feeding') await env.action('feed');
    else await env.invoke('hooks.agentRunFinished', { status: state === 'success' ? 'success' : 'failed' });
    await env.invoke('overlay.closed');
    await env.invoke('overlay.created', { overlayId: 'demo.desktop-pet:pet' });
    await env.message({ type: 'petReady' });
    assert.equal(env.states().at(-1), 'idle');
    assert.equal(env.timers.size, 1);
    await env.advance(180000);
    assert.equal(env.states().at(-1), 'sleep');
  });
}

test('旧窗口迟到的不存在错误不会取消新窗口的空闲计时', async () => {
  const env = loadRuntime();
  await env.message({ type: 'petReady' });
  env.holdResponse = call => call.params.payload && call.params.payload.state === 'feeding';
  const feeding = env.action('feed');
  await flush();
  const release = env.releaseResponse;
  env.holdResponse = null;
  await env.invoke('overlay.closed');
  await env.invoke('overlay.created', { overlayId: 'demo.desktop-pet:pet' });
  await env.message({ type: 'petReady' });
  release({ code: 'OVERLAY_NOT_FOUND', message: 'old overlay closed' });
  await feeding;
  assert.equal(env.states().at(-1), 'idle');
  assert.equal(env.timers.size, 1);
  await env.advance(180000);
  assert.equal(env.states().at(-1), 'sleep');
});

test('隐藏请求迟到回包不会清除重新显示后的空闲计时', async () => {
  const env = loadRuntime();
  await env.message({ type: 'petReady' });
  env.holdResponse = call => call.method === 'host.overlay.close';
  const hiding = env.action('hide');
  await flush();
  const release = env.releaseResponse;
  env.holdResponse = null;
  await env.invoke('overlay.created', { overlayId: 'demo.desktop-pet:pet' });
  await env.message({ type: 'petReady' });
  release();
  await hiding;
  await env.message({ type: 'pet' });
  await env.advance(180000);
  assert.equal(env.states().at(-1), 'sleep');
});

test('隐藏桌宠会同时清除待机与睡眠定时器', async () => {
  const env = loadRuntime();
  await env.message({ type: 'petReady' });
  await env.action('hide');
  assert.equal(env.timers.size, 0);
  await env.advance(300000);
  assert.equal(env.states().at(-1), 'idle');
});

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

test('启用插件后按随 App 启动设置自动显示桌宠', async () => {
  const env = loadRuntime({ settings: { autoStart: true, width: 150, height: 180, visibilityPolicy: 'foreground' } });
  const result = await env.invoke('hooks.pluginEnabled');
  assert.equal(result.ok, true);
  const create = env.calls.find(call => call.method === 'host.overlay.create');
  assert.ok(create, '应创建桌宠浮层');
  assert.deepEqual(plain(create.params), { surface: 'pet', width: 150, height: 180, visibilityPolicy: 'foreground' });
  assert.equal(env.timers.size, 1);
  const status = await env.invoke('overlay.status');
  assert.equal(status.overlayId, 'demo.desktop-pet:pet');
  assert.equal(status.state, 'idle');
});

test('未开启随 App 启动时启用插件不显示桌宠', async () => {
  const env = loadRuntime({ settings: { autoStart: false } });
  const result = await env.invoke('hooks.pluginEnabled');
  assert.equal(result.skipped, true);
  assert.equal(env.calls.some(call => call.method === 'host.overlay.create'), false);
  const status = await env.invoke('overlay.status');
  assert.equal(status.overlayId, null);
});

test('App 启动与插件启用共用同一套自动显示逻辑', async () => {
  const env = loadRuntime({ settings: { autoStart: true } });
  await env.invoke('hooks.boot');
  assert.equal(env.calls.filter(call => call.method === 'host.overlay.create').length, 1);
});
