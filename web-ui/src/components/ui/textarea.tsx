import * as React from "react"

import { cn } from "@/lib/utils"
import { inputVariants } from "@/components/ui/input"

function Textarea({ className, ...props }: React.ComponentProps<"textarea">) {
  return (
    <textarea
      data-slot="textarea"
      className={cn(
        inputVariants({ size: null }),
        "field-sizing-content min-h-16",
        className
      )}
      {...props}
    />
  )
}

export { Textarea }
