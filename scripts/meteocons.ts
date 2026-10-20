// Regenerates app/src/main/res/drawable/wx_*.xml: Meteocons (Bas Milius, MIT, fill style) as
// AnimatedVectorDrawables, with their SMIL animations as animators.
//
//   bun scripts/meteocons.ts
//
// Downloads the 14 icons Couch maps weather codes to (see data/Condition.kt), the same files Labby
// loads, and writes one drawable per icon. Supports what those files use: path, circle, g,
// linearGradient (with xlink:href), and animate/animateTransform (rotate, translate, opacity, negative
// begin). Gradient rotations can't be animated in a vector drawable and are left still.
const ICONS = ["clear-day", "clear-night", "partly-cloudy-day", "partly-cloudy-night", "cloudy", "overcast", "mist", "drizzle",
  "partly-cloudy-day-rain", "partly-cloudy-night-rain", "partly-cloudy-day-snow", "partly-cloudy-night-snow",
  "thunderstorms-day-rain", "thunderstorms-night-rain"];
const SOURCE = "https://cdn.jsdelivr.net/gh/basmilius/weather-icons/production/fill/all";
const OUT = new URL("../app/src/main/res/drawable", import.meta.url).pathname;
type N = { tag: string; a: Record<string, string>; kids: N[] };
function parse(xml: string): N {
  const root: N = { tag: "#root", a: {}, kids: [] }; const stack = [root];
  for (const m of xml.matchAll(/<(\/?)([a-zA-Z:]+)([^>]*?)(\/?)>/g)) {
    const [, close, tag, rest, self] = m;
    if (tag.startsWith("?") || tag.startsWith("!")) continue;
    if (close) { stack.pop(); continue; }
    const n: N = { tag, a: Object.fromEntries([...rest.matchAll(/([a-zA-Z0-9:-]+)="([^"]*)"/g)].map((x) => [x[1], x[2]])), kids: [] };
    stack[stack.length - 1].kids.push(n);
    if (!self) stack.push(n);
  }
  return root;
}
const hex = (c: string) => { c = c.replace("#", ""); if (c.length === 3) c = [...c].map((x) => x + x).join(""); return "#FF" + c.toUpperCase(); };
function normalize(d: string): string {
  const out: string[] = []; let i = 0, cmd = "";
  const num = () => { const m = d.slice(i).match(/^[\s,]*([-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?)/); if (!m) return null; i += m[0].length; return m[1]; };
  const flag = () => { const m = d.slice(i).match(/^[\s,]*([01])/); if (!m) return null; i += m[0].length; return m[1]; };
  const counts: Record<string, number> = { m: 2, l: 2, h: 1, v: 1, c: 6, s: 4, q: 4, t: 2, a: 7, z: 0 };
  while (i < d.length) {
    const ws = d.slice(i).match(/^[\s,]+/); if (ws) { i += ws[0].length; continue; }
    if (/[a-zA-Z]/.test(d[i])) { cmd = d[i++]; out.push(cmd); if (cmd.toLowerCase() === "z") continue; }
    const n = counts[cmd.toLowerCase()]; const args: string[] = [];
    for (let k = 0; k < n; k++) { const v = cmd.toLowerCase() === "a" && (k === 3 || k === 4) ? flag() : num(); if (v === null) throw new Error("bad path"); args.push(v); }
    out.push(args.join(" "));
  }
  return out.join(" ").replace(/\s+/g, " ").trim();
}
const secs = (s?: string) => (s ? parseFloat(s) * (s.endsWith("ms") ? 0.001 : 1) : 0);

/** Keyframes of a looping, evenly spaced, linear animation, phase-shifted by a negative begin. */
function keyframes(values: number[][], dur: number, begin: number) {
  const n = values.length;
  const f = (t: number) => { const x = t * (n - 1); const i = Math.min(n - 2, Math.floor(x)); const u = x - i; return values[i].map((v, k) => v + (values[i + 1][k] - v) * u); };
  const p = begin < 0 ? ((-begin % dur) / dur) : 0;
  if (p === 0) return values.map((v, i) => ({ t: i / (n - 1), v }));
  const wrap = 1 - p, eps = 0.0005;
  const times = new Set<number>([0, 1]);
  values.forEach((_, i) => { const t = (i / (n - 1) - p + 1) % 1; if (t > eps && t < 1 - eps) times.add(+t.toFixed(4)); });
  const out: { t: number; v: number[] }[] = [];
  for (const t of [...times].sort((a, b) => a - b)) {
    if (Math.abs(t - wrap) < eps) continue;
    out.push({ t, v: f((t + p) % 1 || (t === 1 ? p : 0)) });
  }
  // The loop's end lands mid-cycle: hold the last value up to the wrap, then restart.
  out.push({ t: wrap, v: values[n - 1] }, { t: Math.min(1, wrap + eps), v: values[0] });
  return out.sort((a, b) => a.t - b.t).map((k) => ({ t: +k.t.toFixed(4), v: k.v }));
}

for (const icon of ICONS) {
  const svg = parse(await (await fetch(`${SOURCE}/${icon}.svg`)).text()).kids[0];
  const grads: Record<string, any> = {};
  const walkG = (n: N) => { for (const k of n.kids) { if (k.tag === "linearGradient") grads[k.a.id] = { ...k.a, stops: k.kids.filter((s) => s.tag === "stop").map((s) => s.a) }; walkG(k); } };
  walkG(svg);
  for (const g of Object.values<any>(grads)) { const b = g["xlink:href"] && grads[g["xlink:href"].slice(1)]; if (!b) continue; if (!g.stops.length) g.stops = b.stops; for (const k of ["x1", "x2", "y1", "y2"]) g[k] ??= b[k]; }
  const paint = (prop: string, v?: string) => {
    if (!v || v === "none") return { attr: "", child: "" };
    const ref = v.match(/^url\(#(.+)\)$/);
    if (!ref) return { attr: ` android:${prop}="${hex(v)}"`, child: "" };
    const g = grads[ref[1]]; if (!g) return { attr: "", child: "" };
    const items = g.stops.map((s: any) => `<item android:offset="${+s.offset || 0}" android:color="${hex(s["stop-color"])}"/>`).join("");
    return { attr: "", child: `<aapt:attr name="android:${prop}"><gradient android:type="linear" android:startX="${g.x1}" android:startY="${g.y1}" android:endX="${g.x2}" android:endY="${g.y2}">${items}</gradient></aapt:attr>` };
  };
  let id = 0;
  const targets: string[] = [];
  const anim = (name: string, prop: string, kf: { t: number; v: number }[], dur: number) =>
    targets.push(`<target android:name="${name}"><aapt:attr name="android:animation"><objectAnimator android:duration="${Math.round(dur * 1000)}" android:repeatCount="-1" android:interpolator="@android:anim/linear_interpolator"><propertyValuesHolder android:propertyName="${prop}" android:valueType="floatType">${kf.map((k) => `<keyframe android:fraction="${k.t}" android:value="${+k.v.toFixed(3)}"/>`).join("")}</propertyValuesHolder></objectAnimator></aapt:attr></target>`);
  type Out = { xml: string; paths: { name: string; fill: boolean; stroke: boolean }[] };
  /** Any element: its own animations wrap what it draws (first listed transform outermost); opacity animates every path inside. */
  const emit = (n: N): Out => {
    let out: Out;
    if (n.tag === "g") {
      const parts = n.kids.map(emit);
      out = { xml: parts.map((p) => p.xml).join(""), paths: parts.flatMap((p) => p.paths) };
    } else if (n.tag === "path" || n.tag === "circle") {
      const d = n.tag === "circle" ? `M ${+n.a.cx - +n.a.r} ${n.a.cy} A ${n.a.r} ${n.a.r} 0 1 0 ${+n.a.cx + +n.a.r} ${n.a.cy} A ${n.a.r} ${n.a.r} 0 1 0 ${+n.a.cx - +n.a.r} ${n.a.cy} Z` : normalize(n.a.d);
      const fill = paint("fillColor", n.a.fill ?? "#000"), stroke = paint("strokeColor", n.a.stroke);
      const pname = `p${id++}`;
      let p = `<path android:name="${pname}" android:pathData="${d}"${fill.attr}${stroke.attr}`;
      if (n.a.stroke && n.a.stroke !== "none") {
        p += ` android:strokeWidth="${n.a["stroke-width"] ?? 1}"`;
        if (n.a["stroke-linecap"]) p += ` android:strokeLineCap="${n.a["stroke-linecap"]}"`;
        if (n.a["stroke-linejoin"]) p += ` android:strokeLineJoin="${n.a["stroke-linejoin"]}"`;
        if (n.a["stroke-miterlimit"]) p += ` android:strokeMiterLimit="${n.a["stroke-miterlimit"]}"`;
      }
      p += fill.child || stroke.child ? `>${fill.child}${stroke.child}</path>` : "/>";
      out = { xml: p, paths: [{ name: pname, fill: !!(fill.attr || fill.child), stroke: !!(stroke.attr || stroke.child) }] };
    } else return { xml: "", paths: [] };
    const anims = n.kids.filter((k) => k.tag === "animate" || k.tag === "animateTransform");
    for (const a of anims.filter((a) => a.tag === "animate" && a.a.attributeName === "opacity")) {
      const kf = keyframes(a.a.values.split(";").map((v) => [+v.trim()]), secs(a.a.dur), secs(a.a.begin)).map((k) => ({ t: k.t, v: k.v[0] }));
      for (const p of out.paths) {
        if (p.fill) anim(p.name, "fillAlpha", kf, secs(a.a.dur));
        if (p.stroke) anim(p.name, "strokeAlpha", kf, secs(a.a.dur));
      }
    }
    const transforms = anims.filter((a) => a.tag === "animateTransform" && a.a.attributeName === "transform");
    for (const a of [...transforms].reverse()) {
      const g = `g${id++}`;
      const vals = a.a.values.split(";").map((v) => v.trim().split(/[\s,]+/).map(Number));
      const kf = keyframes(vals, secs(a.a.dur), secs(a.a.begin));
      if (a.a.type === "rotate") {
        const [, cx, cy] = vals[0];
        out.xml = `<group android:name="${g}" android:pivotX="${cx}" android:pivotY="${cy}">${out.xml}</group>`;
        // The sun's rays turn once in 45 s upstream, too slow to notice on a TV: three times faster here.
        const dur = secs(a.a.dur) === 45 ? 15 : secs(a.a.dur);
        anim(g, "rotation", kf.map((k) => ({ t: k.t, v: k.v[0] })), dur);
      } else if (a.a.type === "translate") {
        out.xml = `<group android:name="${g}">${out.xml}</group>`;
        anim(g, "translateX", kf.map((k) => ({ t: k.t, v: k.v[0] })), secs(a.a.dur));
        anim(g, "translateY", kf.map((k) => ({ t: k.t, v: k.v[1] ?? 0 })), secs(a.a.dur));
      }
    }
    return out;
  };
  const body = svg.kids.map((k) => emit(k).xml).join("\n");
  const name = "wx_" + icon.replaceAll("-", "_");
  const xml = `<?xml version="1.0" encoding="utf-8"?>
<!-- Meteocons by Bas Milius (MIT), fill style, with their animations. Generated by scripts/meteocons.ts: edit that, not this file. -->
<animated-vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt">
<aapt:attr name="android:drawable">
<vector android:width="64dp" android:height="64dp" android:viewportWidth="64" android:viewportHeight="64">
${body}
</vector>
</aapt:attr>
${targets.join("\n")}
</animated-vector>
`;
  await Bun.write(`${OUT}/${name}.xml`, xml);
  console.log(name, "targets", targets.length);
}
