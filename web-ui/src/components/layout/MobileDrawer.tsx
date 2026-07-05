import type { ReactNode } from "react";
import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";

interface MobileDrawerProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  children: ReactNode;
}

export default function MobileDrawer({ open, onOpenChange, children }: MobileDrawerProps) {
  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Backdrop
          className="fixed inset-0 z-50 bg-black/40 transition-opacity duration-200 data-open:opacity-100 data-closed:opacity-0"
        />
        <DialogPrimitive.Popup
          aria-label="Navigation"
          className="fixed inset-y-0 left-0 z-50 w-72 bg-sidebar shadow-lg transition-transform duration-200 ease-in-out will-change-transform data-open:translate-x-0 data-closed:-translate-x-full"
        >
          <DialogPrimitive.Title className="sr-only">
            Navigation menu
          </DialogPrimitive.Title>
          <DialogPrimitive.Description className="sr-only">
            Main navigation sidebar
          </DialogPrimitive.Description>
          {children}
        </DialogPrimitive.Popup>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
