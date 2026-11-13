// Monta las láminas de Play Store: teléfono 1080x1920, tablet 2560x1440 y gráfico 1024x500.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const dir = __dirname;
const img = (f) => 'file://' + path.join(dir, 'img', f);
const out = process.argv[2];
const P = 'M21,5c-1.11,-0.35 -2.33,-0.5 -3.5,-0.5 -1.95,0 -4.05,0.4 -5.5,1.5 -1.45,-1.1 -3.55,-1.5 -5.5,-1.5S2.45,4.9 1,6v14.65c0,0.25 0.25,0.5 0.5,0.5 0.1,0 0.15,-0.05 0.25,-0.05C3.1,20.45 5.05,20 6.5,20c1.95,0 4.05,0.4 5.5,1.5 1.35,-0.85 3.8,-1.5 5.5,-1.5 1.65,0 3.35,0.3 4.75,1.05 0.1,0.05 0.15,0.05 0.25,0.05 0.25,0 0.5,-0.25 0.5,-0.5V6c-0.6,-0.45 -1.25,-0.75 -2,-1zM21,18.5c-1.1,-0.35 -2.3,-0.5 -3.5,-0.5 -1.7,0 -4.15,0.65 -5.5,1.5V8c1.35,-0.85 3.8,-1.5 5.5,-1.5 1.2,0 2.4,0.15 3.5,0.5v11.5z';

const base = `*{margin:0;padding:0;box-sizing:border-box} body{font-family:'Inter Display','Inter',sans-serif;-webkit-font-smoothing:antialiased}`;

function phone({ shot, bg, dark, title, sub }) {
  const fg = dark ? '#ffffff' : '#1c1c1e', sfg = dark ? 'rgba(235,235,245,.72)' : 'rgba(60,60,67,.78)';
  const h = 1390, w = Math.round(h * 1080 / 2520);
  return `<style>${base}
  .c{width:1080px;height:1920px;position:relative;overflow:hidden;background:url('${img(bg)}') center/cover}
  .t{position:absolute;top:118px;left:70px;right:70px;text-align:center;color:${fg};font-size:78px;font-weight:700;letter-spacing:-1.6px;line-height:1.06}
  .s{position:absolute;top:${118 + 175}px;left:90px;right:90px;text-align:center;color:${sfg};font-size:36px;font-weight:500;line-height:1.3}
  .acc{color:#FF9F0A}
  .d{position:absolute;left:${(1080 - w) / 2 - 14}px;top:452px;padding:14px;background:#0c0c0d;border-radius:72px;
     box-shadow:0 0 0 2px #2a2a2c,0 50px 90px rgba(0,0,0,${dark ? .65 : .35}),0 12px 30px rgba(0,0,0,.3)}
  .d img{display:block;width:${w}px;height:${h}px;border-radius:58px;object-fit:cover}
  </style><div class="c"><div class="t">${title}</div><div class="s">${sub}</div>
  <div class="d"><img src="${img(shot)}"></div></div>`;
}

function tablet({ shot, title, sub }) {
  const h = 1180, w = Math.round(h * 2256 / 2504);
  return `<style>${base}
  .c{width:2560px;height:1440px;position:relative;overflow:hidden;background:url('${img('bg_wide.png')}') center/cover}
  .txt{position:absolute;left:170px;top:0;bottom:0;width:980px;display:flex;flex-direction:column;justify-content:center}
  .logo{display:flex;align-items:center;gap:20px;color:#FF9F0A;font-size:40px;font-weight:600;margin-bottom:44px}
  .t{color:#fff;font-size:118px;font-weight:700;letter-spacing:-2.6px;line-height:1.02}
  .s{color:rgba(235,235,245,.72);font-size:46px;font-weight:500;line-height:1.3;margin-top:36px}
  .d{position:absolute;right:190px;top:${(1440 - h) / 2 - 18}px;padding:18px;background:#0c0c0d;border-radius:64px;
     box-shadow:0 0 0 2px #2a2a2c,0 60px 120px rgba(0,0,0,.7)}
  .d img{display:block;width:${w}px;height:${h}px;border-radius:48px;object-fit:cover}
  .hinge{position:absolute;top:18px;bottom:18px;left:50%;width:3px;transform:translateX(-50%);
     background:linear-gradient(to right,rgba(0,0,0,.10),rgba(255,255,255,.06),rgba(0,0,0,.10))}
  </style><div class="c"><div class="txt">
  <div class="logo"><svg width="56" height="56" viewBox="0 0 24 24"><path fill="#FF9F0A" d="${P}"/></svg>EpubFold</div>
  <div class="t">${title}</div><div class="s">${sub}</div></div>
  <div class="d"><img src="${img(shot)}"><div class="hinge"></div></div></div>`;
}

function feature() {
  return `<style>${base}
  .c{width:1024px;height:500px;position:relative;overflow:hidden;background:#141210}
  .bg{position:absolute;left:-40px;top:-6px;width:1100px;height:550px;background:url('${img('book.png')}') center/cover}
  .fade{position:absolute;inset:0;background:linear-gradient(90deg,rgba(15,13,12,.92) 0%,rgba(15,13,12,.75) 34%,rgba(15,13,12,0) 58%)}
  .txt{position:absolute;left:68px;top:0;bottom:0;display:flex;flex-direction:column;justify-content:center;color:#fff}
  .n{font-size:76px;font-weight:700;letter-spacing:-2px;margin-top:14px}
  .s{font-size:28px;font-weight:500;color:rgba(235,235,245,.8);margin-top:6px;line-height:1.3}
  </style><div class="c"><div class="bg"></div><div class="fade"></div><div class="txt">
  <svg width="74" height="74" viewBox="0 0 24 24"><path fill="#FF9F0A" d="${P}"/></svg>
  <div class="n">EpubFold</div><div class="s">Libros y cómics que pasan<br>página como en papel</div></div></div>`;
}

const jobs = [
  ['telefono-1.png', 1080, 1920, phone({ shot: 'p_curl.png', bg: 'bg_light.png', dark: false, title: 'Pasa página<br>como en <span class="acc">papel</span>', sub: 'Una animación realista que sigue tu dedo' })],
  ['telefono-2.png', 1080, 1920, phone({ shot: 'p_highlight.png', bg: 'bg_dark.png', dark: true, title: 'Subraya, traduce<br>y <span class="acc">escucha</span>', sub: 'Cinco colores, notas y lectura en voz alta' })],
  ['telefono-3.png', 1080, 1920, phone({ shot: 'p_stats.png', bg: 'bg_light.png', dark: false, title: 'Crea el hábito<br>de <span class="acc">leer</span>', sub: 'Metas diarias, rachas y tu reto del año' })],
  ['telefono-4.png', 1080, 1920, phone({ shot: 'p_read.png', bg: 'bg_dark.png', dark: true, title: 'Lectura cómoda,<br><span class="acc">sin distracciones</span>', sub: 'Tipografía cuidada y temas para cada momento' })],
  ['tablet-1.png', 2560, 1440, tablet({ shot: 't_curl.png', title: 'Un libro abierto<br>en tus manos', sub: 'Doble página en tu plegable, con el giro de un libro de verdad' })],
  ['tablet-5.png', 2560, 1440, tablet({ shot: 't_library.png', title: 'Tu biblioteca,<br>a tu manera', sub: 'Estanterías, búsqueda y portadas para cada libro' })],
  ['tablet-2.png', 2560, 1440, tablet({ shot: 't_notes.png', title: 'Diseñada para<br>plegables', sub: 'Índice, notas, cuaderno y traducción en un panel lateral' })],
  ['tablet-3.png', 2560, 1440, tablet({ shot: 't_themes.png', title: 'Nueve temas<br>de lectura', sub: 'Del papel cálido al negro OLED, con su propio brillo' })],
  ['tablet-4.png', 2560, 1440, tablet({ shot: 't_gutenberg.png', title: 'Miles de clásicos<br>gratis', sub: 'Descarga libros de Project Gutenberg y otros catálogos' })],
  ['grafico-funciones-1024x500.png', 1024, 500, feature()],
];

(async () => {
  fs.mkdirSync(out, { recursive: true });
  const b = await chromium.launch();
  for (const [name, w, h, html] of jobs) {
    const f = path.join(dir, '_' + name + '.html');
    fs.writeFileSync(f, `<!doctype html><meta charset="utf-8"><body>${html}</body>`);
    const p = await b.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1 });
    await p.goto('file://' + f);
    await p.waitForLoadState('networkidle');
    await p.screenshot({ path: path.join(out, name) });
    await p.close();
    console.log(name);
  }
  await b.close();
})();
