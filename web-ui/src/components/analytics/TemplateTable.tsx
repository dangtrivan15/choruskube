import type { TemplateAnalytics } from "@/lib/types";
import TruncatedText from "@/components/ui/TruncatedText";

interface TemplateTableProps {
  templates: TemplateAnalytics[];
}

export default function TemplateTable({ templates }: TemplateTableProps) {
  if (templates.length === 0) {
    return (
      <div className="flex h-32 items-center justify-center text-sm text-muted-foreground">
        No template data for this period
      </div>
    );
  }

  return (
    // overflow-x-auto requires the parent grid item to carry min-w-0,
    // otherwise the table's intrinsic width inflates the grid column
    // and the page scrolls horizontally instead. See AnalyticsPage.tsx.
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-muted-foreground">
            <th className="pb-2 pr-4 font-medium">Template</th>
            <th className="pb-2 pr-4 text-right font-medium">Runs</th>
            <th className="pb-2 pr-4 text-right font-medium">Completed</th>
            <th className="pb-2 pr-4 text-right font-medium">Failed</th>
            <th className="pb-2 text-right font-medium">Success Rate</th>
          </tr>
        </thead>
        <tbody>
          {templates.map((t) => (
            <tr key={t.templateName} className="border-b last:border-0">
              <td className="py-2 pr-4 font-medium max-w-xs">
                <TruncatedText>{t.templateName}</TruncatedText>
              </td>
              <td className="py-2 pr-4 text-right tabular-nums">{t.runCount}</td>
              <td className="py-2 pr-4 text-right tabular-nums text-status-success">{t.completedCount}</td>
              <td className="py-2 pr-4 text-right tabular-nums text-status-error">{t.failedCount}</td>
              <td className="py-2 text-right tabular-nums">{t.successRate.toFixed(1)}%</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
