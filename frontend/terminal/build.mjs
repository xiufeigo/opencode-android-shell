import { build } from "esbuild"
import { fileURLToPath } from "node:url"
import { patchPanelLifecycle } from "./panel-lifecycle.mjs"

// The shipped asset embeds runtime xterm code, so the MIT notices for the three
// runtime packages travel inside the APK. esbuild's `legalComments: "eof"` only
// keeps the short `@license` headers, never the license bodies, so the full texts
// are injected here as a banner. Mirrored, with provenance, in THIRD_PARTY_NOTICES.txt.
const mitBody = `Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.`

const banner = `/*!
 * oc-terminal.js bundles the MIT-licensed xterm runtime below.
 * Full notices: frontend/terminal/THIRD_PARTY_NOTICES.txt
 * Bundler (esbuild) is a devDependency and is not part of this asset.
 *
 * ==== @xterm/xterm 6.0.0 — MIT ====
 * Copyright (c) 2017-2019, The xterm.js authors (https://github.com/xtermjs/xterm.js)
 * Copyright (c) 2014-2016, SourceLair Private Company (https://www.sourcelair.com)
 * Copyright (c) 2012-2013, Christopher Jeffrey (https://github.com/chjj/)
 *
 * The inlined css/xterm.css also carries this notice:
 * Copyright (c) 2014 The xterm.js authors. All rights reserved.
 * Copyright (c) 2012-2013, Christopher Jeffrey (MIT License) — https://github.com/chjj/term.js
 * @license MIT
 *
 * ${mitBody}
 *
 * ==== @xterm/addon-fit 0.11.0 — MIT ====
 * Copyright (c) 2019, The xterm.js authors (https://github.com/xtermjs/xterm.js)
 *
 * ${mitBody}
 *
 * ==== @xterm/addon-serialize 0.14.0 — MIT ====
 * package.json declares "license": "MIT"; no LICENSE file in the package.
 * Copyright (c) 2014-2024 The xterm.js authors. All rights reserved.
 * Copyright (c) 2012-2013, Christopher Jeffrey (MIT License)
 * Originally forked from (with the author's permission):
 *   Fabrice Bellard's javascript vt100 for jslinux:
 *   http://bellard.org/jslinux/
 *   Copyright (c) 2011 Fabrice Bellard
 *
 * ${mitBody}
 */`

await build({
  absWorkingDir: fileURLToPath(new URL(".", import.meta.url)),
  entryPoints: ["terminal.js"],
  outfile: "../../webapp/src/main/assets/www/oc-terminal.js",
  bundle: true,
  minify: true,
  format: "esm",
  target: "chrome100",
  loader: { ".css": "text" },
  legalComments: "eof",
  banner: { js: banner },
})
await patchPanelLifecycle()
