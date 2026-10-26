// 128bit family colour themes. Shared file: keep the copies in every app's app/ folder identical.
// Usage: <script src="theme.js" data-app="calc" data-accent="orange" data-second="teal" data-third="blue" data-lcd data-mount="header .tools"></script>
// Every colour is the user's to pick: main/second/third colours, background, cards, text, and
// (with data-lcd) the calculator screen. Picks map onto the app's own CSS variables (--orange,
// --orange-deep…), plus generic --accent / --second / --third, and a 'themechange' event fires
// so canvases can redraw.
(function(){
  const me = document.currentScript, cfg = me.dataset;
  const APP = cfg.app, ACC = cfg.accent, SEC = cfg.second, THI = cfg.third;
  const root = document.documentElement;
  const KEY = '128bit.theme.' + APP, GLOBAL = '128bit.theme';

  const SWATCHES = [
    ['Orange', '#ff9f1c'], ['Gold', '#facc15'], ['Lime', '#a3e635'], ['Green', '#4ade80'], ['Teal', '#2dd4bf'], ['Sky', '#38bdf8'],
    ['Blue', '#6b8cff'], ['Purple', '#a78bfa'], ['Pink', '#ff5d8f'], ['Red', '#ff5d5d'], ['Ghost', '#e5e7eb'], ['Coal', '#3f3f46']
  ];
  // Preset backgrounds: [background, cards, borders, buttons]
  const BACKGROUNDS = {
    Midnight: ['#0b0b16', '#141428', '#2a2a4a', '#1f1f3a'],
    Space:    ['#04050a', '#0c1120', '#1d2944', '#141b30'],
    Forest:   ['#06120b', '#0e2116', '#1f3d29', '#15301f'],
    Ocean:    ['#04101a', '#0a1d2e', '#173a55', '#102a40'],
    Grape:    ['#100717', '#1d0f2b', '#3a2152', '#2a173d'],
    Wine:     ['#14070c', '#24101a', '#47203a', '#341629'],
    Cocoa:    ['#120c07', '#22180f', '#43311f', '#33251a'],
    Slate:    ['#0e1014', '#1a1d24', '#2f3440', '#242832'],
    Black:    ['#000000', '#0d0d0d', '#262626', '#1a1a1a'],
    Paper:    ['#efe8d8', '#fbf6ea', '#cfc4ad', '#e4dac4']
  };
  const TEXTS = [['White', '#f4f1ff'], ['Cream', '#fff3d6'], ['Mint', '#d1fae5'], ['Ice', '#dbeafe'], ['Rose', '#ffe4ec'], ['Ink', '#1b1b24']];
  const LCDS = { Classic: '#b8c99a', Ice: '#a9d4e6', Amber: '#f1c27d', Paper: '#e8e6dc', Grape: '#c7b5e8', Night: '#16241a' };
  // One-tap looks that set everything at once.
  const THEMES = {
    'Default':   null,
    'Game Boy':  { accent: '#9bbc0f', second: '#8bac0f', third: '#306230', bg: '#0f380f', card: 'auto', ink: '#e0f8d0', lcd: '#9bbc0f' },
    'Neon':      { accent: '#ff2bd6', second: '#00f0ff', third: '#faff00', bg: '#08000f', card: 'auto', ink: '#f6e9ff', lcd: '#0a1a2a' },
    'Sunset':    { accent: '#ff7a45', second: '#ff4f8b', third: '#ffd166', bg: '#1a0b14', card: 'auto', ink: '#ffeedd', lcd: '#f1c27d' },
    'Ocean':     { accent: '#38bdf8', second: '#2dd4bf', third: '#a5f3fc', bg: 'Ocean', card: 'auto', ink: '#e0f2fe', lcd: '#a9d4e6' },
    'Forest':    { accent: '#4ade80', second: '#facc15', third: '#a3e635', bg: 'Forest', card: 'auto', ink: '#ecfccb', lcd: '#b8c99a' },
    'Candy':     { accent: '#ff5d8f', second: '#a78bfa', third: '#38bdf8', bg: 'Grape', card: 'auto', ink: '#ffe4ec', lcd: '#c7b5e8' },
    'Mono':      { accent: '#e5e7eb', second: '#9ca3af', third: '#f4f4f5', bg: 'Black', card: 'auto', ink: '#fafafa', lcd: '#e8e6dc' },
    'Paper':     { accent: '#e8590c', second: '#0f766e', third: '#7c3aed', bg: 'Paper', card: 'auto', ink: '#1b1b24', lcd: '#e8e6dc' }
  };

  const css0 = n => getComputedStyle(root).getPropertyValue('--' + n).trim();
  const defaults = { accent: css0(ACC), second: css0(SEC), third: THI ? css0(THI) : '#38bdf8', bg: 'Midnight', card: 'auto', ink: '#f4f1ff', lcd: 'Classic' };
  const read = k => { try { return JSON.parse(localStorage.getItem(k)); } catch { return null; } };
  const write = (k, v) => { try { v === null ? localStorage.removeItem(k) : localStorage.setItem(k, JSON.stringify(v)); } catch {} };
  const SHARED = ['accent', 'second', 'third', 'bg', 'card', 'ink'];
  const load = () => ({ ...defaults, ...(read(KEY) || {}), ...(read(GLOBAL) || {}) });
  let theme = load();

  /* ---------- colour maths ---------- */
  const rgb = h => { const n = parseInt(h.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
  const hex = a => '#' + a.map(v => Math.round(Math.max(0, Math.min(255, v))).toString(16).padStart(2, '0')).join('');
  const mix = (a, b, t) => { const x = rgb(a), y = rgb(b); return hex(x.map((v, i) => v + (y[i] - v) * t)); };
  const shade = (h, amt) => mix(h, amt < 0 ? '#000000' : '#ffffff', Math.abs(amt));
  const lum = h => { const [r, g, b] = rgb(h).map(v => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
  const isHex = v => /^#[0-9a-f]{6}$/i.test(v || '');

  function palette(){
    const ink = isHex(theme.ink) ? theme.ink : defaults.ink;
    let bg, panel, line, key;
    if (BACKGROUNDS[theme.bg]) [bg, panel, line, key] = BACKGROUNDS[theme.bg];
    else { bg = isHex(theme.bg) ? theme.bg : BACKGROUNDS.Midnight[0]; panel = mix(bg, ink, 0.07); line = mix(bg, ink, 0.18); key = mix(bg, ink, 0.11); }
    if (isHex(theme.card)){ panel = theme.card; line = mix(theme.card, ink, 0.16); key = mix(theme.card, ink, 0.08); }
    const lcd = LCDS[theme.lcd] || (isHex(theme.lcd) ? theme.lcd : LCDS.Classic);
    const lcdInk = lum(lcd) > 0.18 ? mix(lcd, '#000000', 0.82) : mix(lcd, '#ffffff', 0.75);
    return { ink, bg, panel, line, key, muted: mix(ink, bg, 0.4), lcd, lcdInk, lcdEdge: mix(lcd, '#000000', 0.2), light: lum(bg) > 0.35 };
  }
  function apply(){
    const set = (k, v) => root.style.setProperty(k, v), P = palette();
    const pair = (name, c, generic) => { const deep = shade(c, -0.45); set('--' + name, c); set('--' + name + '-deep', deep); set('--' + generic, c); set('--' + generic + '-deep', deep); };
    pair(ACC, theme.accent, 'accent'); pair(SEC, theme.second, 'second');
    if (THI) pair(THI, theme.third, 'third');
    set('--bg', P.bg); set('--panel', P.panel); set('--line', P.line); set('--key', P.key);
    set('--ink', P.ink); set('--muted', P.muted);
    set('--lcd', P.lcd); set('--lcd-ink', P.lcdInk); set('--lcd-edge', P.lcdEdge);
    root.style.colorScheme = P.light ? 'light' : 'dark';
    const meta = document.querySelector('meta[name=theme-color]'); if (meta) meta.content = P.bg;
    window.dispatchEvent(new CustomEvent('themechange', { detail: theme }));
  }
  apply();

  /* ---------- picker UI ---------- */
  const css = `
  .th-btn{font-family:'Press Start 2P',monospace;font-size:12px;background:var(--key);color:var(--ink);border:none;padding:7px 9px;box-shadow:0 3px 0 #000;cursor:pointer;line-height:1}
  .th-btn:active{transform:translateY(2px);box-shadow:0 1px 0 #000}
  .th-modal{position:fixed;inset:0;background:rgba(0,0,0,.8);display:none;align-items:flex-end;justify-content:center;z-index:60}
  .th-modal.open{display:flex}
  .th-sheet{background:var(--panel);border:3px solid var(--line);border-bottom:none;width:100%;max-width:560px;padding:18px 16px 24px;max-height:92dvh;overflow-y:auto;color:var(--ink);font-family:'Inter',system-ui,sans-serif;text-align:center}
  .th-sheet h3{font-family:'Press Start 2P',monospace;font-size:12px;color:var(--accent);margin-bottom:4px;line-height:1.6}
  .th-sheet .th-sub{font-size:12px;color:var(--muted);margin-bottom:6px}
  .th-sheet .th-l{font-family:'Press Start 2P',monospace;font-size:8px;color:var(--muted);margin:18px 0 9px}
  .th-row{display:flex;flex-wrap:wrap;gap:8px;align-items:center;justify-content:center}
  .th-sw{width:36px;height:36px;border:3px solid var(--bg);cursor:pointer;box-shadow:0 3px 0 #000;padding:0}
  .th-sw.on{outline:3px solid var(--ink);outline-offset:1px}
  .th-tile{width:76px;height:50px;border:3px solid var(--bg);cursor:pointer;position:relative;padding:0;box-shadow:0 3px 0 #000;overflow:hidden}
  .th-tile span{position:absolute;left:0;right:0;bottom:0;font-family:'Press Start 2P',monospace;font-size:6px;color:#fff;padding:3px 2px;background:rgba(0,0,0,.5);white-space:nowrap}
  .th-tile i{position:absolute;left:8px;right:8px;top:7px;height:12px;font-style:normal;font-family:'Press Start 2P',monospace;font-size:9px;line-height:12px}
  .th-tile b{position:absolute;top:7px;width:10px;height:10px}
  .th-tile.on{outline:3px solid var(--ink);outline-offset:1px}
  .th-pick{position:relative;width:44px;height:36px;border:3px dashed var(--line);background:var(--bg);cursor:pointer;display:grid;place-items:center;font-size:15px;box-shadow:0 3px 0 #000}
  .th-pick input{position:absolute;inset:0;width:100%;height:100%;opacity:0;cursor:pointer;border:0;padding:0}
  .th-pick.on{border-style:solid;outline:3px solid var(--ink);outline-offset:1px}
  .th-auto{font-family:'Press Start 2P',monospace;font-size:7px;height:36px;padding:0 10px;border:3px solid var(--line);background:var(--bg);color:var(--ink);cursor:pointer;box-shadow:0 3px 0 #000}
  .th-auto.on{outline:3px solid var(--ink);outline-offset:1px}
  .th-foot{display:flex;flex-direction:column;gap:14px;align-items:center;margin-top:22px}
  .th-foot label{font-size:13px;color:var(--muted);display:flex;gap:8px;align-items:center;cursor:pointer}
  .th-foot input{width:18px;height:18px;accent-color:var(--accent)}
  .th-act{font-family:'Press Start 2P',monospace;font-size:9px;padding:12px 14px;border:none;cursor:pointer;background:var(--key);color:var(--ink);box-shadow:0 4px 0 #000}
  .th-act:active{transform:translateY(3px);box-shadow:0 1px 0 #000}
  .th-act.go{background:var(--accent);color:var(--bg);box-shadow:0 4px 0 var(--accent-deep)}`;

  function build(){
    const st = document.createElement('style'); st.textContent = css; document.head.appendChild(st);
    const btn = document.createElement('button'); btn.className = 'th-btn'; btn.type = 'button'; btn.title = 'Colours'; btn.setAttribute('aria-label', 'Change colours'); btn.textContent = '🎨';
    const mount = document.querySelector(cfg.mount || 'header'); if (mount) mount.appendChild(btn);
    const modal = document.createElement('div'); modal.className = 'th-modal'; modal.innerHTML = '<div class="th-sheet" role="dialog" aria-label="Colours"></div>';
    document.body.appendChild(modal);
    const sheet = modal.firstChild;
    const same = (a, b) => String(a).toLowerCase() === String(b).toLowerCase();
    const picker = (field, value, on) => `<label class="th-pick ${on ? 'on' : ''}" title="Pick any colour" style="${on ? `background:${value}` : ''}">${on ? '' : '＋'}<input type="color" data-f="${field}" value="${isHex(value) ? value : '#888888'}" aria-label="Custom colour"></label>`;
    const swatchRow = field => {
      const v = theme[field];
      return SWATCHES.map(([n, c]) => `<button class="th-sw ${same(v, c) ? 'on' : ''}" style="background:${c}" data-f="${field}" data-c="${c}" title="${n}" aria-label="${n}"></button>`).join('')
        + picker(field, v, !SWATCHES.some(([, c]) => same(c, v)));
    };
    function render(){
      const P = palette();
      sheet.innerHTML = `<h3>🎨 COLOURS</h3><div class="th-sub">Every colour is yours to pick. Tap ＋ for any colour at all.</div>
        <div class="th-l">QUICK THEMES</div>
        <div class="th-row">${Object.entries(THEMES).map(([n, t]) => { const tt = t || defaults, bg = BACKGROUNDS[tt.bg] ? BACKGROUNDS[tt.bg][0] : tt.bg;
          return `<button class="th-tile" style="background:${bg}" data-theme="${n}" aria-label="${n} theme"><b style="left:8px;background:${tt.accent}"></b><b style="left:22px;background:${tt.second}"></b><b style="left:36px;background:${tt.third}"></b><span>${n.toUpperCase()}</span></button>`; }).join('')}</div>
        <div class="th-l">MAIN COLOUR</div><div class="th-row">${swatchRow('accent')}</div>
        <div class="th-l">SECOND COLOUR</div><div class="th-row">${swatchRow('second')}</div>
        ${THI ? `<div class="th-l">THIRD COLOUR</div><div class="th-row">${swatchRow('third')}</div>` : ''}
        <div class="th-l">BACKGROUND</div><div class="th-row">${Object.entries(BACKGROUNDS).map(([n, [bg, panel]]) => `<button class="th-tile ${theme.bg === n ? 'on' : ''}" style="background:${bg}" data-bg="${n}" aria-label="${n}"><i style="background:${panel};border:2px solid ${theme.accent}"></i><span>${n.toUpperCase()}</span></button>`).join('')}${picker('bg', theme.bg, !BACKGROUNDS[theme.bg])}</div>
        <div class="th-l">CARDS &amp; PANELS</div><div class="th-row"><button class="th-auto ${theme.card === 'auto' ? 'on' : ''}" data-card="auto">AUTO</button>${picker('card', isHex(theme.card) ? theme.card : P.panel, isHex(theme.card))}</div>
        <div class="th-l">TEXT</div><div class="th-row">${TEXTS.map(([n, c]) => `<button class="th-sw ${same(theme.ink, c) ? 'on' : ''}" style="background:${c}" data-f="ink" data-c="${c}" title="${n}" aria-label="${n} text"></button>`).join('')}${picker('ink', theme.ink, !TEXTS.some(([, c]) => same(c, theme.ink)))}</div>
        ${cfg.lcd !== undefined ? `<div class="th-l">CALCULATOR SCREEN</div><div class="th-row">${Object.entries(LCDS).map(([n, c]) => `<button class="th-tile ${theme.lcd === n ? 'on' : ''}" style="background:${c}" data-lcd="${n}" aria-label="${n} screen"><i style="color:${lum(c) > 0.18 ? mix(c, '#000000', 0.82) : mix(c, '#ffffff', 0.75)}">123</i><span>${n.toUpperCase()}</span></button>`).join('')}${picker('lcd', P.lcd, !LCDS[theme.lcd])}</div>` : ''}
        <div class="th-foot">
          <label><input type="checkbox" id="th-all" ${read(GLOBAL) ? 'checked' : ''}> Use these colours on every 128bit app</label>
          <div class="th-row"><button class="th-act" data-random>🎲 SHUFFLE</button><button class="th-act" data-reset>RESET</button><button class="th-act go" data-done>DONE</button></div>
        </div>`;
      sheet.querySelectorAll('.th-sw').forEach(b => b.onclick = () => update({ [b.dataset.f]: b.dataset.c }));
      sheet.querySelectorAll('.th-pick input').forEach(i => { i.oninput = () => update({ [i.dataset.f]: i.value }, true); i.onchange = () => render(); });
      sheet.querySelectorAll('[data-bg]').forEach(b => b.onclick = () => update({ bg: b.dataset.bg }));
      sheet.querySelectorAll('[data-lcd]').forEach(b => b.onclick = () => update({ lcd: b.dataset.lcd }));
      sheet.querySelectorAll('[data-card]').forEach(b => b.onclick = () => update({ card: 'auto' }));
      sheet.querySelectorAll('[data-theme]').forEach(b => b.onclick = () => { const t = THEMES[b.dataset.theme]; update(t ? { ...t } : { ...defaults }); });
      sheet.querySelector('#th-all').onchange = e => write(GLOBAL, e.target.checked ? shared() : null);
      sheet.querySelector('[data-random]').onclick = () => {
        const pick = a => a[Math.floor(Math.random() * a.length)], cols = SWATCHES.slice(0, 10).map(s => s[1]).sort(() => Math.random() - 0.5);
        update({ accent: cols[0], second: cols[1], third: cols[2], bg: pick(Object.keys(BACKGROUNDS).filter(n => n !== 'Paper')), card: 'auto', ink: defaults.ink, lcd: pick(Object.keys(LCDS)) });
      };
      sheet.querySelector('[data-reset]').onclick = () => { theme = { ...defaults }; write(KEY, null); if (read(GLOBAL)) write(GLOBAL, null); apply(); render(); };
      sheet.querySelector('[data-done]').onclick = () => modal.classList.remove('open');
    }
    const shared = () => Object.fromEntries(SHARED.map(k => [k, theme[k]]));
    function update(patch, live){
      theme = { ...theme, ...patch }; write(KEY, theme);
      if (read(GLOBAL)) write(GLOBAL, shared());
      apply(); if (!live) render();
    }
    btn.onclick = () => { render(); modal.classList.add('open'); };
    modal.onclick = e => { if (e.target === modal) modal.classList.remove('open'); };
  }
  document.readyState === 'loading' ? document.addEventListener('DOMContentLoaded', build) : build();
  // Another open 128bit app changed the shared colours: follow along.
  window.addEventListener('storage', e => { if (e.key === KEY || e.key === GLOBAL){ theme = load(); apply(); } });
})();
