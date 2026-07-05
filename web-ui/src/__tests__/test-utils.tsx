import { type ReactNode } from "react";
import { render, type RenderOptions } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router";
import { ActivityFeedProvider } from "@/hooks/useActivityFeed";

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        gcTime: 0,
      },
      mutations: {
        retry: false,
      },
    },
  });
}

function createWrapper(initialEntries: string[] = ["/"]) {
  const queryClient = createTestQueryClient();
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <ActivityFeedProvider>
          <MemoryRouter initialEntries={initialEntries}>{children}</MemoryRouter>
        </ActivityFeedProvider>
      </QueryClientProvider>
    );
  };
}

export function renderWithProviders(
  ui: React.ReactElement,
  options?: Omit<RenderOptions, "wrapper"> & { initialEntries?: string[] }
) {
  const { initialEntries, ...renderOptions } = options ?? {};
  return render(ui, {
    wrapper: createWrapper(initialEntries),
    ...renderOptions,
  });
}

export function createTestHookWrapper(initialEntries: string[] = ["/"]) {
  const queryClient = createTestQueryClient();
  return {
    queryClient,
    wrapper: ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>
        <ActivityFeedProvider>
          <MemoryRouter initialEntries={initialEntries}>{children}</MemoryRouter>
        </ActivityFeedProvider>
      </QueryClientProvider>
    ),
  };
}

export { createTestQueryClient };
