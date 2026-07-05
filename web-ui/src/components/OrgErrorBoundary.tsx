import { Component, type ErrorInfo, type ReactNode } from "react";
import { isAuthEnabled, logout } from "@/lib/oidc";

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

export class OrgErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("OrgErrorBoundary caught error:", error, info);
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex h-screen flex-col items-center justify-center gap-4">
          <p className="text-destructive font-medium">Organization Error</p>
          <p className="text-muted-foreground text-sm">
            {this.state.error?.message ?? "An unexpected error occurred."}
          </p>
          <div className="flex gap-2">
            <button
              className="rounded bg-primary px-4 py-2 text-sm text-primary-foreground"
              onClick={() => {
                this.setState({ hasError: false, error: null });
                window.location.reload();
              }}
            >
              Retry
            </button>
            {isAuthEnabled() && (
              <button
                className="rounded border px-4 py-2 text-sm"
                onClick={() => {
                  void logout();
                }}
              >
                Logout
              </button>
            )}
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}
