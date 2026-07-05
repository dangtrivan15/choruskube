import { createContext, useContext } from "react";
import type { ReactNode } from "react";

export type ResolveFeedTopic = (feed: string) => string;

export interface FeedTopicContextValue {
  resolveFeedTopic: ResolveFeedTopic;
}

const defaultResolveFeedTopic: ResolveFeedTopic = (feed) => `/topic/${feed}`;

const FeedTopicContext = createContext<FeedTopicContextValue>({
  resolveFeedTopic: defaultResolveFeedTopic,
});

export function FeedTopicProvider({
  resolveFeedTopic,
  children,
}: {
  resolveFeedTopic: ResolveFeedTopic;
  children: ReactNode;
}) {
  return (
    <FeedTopicContext.Provider value={{ resolveFeedTopic }}>
      {children}
    </FeedTopicContext.Provider>
  );
}

/** Returns the feed-topic resolver. Core default yields org-free topics (`/topic/${feed}`). */
export function useResolveFeedTopic(): ResolveFeedTopic {
  return useContext(FeedTopicContext).resolveFeedTopic;
}
