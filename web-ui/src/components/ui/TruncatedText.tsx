import type { ReactNode } from "react";
import {
  Tooltip,
  TooltipTrigger,
  TooltipContent,
} from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

interface TruncatedTextProps extends Omit<React.ComponentPropsWithoutRef<"span">, "children"> {
  children: React.ReactNode;
  as?: React.ElementType;
  /** The trigger's own host element — e.g. a router `Link` — merged with the computed trigger props. Ignores `as` when set. */
  render?: React.ReactElement;
  /** Tooltip content override; defaults to `children`. */
  tooltip?: ReactNode;
}

export default function TruncatedText({
  children,
  as: As = "span",
  className,
  render,
  tooltip,
  ...rest
}: TruncatedTextProps) {
  return (
    <Tooltip>
      <TooltipTrigger
        render={render ?? <As />}
        className={cn("block truncate", className)}
        {...rest}
      >
        {children}
      </TooltipTrigger>
      <TooltipContent>{tooltip ?? children}</TooltipContent>
    </Tooltip>
  );
}
