import type { Locator, Response } from "@playwright/test";

// Past the 5px activation distance every board's PointerSensor sets, still inside the card.
const ACTIVATION_NUDGE_PX = 12;

export interface BoardDrag {
  /** The card to move. Must resolve to exactly one element. */
  card: Locator;
  /** Where to press: a non-interactive part of the card, never the title `<Link>`, which stops `pointerdown`. */
  grip: Locator;
  /** The droppable column to release over. */
  target: Locator;
  /** Matches the response of the write this card's drop sends. */
  isWrite: (response: Response) => boolean;
}

/**
 * Drags `card` onto `target` with a real pointer gesture and returns the drop's write response.
 *
 * Releases only once dnd-kit marks `card` itself active (`aria-pressed`): another worker's create
 * can shift a neighbour under the press, and dropping that neighbour moves the other worker's item.
 * Any other pick-up is cancelled with Escape, which reaches `onDragCancel`, never `onDragEnd`.
 */
export async function dragBoardCard(
  { card, grip, target, isWrite }: BoardDrag,
  attempts = 3,
): Promise<Response> {
  const page = card.page();

  for (let attempt = 1; attempt <= attempts; attempt++) {
    await grip.scrollIntoViewIfNeeded();
    const sourceBox = await grip.boundingBox();
    const targetBox = await target.boundingBox();
    if (!sourceBox || !targetBox) continue;

    const startX = sourceBox.x + sourceBox.width / 2;
    const startY = sourceBox.y + sourceBox.height / 2;

    await page.mouse.move(startX, startY);
    await page.mouse.down();
    await page.mouse.move(startX + ACTIVATION_NUDGE_PX, startY, { steps: 3 });

    const pickedUpThisCard = await card
      .and(page.locator('[aria-pressed="true"]'))
      .waitFor({ state: "attached", timeout: 2_000 })
      .then(
        () => true,
        () => false,
      );

    if (!pickedUpThisCard) {
      await page.keyboard.press("Escape");
      // Released where it was pressed, a press that never activated (e.g. on a title link) becomes a click.
      await page.mouse.move(0, 0);
      await page.mouse.up();
      continue;
    }

    await page.mouse.move(
      targetBox.x + targetBox.width / 2,
      targetBox.y + Math.min(40, targetBox.height / 2),
      { steps: 10 },
    );
    const write = page.waitForResponse(isWrite, { timeout: 30_000 });
    await page.mouse.up();
    return await write;
  }

  throw new Error(`dnd-kit never picked up the intended card in ${attempts} attempts`);
}
