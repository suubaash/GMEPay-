/**
 * render-docx.js — renders the content model to `outputs/GMEPay+_Feature_Specification.docx`.
 *
 * Same model as the HTML artifact, so the two cannot disagree. The `created`/`modified`
 * timestamps are pinned to a fixed instant on purpose: a regenerated document that differs only
 * in its embedded clock is indistinguishable from one whose CONTENT changed, and this file is
 * committed.
 */

'use strict';

const JSZip = require('jszip');
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  AlignmentType, LevelFormat, HeadingLevel, BorderStyle, WidthType, ShadingType,
  TableOfContents, PageBreak, Footer, PageNumber,
} = require('docx');

/** Fixed so the committed bytes change only when the content does. */
const FIXED_INSTANT = new Date('2026-07-28T00:00:00.000Z');

/**
 * A .docx is a zip, and a zip stamps every entry with the clock at packing time — so an
 * unchanged document produces different bytes on every run. That makes a committed artifact
 * impossible to diff and makes "is the checked-in file up to date?" unanswerable. Repacking with
 * one fixed timestamp per entry makes the bytes a function of the CONTENT alone, which is the
 * property `build.js --check` depends on.
 *
 * The same applies to `docProps/core.xml`: this version of the `docx` library stamps
 * `dcterms:created` / `dcterms:modified` with the wall clock and ignores the `created`/`modified`
 * document options, so those two elements are rewritten here as well.
 *
 * @param {Buffer} buf a freshly packed .docx
 * @returns {Promise<Buffer>} the same document, with every timestamp pinned
 */
async function withFixedTimestamps(buf) {
  const zip = await JSZip.loadAsync(buf);
  const iso = FIXED_INSTANT.toISOString();

  const coreName = 'docProps/core.xml';
  if (zip.files[coreName]) {
    const core = await zip.file(coreName).async('string');
    const pinned = core.replace(
      /(<dcterms:(?:created|modified) xsi:type="dcterms:W3CDTF">)[^<]*(<\/dcterms:(?:created|modified)>)/g,
      `$1${iso}$2`,
    );
    if (pinned === core && /dcterms:created/.test(core)) {
      throw new Error('docProps/core.xml timestamp rewrite matched nothing — the docx library changed its core-properties format; the .docx would no longer be byte-deterministic');
    }
    zip.file(coreName, pinned);
  }

  Object.keys(zip.files).forEach((name) => { zip.files[name].date = FIXED_INSTANT; });
  return zip.generateAsync({
    type: 'nodebuffer',
    compression: 'DEFLATE',
    compressionOptions: { level: 6 },
    platform: 'DOS',
    streamFiles: false,
  });
}

const TONE_COLOR = {
  ok: '1F6136', info: '254B76', warn: '7A5B14', bad: '8A2F26',
};
const TONE_FILL = {
  ok: 'EAF5EE', info: 'EDF3FA', warn: 'FBF5E9', bad: 'FCEDEC',
};

// ---- numbering: one bullet reference + independent numbered refs (restart per list) ----
const numConfigs = [{
  reference: 'bullets',
  levels: [{
    level: 0, format: LevelFormat.BULLET, text: '•', alignment: AlignmentType.LEFT,
    style: { paragraph: { indent: { left: 480, hanging: 240 } } },
  }],
}];
for (let i = 1; i <= 60; i += 1) {
  numConfigs.push({
    reference: `n${i}`,
    levels: [{
      level: 0, format: LevelFormat.DECIMAL, text: '%1.', alignment: AlignmentType.LEFT,
      style: { paragraph: { indent: { left: 480, hanging: 300 } } },
    }],
  });
}

const H1 = (t) => new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun(t)] });
const H3 = (t, extra) => new Paragraph({
  heading: HeadingLevel.HEADING_2,
  children: [new TextRun(t), ...(extra ? [new TextRun({ text: `   [${extra.label}]`, bold: true, size: 18, color: TONE_COLOR[extra.tone] })] : [])],
});
const P = (t) => new Paragraph({ spacing: { after: 120 }, children: [new TextRun(t)] });
const bullet = (label, text) => new Paragraph({
  numbering: { reference: 'bullets', level: 0 },
  children: [
    ...(label ? [new TextRun({ text: label, bold: true })] : []),
    new TextRun(text),
  ],
});
const step = (ref, label, text) => new Paragraph({
  numbering: { reference: ref, level: 0 },
  children: [new TextRun({ text: `${label} — `, bold: true }), new TextRun(text)],
});
const note = (t) => new Paragraph({
  spacing: { after: 160 },
  shading: { fill: TONE_FILL.warn, type: ShadingType.CLEAR },
  children: [
    new TextRun({ text: 'Note: ', bold: true, italics: true, color: TONE_COLOR.warn }),
    new TextRun({ text: t, italics: true, color: TONE_COLOR.warn }),
  ],
});

const cellBorder = { style: BorderStyle.SINGLE, size: 1, color: 'CCCCCC' };
const borders = {
  top: cellBorder, bottom: cellBorder, left: cellBorder, right: cellBorder,
};

function gridTable(head, rows, widths) {
  const mk = (runs, w, isHead) => new TableCell({
    borders,
    width: { size: w, type: WidthType.DXA },
    shading: isHead ? { fill: 'D5E8F0', type: ShadingType.CLEAR } : undefined,
    margins: {
      top: 60, bottom: 60, left: 120, right: 120,
    },
    children: [new Paragraph({ children: runs })],
  });
  const headRow = new TableRow({
    children: head.map((h, i) => mk([new TextRun({ text: h, bold: true })], widths[i], true)),
  });
  const bodyRows = rows.map((r) => new TableRow({
    children: r.map((c, i) => mk(
      Array.isArray(c) ? c : [new TextRun(String(c))],
      widths[i],
      false,
    )),
  }));
  return new Table({
    width: { size: widths.reduce((a, b) => a + b, 0), type: WidthType.DXA },
    columnWidths: widths,
    rows: [headRow, ...bodyRows],
  });
}

function stateRuns(state) {
  return [new TextRun({
    text: state.label, bold: true, size: 18, color: TONE_COLOR[state.tone],
  })];
}

function renderNode(n, ctx) {
  switch (n.t) {
    case 'lead':
    case 'p':
      return [P(n.text)];
    case 'h3':
      return [H3(n.text, n.state)];
    case 'bullet':
      return [bullet(n.label, n.text)];
    case 'steps': {
      ctx.stepRef += 1;
      const ref = `n${ctx.stepRef}`;
      return n.items.map(([label, text]) => step(ref, label, text));
    }
    case 'note':
      return [note(n.text)];
    case 'waterfall':
      return [new Paragraph({
        alignment: AlignmentType.CENTER,
        spacing: { before: 120, after: 120 },
        children: [new TextRun({
          text: n.nodes.join('  →  '), bold: true, size: 24, color: '1F3864',
        })],
      })];
    case 'table':
      return [gridTable(n.head, n.rows, [3400, 5960]), P('')];
    case 'services':
      return [
        gridTable(
          ['Microservice', 'State', 'What it does, and how far it has actually got'],
          n.items.map((s) => [
            [new TextRun({ text: s.term, bold: true })],
            stateRuns(s.state),
            [new TextRun(s.def)],
          ]),
          [2100, 1500, 5760],
        ),
        P(''),
      ];
    case 'status':
      return [
        gridTable(
          ['Capability', 'State', 'Evidence'],
          n.rows.map((r) => [
            [new TextRun({ text: r.capability, bold: true })],
            stateRuns(r.state),
            [new TextRun(r.evidence)],
          ]),
          [2100, 1500, 5760],
        ),
        P(''),
      ];
    case 'legend':
      return [
        gridTable(
          ['State', 'Means exactly this'],
          Object.values(ctx.STATE).map((s) => [stateRuns(s), [new TextRun(s.gloss)]]),
          [1900, 7460],
        ),
        P(''),
      ];
    case 'banner': {
      const shade = { fill: TONE_FILL.bad, type: ShadingType.CLEAR };
      const out = [
        new Paragraph({
          spacing: { before: 120, after: 60 },
          shading: shade,
          children: [new TextRun({
            text: n.title, bold: true, size: 26, color: TONE_COLOR.bad,
          })],
        }),
        new Paragraph({
          spacing: { after: 120 },
          shading: shade,
          children: [new TextRun({ text: n.intro, color: TONE_COLOR.bad, bold: true })],
        }),
      ];
      n.lines.forEach(([label, text]) => {
        out.push(new Paragraph({
          numbering: { reference: 'bullets', level: 0 },
          shading: shade,
          children: [
            new TextRun({ text: `${label} `, bold: true, color: TONE_COLOR.bad }),
            new TextRun({ text, color: TONE_COLOR.bad }),
          ],
        }));
      });
      out.push(new Paragraph({
        spacing: { before: 120, after: 160 },
        shading: shade,
        children: [new TextRun({ text: n.closing, bold: true, color: TONE_COLOR.bad })],
      }));
      return out;
    }
    default:
      throw new Error(`docx renderer has no case for node type '${n.t}'`);
  }
}

/**
 * @param {{META: object, SECTIONS: Array, STATE: object}} model
 * @returns {Promise<Buffer>}
 */
function renderDocx({ META, SECTIONS, STATE }) {
  const ctx = { stepRef: 0, STATE };
  const children = [];

  // ---------- TITLE ----------
  children.push(new Paragraph({
    spacing: { before: 1800, after: 120 },
    alignment: AlignmentType.CENTER,
    children: [new TextRun({
      text: META.title, bold: true, size: 72, color: '1F3864',
    })],
  }));
  children.push(new Paragraph({
    alignment: AlignmentType.CENTER,
    spacing: { after: 80 },
    children: [new TextRun({ text: META.subtitle, bold: true, size: 40 })],
  }));
  children.push(new Paragraph({
    alignment: AlignmentType.CENTER,
    spacing: { after: 360 },
    children: [new TextRun({
      text: META.tagline, italics: true, size: 26, color: '555555',
    })],
  }));
  children.push(new Paragraph({
    alignment: AlignmentType.CENTER,
    spacing: { after: 120 },
    children: [new TextRun({ text: META.version, size: 22 })],
  }));
  children.push(new Paragraph({
    alignment: AlignmentType.CENTER,
    spacing: { after: 240 },
    children: [new TextRun({ text: META.provenance, size: 20, color: '777777' })],
  }));

  // The banner is on the title page too. An exec who reads one page must read it.
  const bannerNode = SECTIONS[0].nodes.find((x) => x.t === 'banner');
  if (!bannerNode) throw new Error('section 0 must carry the never-exercised banner');
  children.push(...renderNode(bannerNode, ctx));
  children.push(new Paragraph({ children: [new PageBreak()] }));

  // ---------- TOC ----------
  children.push(H1('Contents'));
  children.push(new TableOfContents('Contents', { hyperlink: true, headingStyleRange: '1-2' }));
  children.push(new Paragraph({ children: [new PageBreak()] }));

  // ---------- SECTIONS ----------
  SECTIONS.forEach((s, i) => {
    // Section 0 is the banner, already rendered on the title page — render its heading and a
    // pointer rather than duplicating the whole block.
    children.push(H1(`${s.num}. ${s.title}`));
    if (s.id === 's0') {
      children.push(P('This section is reproduced on the title page, deliberately, so that it '
        + 'cannot be skipped by a reader who opens the document and stops.'));
      children.push(...renderNode(bannerNode, ctx));
    } else {
      s.nodes.forEach((n) => children.push(...renderNode(n, ctx)));
    }
    if (i < SECTIONS.length - 1) children.push(new Paragraph({ children: [new PageBreak()] }));
  });

  children.push(new Paragraph({
    spacing: { before: 320 },
    children: [new TextRun({ text: '— End of document —', italics: true, color: '777777' })],
  }));

  const doc = new Document({
    creator: 'GMEPay+ outputs/docgen/build.js',
    title: `${META.title} ${META.subtitle}`,
    description: 'Generated from Documentation/GAP_REGISTER.md. Do not hand-edit.',
    created: FIXED_INSTANT,
    modified: FIXED_INSTANT,
    lastModifiedBy: 'outputs/docgen/build.js',
    styles: {
      default: { document: { run: { font: 'Arial', size: 22 } } },
      paragraphStyles: [
        {
          id: 'Heading1', name: 'Heading 1', basedOn: 'Normal', next: 'Normal', quickFormat: true,
          run: {
            size: 32, bold: true, font: 'Arial', color: '1F3864',
          },
          paragraph: { spacing: { before: 280, after: 160 }, outlineLevel: 0 },
        },
        {
          id: 'Heading2', name: 'Heading 2', basedOn: 'Normal', next: 'Normal', quickFormat: true,
          run: {
            size: 26, bold: true, font: 'Arial', color: '2E5496',
          },
          paragraph: { spacing: { before: 220, after: 120 }, outlineLevel: 1 },
        },
        {
          id: 'Heading3', name: 'Heading 3', basedOn: 'Normal', next: 'Normal', quickFormat: true,
          run: {
            size: 23, bold: true, font: 'Arial', color: '444444',
          },
          paragraph: { spacing: { before: 160, after: 80 }, outlineLevel: 2 },
        },
      ],
    },
    numbering: { config: numConfigs },
    sections: [{
      properties: {
        page: {
          size: { width: 12240, height: 15840 },
          margin: {
            top: 1440, right: 1440, bottom: 1440, left: 1440,
          },
        },
      },
      footers: {
        default: new Footer({
          children: [new Paragraph({
            alignment: AlignmentType.CENTER,
            children: [
              new TextRun({ text: `${META.title} — ${META.subtitle}   ·   generated, do not hand-edit   ·   Page `, size: 18, color: '999999' }),
              new TextRun({ children: [PageNumber.CURRENT], size: 18, color: '999999' }),
            ],
          })],
        }),
      },
      children,
    }],
  });

  return Packer.toBuffer(doc).then(withFixedTimestamps);
}

module.exports = { renderDocx };
