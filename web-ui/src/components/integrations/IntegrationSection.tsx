import type { ReactNode } from "react";

interface IntegrationSectionProps {
  title: string;
  description: string;
  testId?: string;
  children: ReactNode;
}

export default function IntegrationSection({
  title,
  description,
  testId,
  children,
}: IntegrationSectionProps) {
  return (
    <div data-testid={testId} className="space-y-6">
      <div>
        <h3 className="text-base font-semibold">{title}</h3>
        <p className="text-muted-foreground text-sm">{description}</p>
      </div>
      {children}
    </div>
  );
}
