import { Menu } from "lucide-react";
import { Button } from "@/components/ui/button";
import Logo from "@/components/Logo";
import ThemeToggle from "./ThemeToggle";
import ActivityFeedButton from "./ActivityFeedButton";

interface MobileHeaderProps {
  onMenuToggle: () => void;
  onActivityFeedToggle: () => void;
}

export default function MobileHeader({ onMenuToggle, onActivityFeedToggle }: MobileHeaderProps) {
  return (
    <header className="flex h-12 items-center justify-between border-b px-4">
      <div className="flex items-center gap-2">
        <Button
          data-testid="mobile-menu-button"
          variant="ghost"
          size="icon"
          onClick={onMenuToggle}
          aria-label="Open navigation menu"
        >
          <Menu className="h-5 w-5" />
        </Button>
        <Logo size={18} />
        <span className="text-sm font-semibold tracking-tight">ChorusKube</span>
      </div>
      <div className="flex items-center gap-1">
        <ActivityFeedButton onClick={onActivityFeedToggle} />
        <ThemeToggle />
      </div>
    </header>
  );
}
