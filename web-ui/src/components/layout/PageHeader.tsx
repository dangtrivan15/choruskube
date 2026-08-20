import type { ReactNode } from "react";

interface PageHeaderProps {
  title: string;
  "data-testid"?: string;
  children?: ReactNode;
}

export default function PageHeader({ title, "data-testid": testId, children }: PageHeaderProps) {
  return (
    <div className="flex flex-col gap-2 md:flex-row md:items-center md:justify-between">
      <h1 data-testid={testId} className="text-xl font-semibold tracking-tight">
        {title}
      </h1>
      {children && (
        // `flex-wrap`, not a single row: several pages put a navigation control, filters, and a
        // primary action side by side here, which cannot fit a phone's width. Without wrapping the
        // row simply overflows the viewport and takes the whole document into horizontal scroll,
        // since nothing in this column establishes a scroll container of its own.
        <div className="flex flex-wrap items-center gap-2">
          {children}
        </div>
      )}
    </div>
  );
}
