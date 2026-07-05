import { useMemo, type ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "react-router";
import { buildRouter } from "./router";
import { useExtensions } from "./ExtensionsContext";
import { ActivityFeedProvider } from "./hooks/useActivityFeed";
import { Toaster } from "./components/ui/toaster";
import { AuthProvider, useAuth } from "./components/AuthProvider";
import { OrgErrorBoundary } from "./components/OrgErrorBoundary";
import { ImpersonationBannerSlot } from "@/lib/impersonation";
import InvitationAcceptPage from "./pages/InvitationAcceptPage";
import OnboardingWizard from "./pages/OnboardingWizard";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { refetchOnWindowFocus: false },
  },
});

function AuthenticatedApp() {
  const { onboardingCompleted } = useAuth();
  const { routes, authedProviders } = useExtensions();
  // Extensions are injected once at bootstrap, so `routes` is a stable reference and the
  // router is built once (recreating it would discard browser-history state).
  const router = useMemo(() => buildRouter(routes ?? []), [routes]);

  const authedProviderList = authedProviders ?? [];
  const content: ReactNode = (
    <ActivityFeedProvider>
      <ImpersonationBannerSlot />
      {!onboardingCompleted ? (
        <OnboardingWizard />
      ) : (
        <OrgErrorBoundary>
          <RouterProvider router={router} />
        </OrgErrorBoundary>
      )}
    </ActivityFeedProvider>
  );

  return (
    <>
      {authedProviderList.reduceRight<ReactNode>(
        (children, Provider) => <Provider>{children}</Provider>,
        content,
      )}
    </>
  );
}

export default function App() {
  // Invitation accept page lives outside AuthProvider — it manages its own
  // OIDC init (check-sso) and does not call /me on load.
  const isInvitationAcceptPath = window.location.pathname.match(
    /^\/invitations\/[^/]+$/,
  );

  return (
    <QueryClientProvider client={queryClient}>
      {isInvitationAcceptPath ? (
        <InvitationAcceptPage />
      ) : (
        <AuthProvider>
          <AuthenticatedApp />
        </AuthProvider>
      )}
      <Toaster />
    </QueryClientProvider>
  );
}
