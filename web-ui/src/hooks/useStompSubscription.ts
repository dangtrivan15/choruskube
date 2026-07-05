import { useEffect, useRef } from "react";
import type { IMessage } from "@stomp/stompjs";
import { subscribe } from "@/lib/stomp";

/**
 * Subscribe to a STOMP topic on the shared client.
 *
 * Pass `null` as `topic` to skip the subscription (e.g. when data isn't
 * ready yet).  The callback is stored in a ref so callers don't need to
 * memoize it — changes to the function identity won't cause a
 * resubscribe.
 */
export function useStompSubscription(
  topic: string | null,
  onMessage: (message: IMessage) => void,
): void {
  const callbackRef = useRef(onMessage);
  callbackRef.current = onMessage;

  useEffect(() => {
    if (!topic) return;
    return subscribe(topic, (msg) => callbackRef.current(msg));
  }, [topic]);
}
