interface PlatformManagedCredentialPanelProps {
  badgeLabel: string;
  hintPrefix: string;
  hint: string;
  rotationHelp: string;
  testId?: string;
}

export default function PlatformManagedCredentialPanel({
  badgeLabel,
  hintPrefix,
  hint,
  rotationHelp,
  testId,
}: PlatformManagedCredentialPanelProps) {
  return (
    <div className="space-y-3" data-testid={testId}>
      <div className="flex items-center gap-3 rounded-md border p-3">
        <div className="h-2 w-2 rounded-full bg-green-500" />
        <span className="text-sm font-medium">{badgeLabel}</span>
        <span className="font-mono text-sm text-muted-foreground">
          {hintPrefix}****{hint}
        </span>
      </div>
      <p className="text-muted-foreground text-sm">{rotationHelp}</p>
    </div>
  );
}
