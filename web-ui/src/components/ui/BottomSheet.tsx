import { useRef, type ReactNode } from "react";
import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import { XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

interface BottomSheetProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Accessible name for the sheet — always rendered (for the dialog's a11y name), visually hidden when `hideTitle`. */
  title: ReactNode;
  /** Hide the title visually — use when the sheet body already shows the same heading. */
  hideTitle?: boolean;
  /** Render the built-in close button. Default `true` — pass `false` when the body has its own close affordance. */
  showClose?: boolean;
  children: ReactNode;
  "data-testid"?: string;
}

/**
 * Shared modal bottom-sheet primitive — replaces the hand-rolled `fixed h-[85vh]`
 * overlays previously duplicated across run detail and the two roadmap canvases.
 * Built on the already-installed Base UI Dialog, so it inherits backdrop dismissal,
 * Escape handling and focus trapping from the Root's modal behavior; this component
 * only shapes the popup and pins initial focus off of any input it might contain.
 */
export default function BottomSheet({
  open,
  onOpenChange,
  title,
  hideTitle = false,
  showClose = true,
  children,
  "data-testid": dataTestId,
}: BottomSheetProps) {
  const popupRef = useRef<HTMLDivElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Backdrop className="fixed inset-0 z-50 bg-black/40 transition-opacity duration-200 data-open:opacity-100 data-closed:opacity-0" />
        <DialogPrimitive.Popup
          ref={popupRef}
          data-testid={dataTestId}
          tabIndex={-1}
          // Never the first tabbable element (which could be a feedback textarea) — the
          // close button when one exists, else the popup container itself.
          initialFocus={() => closeButtonRef.current ?? popupRef.current}
          className="fixed inset-x-0 bottom-0 z-50 flex max-h-[85dvh] flex-col rounded-t-xl border-t bg-background shadow-lg pb-[env(safe-area-inset-bottom)]"
        >
          <div aria-hidden="true" className="mx-auto mt-2 h-1 w-10 shrink-0 rounded-full bg-muted" />
          <div className="flex shrink-0 items-center justify-between gap-2 border-b px-4 py-2">
            <DialogPrimitive.Title className={cn("text-sm font-semibold", hideTitle && "sr-only")}>
              {title}
            </DialogPrimitive.Title>
            {showClose && (
              <DialogPrimitive.Close
                ref={closeButtonRef}
                data-testid="bottom-sheet-close"
                aria-label="Close"
                render={<Button variant="ghost" size="icon-sm" />}
              >
                <XIcon />
              </DialogPrimitive.Close>
            )}
          </div>
          <div className="@container flex-1 overflow-y-auto overflow-x-hidden">{children}</div>
        </DialogPrimitive.Popup>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
