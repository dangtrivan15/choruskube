import type { ReactNode } from "react";

interface PageShellProps {
  children: ReactNode;
  spacing?: "normal" | "relaxed";
}

export default function PageShell({ children, spacing = "normal" }: PageShellProps) {
  const spacingClass = spacing === "relaxed" ? "space-y-6" : "space-y-4";
  return <div className={spacingClass}>{children}</div>;
}
