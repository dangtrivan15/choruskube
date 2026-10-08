import { useMediaQuery, MOBILE_QUERY } from "./useMediaQuery";

export function useMobileBreakpoint(): boolean {
  return useMediaQuery(MOBILE_QUERY);
}
