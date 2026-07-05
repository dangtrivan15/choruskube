import {
  Tooltip,
  TooltipTrigger,
  TooltipContent,
} from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

interface TruncatedTextProps {
  children: React.ReactNode;
  as?: React.ElementType;
  className?: string;
}

export default function TruncatedText({
  children,
  as: As = "span",
  className,
}: TruncatedTextProps) {
  return (
    <Tooltip>
      <TooltipTrigger render={<As />} className={cn("block truncate", className)}>
        {children}
      </TooltipTrigger>
      <TooltipContent>{children}</TooltipContent>
    </Tooltip>
  );
}
