#!/usr/bin/env node
/**
 * build.js — regenerates the exec-facing GMEPay+ feature specification, in both renderings.
 *
 *   node outputs/docgen/build.js            regenerate both artifacts
 *   node outputs/docgen/build.js --check    verify the committed artifacts are up to date
 *                                          (exits 1 and writes nothing if they are not)
 *
 * Outputs (both committed, both overwritten here — never hand-edit them):
 *   outputs/GMEPay+_Feature_Specification.docx
 *   outputs/feature_spec_artifact.html
 *
 * WHY IT IS BUILT THIS WAY
 * ------------------------
 * This document's audience is executives and prospects, which makes an overstatement in it more
 * damaging than the same overstatement in code. It used to be hand-authored prose that RESTATED
 * platform capabilities, and it drifted: for weeks it told that audience that suspicious
 * transactions are "detected and filed (CTR/STR)" when there is no transaction screening in the
 * payment path, no party identity to screen, and no live filing channel to any authority.
 *
 * Three structural changes stop that recurring:
 *
 *   1. ONE CONTENT MODEL, TWO RENDERINGS. The .docx and the .html are generated from the same
 *      `content.js`. Previously they were separate authored files, so the .docx got corrected
 *      and the .html — the prettier one, the one people were actually shown — did not.
 *   2. CLAIMS ARE PINNED TO THE REGISTER. Every qualified sentence cites the gap that justifies
 *      it and asserts that gap's status at build time. A gap closing or re-opening FAILS the
 *      build with a message naming the claim to revisit. See truth.js.
 *   3. THE SUCCESS STATE IS UNREACHABLE. No capability can be presented as LIVE while the
 *      register records that there is no always-on environment, mirroring `apps/admin-ui`'s rule
 *      that a success colour means a real filing/transmission and nothing else.
 *
 * It reads only committed files — no database, no service, no network — so it is reproducible
 * offline and its output is byte-deterministic.
 */

'use strict';

const fs = require('fs');
const path = require('path');

const { META, SECTIONS, STATE } = require('./content');
const { renderHtml } = require('./render-html');
const { renderDocx } = require('./render-docx');

const OUT_DIR = path.resolve(__dirname, '..');
const DOCX_PATH = path.join(OUT_DIR, 'GMEPay+_Feature_Specification.docx');
const HTML_PATH = path.join(OUT_DIR, 'feature_spec_artifact.html');

const checkOnly = process.argv.includes('--check');

async function main() {
  const model = { META, SECTIONS, STATE };

  const html = renderHtml(model);
  const docx = await renderDocx(model);

  if (checkOnly) {
    const stale = [];
    const compare = (file, produced) => {
      let existing;
      try {
        existing = fs.readFileSync(file);
      } catch {
        stale.push(`${path.basename(file)} (missing)`);
        return;
      }
      const want = Buffer.isBuffer(produced) ? produced : Buffer.from(produced, 'utf8');
      if (!existing.equals(want)) stale.push(path.basename(file));
    };
    compare(HTML_PATH, html);
    compare(DOCX_PATH, docx);
    if (stale.length > 0) {
      process.stderr.write(
        `STALE: ${stale.join(', ')} do(es) not match the generator.\n`
        + 'Run "node outputs/docgen/build.js" and commit the result.\n',
      );
      process.exitCode = 1;
      return;
    }
    process.stdout.write('OK — both rendered artifacts match the generator.\n');
    return;
  }

  fs.writeFileSync(HTML_PATH, html, 'utf8');
  fs.writeFileSync(DOCX_PATH, docx);
  process.stdout.write(`WROTE ${HTML_PATH} ${Buffer.byteLength(html, 'utf8')} bytes\n`);
  process.stdout.write(`WROTE ${DOCX_PATH} ${docx.length} bytes\n`);
}

main().catch((e) => {
  process.stderr.write(`${e.message}\n`);
  process.exitCode = 1;
});
