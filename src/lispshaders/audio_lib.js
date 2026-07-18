// The JS runtime for compiled drones. Where the GLSL target's ops run on
// the GPU, this library's ops build an Elementary Audio node graph: a
// compiled defsound body calls `add(a, b)` / `osc(f)` / `lowpass(...)`
// and gets back signal nodes, not numbers.
//
// Two rules make the DSL feel identical across targets:
// - vectors are JS arrays and every op broadcasts scalars over them,
//   exactly like GLSL — a vec2 here is a stereo [left right] pair;
// - plain numbers stay plain numbers ((* 2.0 3.0) folds to 6 while the
//   graph is built); a value only becomes a node once a signal touches it.
//
// Everything here is pure graph construction with no browser
// dependencies, so the compiler tests exercise it in Node.

export function buildAudioLib(el) {
  const isV = Array.isArray;
  const isNum = (x) => typeof x === 'number';

  // -- lifting: fold plain numbers, build nodes as soon as a signal appears
  const lift1 = (name, jsF, elF) => (x) => {
    if (isNum(x)) return jsF(x);
    if (elF) return elF(x);
    throw new Error('(' + name + ' ...) only works on plain numbers in a drone, not on signals');
  };
  const lift2 = (jsF, elF) => (a, b) => (isNum(a) && isNum(b)) ? jsF(a, b) : elF(a, b);

  // -- broadcasting over vectors (stereo pairs), GLSL-style
  const u1 = (f) => (a) => isV(a) ? a.map(f) : f(a);
  const b2 = (f) => (a, b) => {
    if (isV(a) && isV(b)) return a.map((x, i) => f(x, b[i]));
    if (isV(a)) return a.map(x => f(x, b));
    if (isV(b)) return b.map(x => f(a, x));
    return f(a, b);
  };
  const b3 = (f) => (a, b, c) => {
    const at = (v, i) => isV(v) ? v[i] : v;
    if (isV(a) || isV(b) || isV(c)) {
      const n = (isV(a) ? a : isV(b) ? b : c).length;
      return Array.from({length: n}, (_, i) => f(at(a, i), at(b, i), at(c, i)));
    }
    return f(a, b, c);
  };

  // -- scalar leaves
  const addS = lift2((x, y) => x + y, el.add);
  const subS = lift2((x, y) => x - y, el.sub);
  const mulS = lift2((x, y) => x * y, el.mul);
  const divS = lift2((x, y) => x / y, el.div);
  const powS = lift2(Math.pow, el.pow);
  const minS = lift2(Math.min, el.min);
  const maxS = lift2(Math.max, el.max);
  const sinS = lift1('sin', Math.sin, el.sin);
  const cosS = lift1('cos', Math.cos, el.cos);
  const sqrtS = lift1('sqrt', Math.sqrt, el.sqrt);
  const floorS = lift1('floor', Math.floor, el.floor);
  const fractS = (x) => subS(x, floorS(x));
  // GLSL mod (sign follows the divisor), not C fmod
  const modS = (a, b) => subS(a, mulS(b, floorS(divS(a, b))));
  const absS = lift1('abs', Math.abs, el.abs);

  // -- comparisons are gates: 1.0 when true, 0.0 when false — so a
  //    comparison of signals is itself a signal
  const ltS  = lift2((x, y) => (x <  y ? 1 : 0), el.le);
  const gtS  = lift2((x, y) => (x >  y ? 1 : 0), el.ge);
  const lteS = lift2((x, y) => (x <= y ? 1 : 0), el.leq);
  const gteS = lift2((x, y) => (x >= y ? 1 : 0), el.geq);
  const eqS  = lift2((x, y) => (x === y ? 1 : 0), el.eq);
  const andS = lift2((x, y) => (x !== 0 && y !== 0 ? 1 : 0), el.and);
  const orS  = lift2((x, y) => (x !== 0 || y !== 0 ? 1 : 0), el.or);
  const notS = (x) => isNum(x) ? (x !== 0 ? 0 : 1) : el.sub(1, x);
  const selectS = (c, a, b) => isNum(c) ? (c !== 0 ? a : b) : el.select(c, a, b);

  const clampS = (x, lo, hi) => minS(maxS(x, lo), hi);
  const mixS = (a, b, t) => addS(a, mulS(subS(b, a), t));
  const smoothS = (a, b, x) => {
    const t = clampS(divS(subS(x, a), subS(b, a)), 0, 1);
    return mulS(t, mulS(t, subS(3, mulS(2, t))));
  };

  // -- vector math, composed from the leaves so signals flow through
  const dot = (a, b) => a.map((x, i) => mulS(x, b[i])).reduce(addS);
  const length = (v) => isV(v) ? sqrtS(dot(v, v)) : absS(v);
  const signS = (x) => isNum(x) ? Math.sign(x) : el.sub(el.ge(x, 0), el.le(x, 0));

  // -- pseudo-random, for per-layer variation on iter indexes; a signal
  //    has no stable value to hash, so these insist on plain numbers
  const hashNum = (name) => (p) => {
    if (!isNum(p)) throw new Error('(' + name + ' ...) needs a plain number — hash iter indexes for per-layer randomness, not signals');
    return p;
  };
  const hash1 = (p) => {
    p = hashNum('hash1')(p) * 0.1031 - Math.floor(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return p - Math.floor(p);
  };

  const flat = (args) => args.flatMap(a => isV(a) ? a : [a]);
  const vecN = (n) => (...args) => {
    const f = flat(args);
    return (args.length === 1 && f.length === 1) ? Array(n).fill(f[0]) : f.slice(0, n);
  };

  // seconds since the audio engine started, as a signal
  const TIME = el.div(el.time(), el.sr());

  // Uploaded audio lives in the renderer's virtual file system under
  // versioned paths (re-uploading a name gets a fresh path, since VFS
  // entries are immutable). This registry routes the user-facing name to
  // the current path; unset names pass through so graph construction stays
  // pure and testable without a renderer.
  const samplePaths = {};
  const samplePath = (name) => samplePaths[name] || name;

  // (defparam ...) knobs live here as plain numbers the UI writes to. A
  // param reads as a keyed, smoothed const: re-rendering with a new value
  // ramps the const over ~30ms (the mute/solo trick), so turning a knob
  // glides the sound instead of clicking. Unknown names read 0.
  const paramValues = {};
  const param = (name) =>
    el.smooth(el.tau2pole(0.03),
              el.const({key: 'param/' + name, value: paramValues[name] ?? 0}));

  // drone builtins with a signal-last argument broadcast over stereo pairs
  const overSig = (f) => (...args) => {
    const x = args[args.length - 1];
    return isV(x) ? x.map(ch => f(...args.slice(0, -1), ch)) : f(...args);
  };

  return {
    TIME,

    // arithmetic
    add: b2(addS), sub: b2(subS), mul: b2(mulS), div: b2(divS),
    neg: u1((x) => isNum(x) ? -x : el.mul(-1, x)),

    // comparisons, logic and choice (compiled from < > = if and or not)
    lt: ltS, gt: gtS, lte: lteS, gte: gteS,
    eq: (a, b) => isV(a) ? a.map((x, i) => eqS(x, b[i])).reduce(andS) : eqS(a, b),
    neq: (a, b) => notS(isV(a) ? a.map((x, i) => eqS(x, b[i])).reduce(andS) : eqS(a, b)),
    and: (...xs) => xs.reduce(andS),
    or: (...xs) => xs.reduce(orS),
    not: notS,
    select: b3(selectS),

    // constructors, casts & swizzles
    vec2: vecN(2), vec3: vecN(3), vec4: vecN(4),
    float: (x) => isNum(x) ? +x : x,
    int: lift1('int', Math.trunc, null),
    sw: (v, s) => {
      const idx = {x: 0, y: 1, z: 2, w: 3};
      return s.length === 1 ? v[idx[s]] : [...s].map(c => v[idx[c]]);
    },

    // math, componentwise like GLSL
    sin: u1(sinS), cos: u1(cosS), tan: u1(lift1('tan', Math.tan, el.tan)),
    asin: u1(lift1('asin', Math.asin, null)),
    acos: u1(lift1('acos', Math.acos, null)),
    atan: (y, x) => x === undefined
      ? u1(lift1('atan', Math.atan, null))(y)
      : b2(lift2(Math.atan2, () => { throw new Error('(atan y x) only works on plain numbers in a drone'); }))(y, x),
    exp: u1(lift1('exp', Math.exp, el.exp)),
    log: u1(lift1('log', Math.log, el.ln)),
    exp2: u1((x) => powS(2, x)),
    log2: u1(lift1('log2', Math.log2, el.log2)),
    sqrt: u1(sqrtS), inversesqrt: u1((x) => divS(1, sqrtS(x))),
    abs: u1(absS), sign: u1(signS),
    floor: u1(floorS), ceil: u1(lift1('ceil', Math.ceil, el.ceil)), fract: u1(fractS),
    pow: b2(powS), mod: b2(modS),
    min: b2(minS), max: b2(maxS),
    clamp: b3(clampS), mix: b3(mixS),
    step: b2((e, x) => gteS(x, e)), smoothstep: b3(smoothS),

    // vector helpers (on a stereo pair these are cheap graph math too)
    length, dot,
    distance: (a, b) => length(b2(subS)(a, b)),
    normalize: (v) => {
      if (!isV(v)) return signS(v);
      const len = addS(length(v), 1e-9);
      return v.map(x => divS(x, len));
    },
    cross: (a, b) => [subS(mulS(a[1], b[2]), mulS(a[2], b[1])),
                      subS(mulS(a[2], b[0]), mulS(a[0], b[2])),
                      subS(mulS(a[0], b[1]), mulS(a[1], b[0]))],
    reflect: (i, n) => b2(subS)(i, b2(mulS)(n, mulS(2, dot(n, i)))),
    refract: (i, n, eta) => {
      const d = dot(n, i);
      const k = subS(1, mulS(mulS(eta, eta), subS(1, mulS(d, d))));
      const bent = b2(subS)(b2(mulS)(i, eta), b2(mulS)(n, addS(mulS(eta, d), sqrtS(maxS(k, 0)))));
      return bent.map(x => selectS(ltS(k, 0), 0, x));
    },

    // pseudo-random & pitch
    hash1,
    hash2: (p) => {
      hashNum('hash2')(p[0]); hashNum('hash2')(p[1]);
      const fr = (x) => x - Math.floor(x);
      let p3 = [fr(p[0] * 0.1031), fr(p[1] * 0.1031), fr(p[0] * 0.1031)];
      const d = p3[0] * (p3[1] + 33.33) + p3[1] * (p3[2] + 33.33) + p3[2] * (p3[0] + 33.33);
      p3 = p3.map(x => x + d);
      return fr((p3[0] + p3[1]) * p3[2]);
    },
    midiToHz: (m) => mulS(440, powS(2, divS(subS(m, 69), 12))),

    // -- oscillators ---------------------------------------------------
    osc: (f) => el.cycle(f),
    saw: (f) => el.blepsaw(f),
    square: (f) => el.blepsquare(f),
    tri: (f) => el.bleptriangle(f),
    phasor: (f) => el.phasor(f),
    noise: () => el.noise(),
    pink: () => el.pinknoise(),

    // -- uploaded audio (the samples tab) --------------------------------
    // sample loops the whole file at a (possibly signal-driven) rate;
    // scrub reads it like a tape head parked at position 0..1
    sample: (name, rate) => el.sample({path: samplePath(name), mode: 'loop'}, 1, rate),
    scrub: (name, pos) => el.table({path: samplePath(name)}, pos),
    __setSamplePaths: (paths) => {
      for (const k of Object.keys(samplePaths)) delete samplePaths[k];
      Object.assign(samplePaths, paths);
    },

    // -- knobs (the params tab) -----------------------------------------
    param,
    __setParamValues: (values) => {
      for (const k of Object.keys(paramValues)) delete paramValues[k];
      Object.assign(paramValues, values);
    },

    // -- slow modulation: what makes a drone breathe --------------------
    // both run 0..1, ready to scale into any parameter
    lfo: (rate) => el.add(0.5, el.mul(0.5, el.cycle(rate))),
    wander: (rate) => el.smooth(el.tau2pole(divS(0.5, rate)),
                                el.latch(el.train(rate), el.rand())),
    slew: overSig((secs, x) => el.smooth(el.tau2pole(secs), x)),

    // -- sculpting -------------------------------------------------------
    lowpass: overSig((fc, q, x) => el.lowpass(fc, q, x)),
    highpass: overSig((fc, q, x) => el.highpass(fc, q, x)),
    bandpass: overSig((fc, q, x) => el.bandpass(fc, q, x)),
    drive: overSig((amt, x) => el.tanh(el.mul(x, el.add(1, el.mul(4, amt))))),

    // -- space -----------------------------------------------------------
    // echo keeps the dry signal and adds a feedback-delay tail (up to ~5s)
    echo: overSig((ms, fb, x) =>
      el.add(x, el.delay({size: 240000}, el.ms2samps(ms), fb, x))),

    // equal-power stereo placement of a mono signal, pos 0 (L) .. 1 (R)
    pan: (pos, x) => {
      const angle = mulS(pos, Math.PI / 2);
      return [mulS(x, cosS(angle)), mulS(x, sinS(angle))];
    },

    // a wide, diffuse wash: four detuned feedback combs, split L/R and
    // darkened, mixed against the dry signal by `amount`
    space: (amount, x) => {
      const mono = isV(x) ? el.mul(0.5, el.add(x[0], x[1])) : x;
      const comb = (ms, fb) => el.delay({size: 24000}, el.ms2samps(ms), fb, mono);
      const wetL = el.lowpass(3500, 0.7, el.mul(0.4, el.add(comb(53.1, 0.78), comb(73.3, 0.74))));
      const wetR = el.lowpass(3500, 0.7, el.mul(0.4, el.add(comb(61.7, 0.76), comb(89.9, 0.72))));
      const dry = isV(x) ? x : [x, x];
      return [mixS(dry[0], wetL, amount), mixS(dry[1], wetR, amount)];
    },
  };
}
