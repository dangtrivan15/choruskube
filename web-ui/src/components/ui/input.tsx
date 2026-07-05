import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/lib/utils"

const inputVariants = cva(
  "flex w-full rounded-lg border border-input bg-transparent px-2.5 py-2 text-base transition-colors outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:bg-input/50 disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 md:text-sm dark:bg-input/30 dark:disabled:bg-input/80 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40",
  {
    variants: {
      variant: {
        default: "",
        ghost: "border-transparent shadow-none",
      },
      // Note: the CVA `size` prop shadows the native HTML `size` attribute.
      // If a consumer needs the native attribute, pass it via the spread props
      // and use a CVA size separately (e.g., `<Input size="sm" {...{ size: 20 }}`
      // won't work — rename the native attr or avoid the collision).
      // This matches the `buttonVariants` convention used throughout the codebase.
      size: {
        default: "h-9",
        sm: "h-8",
        lg: "h-10",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

// React 19: ref is a regular prop in ComponentProps, forwarded via {...props}.
// No React.forwardRef needed — matches Button, Textarea, and other UI components.
function Input({
  className,
  variant,
  size,
  ...props
}: Omit<React.ComponentProps<"input">, "size"> & VariantProps<typeof inputVariants>) {
  return (
    <input
      data-slot="input"
      className={cn(
        inputVariants({ variant, size }),
        "file:inline-flex file:h-7 file:border-0 file:bg-transparent file:px-2.5 file:font-medium file:text-foreground file:text-sm",
        className
      )}
      {...props}
    />
  )
}

export { Input, inputVariants }
