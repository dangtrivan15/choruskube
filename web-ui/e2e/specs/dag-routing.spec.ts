import { test, expect, type Page } from "@playwright/test";

const FIXTURES = ["feature_development", "sparse_chain", "dense_fanout"] as const;

interface Rect { x: number; y: number; width: number; height: number; }

function rectsOverlap(a: Rect, b: Rect): boolean {
  return !(a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y);
}

interface DomEdge {
  id: string;
  source: string;
  target: string;
  pathD: string;
  labelRect?: Rect;
}

async function readGraph(page: Page): Promise<{ nodes: Map<string, Rect>; edges: DomEdge[] }> {
  return await page.evaluate(() => {
    const nodeMap: Record<string, Rect> = {};
    document.querySelectorAll(".react-flow__node").forEach((el) => {
      const id = (el as HTMLElement).getAttribute("data-id") ?? "";
      const r = el.getBoundingClientRect();
      nodeMap[id] = { x: r.x, y: r.y, width: r.width, height: r.height };
    });
    const edges: DomEdge[] = [];
    document.querySelectorAll(".react-flow__edge").forEach((el) => {
      const id = (el as HTMLElement).getAttribute("data-id") ?? "";
      const arrow = el.querySelector("path") as SVGPathElement | null;
      const labelEl = el.querySelector(".react-flow__edge-label") as HTMLElement | null;
      const source = (el as HTMLElement).getAttribute("data-source") ?? id.split("->")[0] ?? "";
      const target = (el as HTMLElement).getAttribute("data-target") ?? id.split("->")[1]?.split(":")[0] ?? "";
      let labelRect: Rect | undefined;
      if (labelEl) {
        const r = labelEl.getBoundingClientRect();
        labelRect = { x: r.x, y: r.y, width: r.width, height: r.height };
      }
      edges.push({ id, source, target, pathD: arrow?.getAttribute("d") ?? "", labelRect });
    });
    return { nodeMap, edges };
  }).then((raw) => ({
    nodes: new Map(Object.entries(raw.nodeMap)),
    edges: raw.edges,
  }));
}

async function samplePathPointsScreen(page: Page, pathD: string, samples: number): Promise<{ x: number; y: number }[]> {
  return page.evaluate(({ pathD, samples }) => {
    const svgNS = "http://www.w3.org/2000/svg";
    const tmp = document.createElementNS(svgNS, "path");
    tmp.setAttribute("d", pathD);
    const svg = document.querySelector("svg.react-flow__edges") as SVGSVGElement | null;
    if (!svg) return [];
    svg.appendChild(tmp);
    const total = tmp.getTotalLength();
    const ctm = tmp.getScreenCTM();
    const pts: { x: number; y: number }[] = [];
    if (ctm) {
      for (let i = 0; i <= samples; i++) {
        const p = tmp.getPointAtLength((total * i) / samples);
        const sp = svg.createSVGPoint();
        sp.x = p.x;
        sp.y = p.y;
        const screen = sp.matrixTransform(ctm);
        pts.push({ x: screen.x, y: screen.y });
      }
    }
    svg.removeChild(tmp);
    return pts;
  }, { pathD, samples });
}

for (const fixture of FIXTURES) {
  test(`/dev/dag-playground (${fixture}): no edge crosses unrelated nodes`, async ({ page }) => {
    await page.goto(`/dev/dag-playground?fixture=${fixture}`);
    await expect(page.locator('[data-testid="run-dag-container"]')).toHaveAttribute("data-elk-ready", "true", { timeout: 5000 });

    const { nodes, edges } = await readGraph(page);

    for (const edge of edges) {
      if (!edge.pathD) continue;
      const samples = await samplePathPointsScreen(page, edge.pathD, 40);
      for (const [nodeId, rect] of nodes) {
        if (nodeId === edge.source || nodeId === edge.target) continue;
        const padded: Rect = { x: rect.x + 2, y: rect.y + 2, width: rect.width - 4, height: rect.height - 4 };
        const inside = samples.find((p) => p.x >= padded.x && p.x <= padded.x + padded.width && p.y >= padded.y && p.y <= padded.y + padded.height);
        expect(inside, `edge ${edge.id} crosses through unrelated node ${nodeId}`).toBeUndefined();
      }
    }
  });

  test(`/dev/dag-playground (${fixture}): edge labels do not overlap each other`, async ({ page }) => {
    await page.goto(`/dev/dag-playground?fixture=${fixture}`);
    await expect(page.locator('[data-testid="run-dag-container"]')).toHaveAttribute("data-elk-ready", "true", { timeout: 5000 });

    const { edges } = await readGraph(page);
    const labels = edges.filter((e) => e.labelRect).map((e) => ({ id: e.id, rect: e.labelRect! }));

    for (let i = 0; i < labels.length; i++) {
      for (let j = i + 1; j < labels.length; j++) {
        const overlap = rectsOverlap(labels[i].rect, labels[j].rect);
        expect(overlap, `labels ${labels[i].id} and ${labels[j].id} overlap`).toBe(false);
      }
    }
  });
}
