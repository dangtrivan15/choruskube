import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api, ApiError } from "@/lib/api";
import type {
  OrgGitHubCredentialResponse,
  CredentialHealthResponse,
  CheckRepoReachabilityResponse,
} from "@/lib/types";

export const GITHUB_CREDENTIAL_QUERY_KEY = (orgId: string) =>
  ["org-github-credential", orgId] as const;

export function useGitHubCredential(orgId: string) {
  return useQuery<OrgGitHubCredentialResponse | null>({
    queryKey: GITHUB_CREDENTIAL_QUERY_KEY(orgId),
    queryFn: async () => {
      try {
        return await api.get<OrgGitHubCredentialResponse>(
          `/organizations/${orgId}/github-credential`
        );
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
    enabled: !!orgId,
  });
}

export function useSaveGitHubCredential() {
  const queryClient = useQueryClient();
  return useMutation<
    OrgGitHubCredentialResponse,
    ApiError,
    { orgId: string; token: string; force?: boolean }
  >({
    mutationFn: ({ orgId, token, force = false }) =>
      api.put<OrgGitHubCredentialResponse>(
        `/organizations/${orgId}/github-credential${force ? "?force=true" : ""}`,
        { token }
      ),
    onSuccess: (_, { orgId }) =>
      queryClient.invalidateQueries({ queryKey: GITHUB_CREDENTIAL_QUERY_KEY(orgId) }),
  });
}

export function useDeleteGitHubCredential() {
  const queryClient = useQueryClient();
  return useMutation<void, ApiError, { orgId: string; force?: boolean }>({
    mutationFn: ({ orgId, force = false }) =>
      api.delete(`/organizations/${orgId}/github-credential${force ? "?force=true" : ""}`),
    onSuccess: (_, { orgId }) =>
      queryClient.invalidateQueries({ queryKey: GITHUB_CREDENTIAL_QUERY_KEY(orgId) }),
  });
}

export function useVerifyGitHubCredential() {
  const queryClient = useQueryClient();
  return useMutation<CredentialHealthResponse, ApiError, { orgId: string }>({
    mutationFn: ({ orgId }) =>
      api.post<CredentialHealthResponse>(
        `/organizations/${orgId}/github-credential/verify`
      ),
    onSuccess: (_, { orgId }) =>
      queryClient.invalidateQueries({ queryKey: GITHUB_CREDENTIAL_QUERY_KEY(orgId) }),
  });
}

export function useCheckRepoReachability() {
  return useMutation<
    CheckRepoReachabilityResponse,
    ApiError,
    { orgId: string; url: string }
  >({
    mutationFn: ({ orgId, url }) =>
      api.post<CheckRepoReachabilityResponse>(
        `/organizations/${orgId}/github-credential/check-repo`,
        { url }
      ),
  });
}
