import { Bell } from "lucide-react";
import { useActivityFeed } from "@/hooks/useActivityFeed";

interface ActivityFeedButtonProps {
  onClick: () => void;
}

export default function ActivityFeedButton({ onClick }: ActivityFeedButtonProps) {
  const { unreadCount } = useActivityFeed();

  return (
    <button
      type="button"
      onClick={onClick}
      className="relative inline-flex items-center justify-center rounded-md p-2 text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      aria-label={
        unreadCount > 0
          ? `Activity feed — ${unreadCount} unread`
          : "Activity feed"
      }
    >
      <Bell className="h-4 w-4" />
      {unreadCount > 0 && (
        <span className="absolute -right-0.5 -top-0.5 inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-bold text-white">
          {unreadCount > 99 ? "99+" : unreadCount}
        </span>
      )}
    </button>
  );
}
