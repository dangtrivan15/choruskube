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
        <div className="flex items-center gap-2">
          {children}
        </div>
      )}
    </div>
  );
}
