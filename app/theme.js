// 128bit family colour themes. Shared file: keep the copies in every app's app/ folder identical.
// Usage: <script src="theme.js" data-app="calc" data-accent="orange" data-second="teal" data-mount="header .tools"></script>
// It maps the picked colours onto the app's own CSS variables (--orange, --orange-deep…), plus
// generic --accent / --accent-deep / --second, and fires a 'themechange' event for canvases.
(function(){
  const me = document.currentScript, cfg = me.dataset;
  const APP = cfg.app, ACC = cfg.accent, SEC = cfg.second;
  const root = document.documentElement;
  const KEY = '128bit.theme.' + APP, GLOBAL = '128bit.theme';

  const ACCENTS = [
    ['Orange', '#ff9f1c'], ['Gold', '#facc15'], ['Green', '#4ade80'], ['Teal', '#2dd4bf'], ['Sky', '#38bdf8'],
    ['Blue', '#6b8cff'], ['Purple', '#a78bfa'], ['Pink', '#ff5d8f'], ['Red', '#ff5d5d'], ['Ghost', '#e5e7eb']
  ];
  const BACKGROUNDS = {
    Midnight: ['#0b0b16', '#141428', '#2a2a4a', '#1f1f3a'],
    Space:    ['#04050a', '#0c1120', '#1d2944', '#141b30'],
    Forest:   ['#06120b', '#0e2116', '#1f3d29', '#15301f'],
    Ocean:    ['#04101a', '#0a1d2e', '#173a55', '#102a40'],
    Grape:    ['#100717', '#1d0f2b', '#3a2152', '#2a173d'],
    Wine:     ['#14070c', '#24101a', '#47203a', '#341629'],
    Cocoa:    ['#120c07', '#22180f', '#43311f', '#33251a'],
    Slate:    ['#0e1014', '#1a1d24', '#2f3440', '#242832'],
    Black:    ['#000000', '#0d0d0d', '#262626', '#1a1a1a']
  };
  const LCDS = { Classic: ['#b8c99a', '#1e2a12', '#95a67a'], Ice: ['#a9d4e6', '#0c2a3a', '#86b4c8'], Amber: ['#f1c27d', '#3a2306', '#c99a55'],
                 Paper: ['#e8e6dc', '#1b1b1b', '#bdbab0'], Grape: ['#c7b5e8', '#26124a', '#a28fc6'], Night: ['#16241a', '#4ade80', '#0b140e'] };

  const defaults = { accent: getComputedStyle(root).getPropertyValue('--' + ACC).trim(), second: getComputedStyle(root).getPropertyValue('--' + SEC).trim(), bg: 'Midnight', lcd: 'Classic' };
  const read = k => { try { return JSON.parse(localStorage.getItem(k)); } catch { return null; } };
  const write = (k, v) => { try { v === null ? localStorage.removeItem(k) : localStorage.setItem(k, JSON.stringify(v)); } catch {} };
  let theme = { ...defaults, ...(read(KEY) || {}), ...(read(GLOBAL) || {}) };

  function shade(hex, amt){ // amt < 0 darkens towards black, > 0 lightens towards white
    const n = parseInt(hex.slice(1), 16), t = amt < 0 ? 0 : 255, p = Math.abs(amt);
    const ch = s => Math.round(((n >> s) & 255) + (t - ((n >> s) & 255)) * p);
    return '#' + [16, 8, 0].map(s => ch(s).toString(16).padStart(2, '0')).join('');
  }
  function apply(){
    const set = (k, v) => root.style.setProperty(k, v);
    const deepA = shade(theme.accent, -0.45), deepS = shade(theme.second, -0.45);
    set('--' + ACC, theme.accent); set('--' + ACC + '-deep', deepA); set('--accent', theme.accent); set('--accent-deep', deepA);
    set('--' + SEC, theme.second); set('--' + SEC + '-deep', deepS); set('--second', theme.second); set('--second-deep', deepS);
    const [bg, panel, line, key] = BACKGROUNDS[theme.bg] || BACKGROUNDS.Midnight;
    set('--bg', bg); set('--panel', panel); set('--line', line); set('--key', key);
    const [lcd, ink, edge] = LCDS[theme.lcd] || LCDS.Classic;
    set('--lcd', lcd); set('--lcd-ink', ink); set('--lcd-edge', edge);
    const meta = document.querySelector('meta[name=theme-color]'); if (meta) meta.content = bg;
    window.dispatchEvent(new CustomEvent('themechange', { detail: theme }));
  }
  apply();

  /* ---------- picker UI ---------- */
  const css = `
  .th-btn{font-family:'Press Start 2P',monospace;font-size:12px;background:var(--key);color:var(--ink);border:none;padding:7px 9px;box-shadow:0 3px 0 #000;cursor:pointer;line-height:1}
  .th-btn:active{transform:translateY(2px);box-shadow:0 1px 0 #000}
  .th-modal{position:fixed;inset:0;background:rgba(0,0,0,.8);display:none;align-items:flex-end;justify-content:center;z-index:60}
  .th-modal.open{display:flex}
  .th-sheet{background:var(--panel);border:3px solid var(--line);border-bottom:none;width:100%;max-width:560px;padding:18px 16px 24px;max-height:92dvh;overflow-y:auto;color:var(--ink);font-family:'Inter',system-ui,sans-serif}
  .th-sheet h3{font-family:'Press Start 2P',monospace;font-size:11px;color:var(--accent);margin-bottom:4px;line-height:1.6}
  .th-sheet .th-l{font-family:'Press Start 2P',monospace;font-size:8px;color:var(--muted);margin:16px 0 8px}
  .th-row{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
  .th-sw{width:36px;height:36px;border:3px solid var(--bg);cursor:pointer;box-shadow:0 3px 0 #000;padding:0}
  .th-sw.on{outline:3px solid var(--ink);outline-offset:1px}
  .th-bg{width:74px;height:48px;border:3px solid var(--bg);cursor:pointer;position:relative;padding:0;box-shadow:0 3px 0 #000}
  .th-bg span{position:absolute;left:0;right:0;bottom:0;font-family:'Press Start 2P',monospace;font-size:6px;color:#fff;padding:3px;background:rgba(0,0,0,.45)}
  .th-bg i{position:absolute;left:8px;right:8px;top:7px;height:12px}
  .th-bg.on{outline:3px solid var(--ink);outline-offset:1px}
  .th-pick{width:44px;height:36px;border:3px solid var(--line);background:var(--bg);padding:2px;cursor:pointer}
  .th-foot{display:flex;gap:10px;justify-content:space-between;align-items:center;margin-top:20px;flex-wrap:wrap}
  .th-foot label{font-size:13px;color:var(--muted);display:flex;gap:8px;align-items:center;cursor:pointer}
  .th-foot input{width:18px;height:18px;accent-color:var(--accent)}
  .th-act{font-family:'Press Start 2P',monospace;font-size:9px;padding:12px 14px;border:none;cursor:pointer;background:var(--key);color:var(--ink);box-shadow:0 4px 0 #000}
  .th-act.go{background:var(--accent);color:var(--bg);box-shadow:0 4px 0 var(--accent-deep)}`;
  function build(){
    const st = document.createElement('style'); st.textContent = css; document.head.appendChild(st);
    const btn = document.createElement('button'); btn.className = 'th-btn'; btn.type = 'button'; btn.title = 'Colours'; btn.setAttribute('aria-label', 'Change colours'); btn.textContent = '🎨';
    const mount = document.querySelector(cfg.mount || 'header'); if (mount) mount.appendChild(btn);
    const modal = document.createElement('div'); modal.className = 'th-modal'; modal.innerHTML = '<div class="th-sheet"></div>';
    document.body.appendChild(modal);
    const sheet = modal.firstChild;
    const swatches = (field) => ACCENTS.map(([n, c]) => `<button class="th-sw ${theme[field].toLowerCase() === c ? 'on' : ''}" style="background:${c}" data-f="${field}" data-c="${c}" title="${n}" aria-label="${n}"></button>`).join('')
      + `<input type="color" class="th-pick" data-f="${field}" value="${theme[field]}" aria-label="Custom colour">`;
    function render(){
      sheet.innerHTML = `<h3>🎨 COLOURS</h3>
        <div class="th-l">MAIN COLOUR</div><div class="th-row">${swatches('accent')}</div>
        <div class="th-l">SECOND COLOUR</div><div class="th-row">${swatches('second')}</div>
        <div class="th-l">BACKGROUND</div><div class="th-row">${Object.entries(BACKGROUNDS).map(([n, [bg, panel]]) => `<button class="th-bg ${theme.bg === n ? 'on' : ''}" style="background:${bg}" data-bg="${n}" aria-label="${n}"><i style="background:${panel};border:2px solid ${theme.accent}"></i><span>${n.toUpperCase()}</span></button>`).join('')}</div>
        ${cfg.lcd !== undefined ? `<div class="th-l">SCREEN</div><div class="th-row">${Object.entries(LCDS).map(([n, [c, ink]]) => `<button class="th-bg ${theme.lcd === n ? 'on' : ''}" style="background:${c}" data-lcd="${n}" aria-label="${n}"><i style="background:none;color:${ink};font-family:'Press Start 2P',monospace;font-size:9px;top:10px">123</i><span>${n.toUpperCase()}</span></button>`).join('')}</div>` : ''}
        <div class="th-foot">
          <label><input type="checkbox" id="th-all" ${read(GLOBAL) ? 'checked' : ''}> Use on every 128bit app</label>
          <div class="th-row"><button class="th-act" data-reset>RESET</button><button class="th-act go" data-done>DONE</button></div>
        </div>`;
      sheet.querySelectorAll('.th-sw').forEach(b => b.onclick = () => update({ [b.dataset.f]: b.dataset.c }));
      sheet.querySelectorAll('.th-pick').forEach(i => i.oninput = () => update({ [i.dataset.f]: i.value }, true));
      sheet.querySelectorAll('[data-bg]').forEach(b => b.onclick = () => update({ bg: b.dataset.bg }));
      sheet.querySelectorAll('[data-lcd]').forEach(b => b.onclick = () => update({ lcd: b.dataset.lcd }));
      sheet.querySelector('#th-all').onchange = e => { write(GLOBAL, e.target.checked ? { bg: theme.bg, accent: theme.accent, second: theme.second } : null); };
      sheet.querySelector('[data-reset]').onclick = () => { theme = { ...defaults }; write(KEY, null); if (read(GLOBAL)) write(GLOBAL, null); apply(); render(); };
      sheet.querySelector('[data-done]').onclick = () => modal.classList.remove('open');
    }
    function update(patch, keepOpen){
      theme = { ...theme, ...patch }; write(KEY, theme);
      if (read(GLOBAL)) write(GLOBAL, { bg: theme.bg, accent: theme.accent, second: theme.second });
      apply(); if (!keepOpen) render();
    }
    btn.onclick = () => { render(); modal.classList.add('open'); };
    modal.onclick = e => { if (e.target === modal) modal.classList.remove('open'); };
  }
  document.readyState === 'loading' ? document.addEventListener('DOMContentLoaded', build) : build();
  // Another open 128bit app changed the shared theme: follow along.
  window.addEventListener('storage', e => { if (e.key === KEY || e.key === GLOBAL){ theme = { ...defaults, ...(read(KEY) || {}), ...(read(GLOBAL) || {}) }; apply(); } });
})();
