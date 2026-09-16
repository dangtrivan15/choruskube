import { useState } from "react";
import { LayoutList, Workflow } from "lucide-react";
import { Button } from "@/components/ui/button";
import RoadmapCandidateBreakdown from "./RoadmapCandidateBreakdown";
import RoadmapCandidateGraph from "./RoadmapCandidateGraph";
import type { RoadmapCandidatesDocument } from "@/lib/types";

type View = "cards" | "graph";

interface Props {
  value: RoadmapCandidatesDocument;
  onChange: (next: RoadmapCandidatesDocument) => void;
}

/**
 * The reviewer's roadmap-proposal editor with two synchronized views over one
 * document: the card breakdown (ticket-field editing) and the graph (structure
 * + dependency-edge editing). Both receive the same `value`/`onChange`, so an
 * edit in either is visible in the other on toggle.
 */
export default function RoadmapCandidateReview({ value, onChange }: Props) {
  const [view, setView] = useState<View>("cards");

  return (
    <div data-testid="roadmap-candidate-review" className="space-y-2">
      <div className="flex items-center gap-1">
        <Button
          type="button"
          size="xs"
          variant={view === "cards" ? "default" : "outline"}
          data-testid="candidate-view-cards"
          onClick={() => setView("cards")}
        >
          <LayoutList className="h-3 w-3" />
          Cards
        </Button>
        <Button
          type="button"
          size="xs"
          variant={view === "graph" ? "default" : "outline"}
          data-testid="candidate-view-graph"
          onClick={() => setView("graph")}
        >
          <Workflow className="h-3 w-3" />
          Graph
        </Button>
      </div>

      {view === "cards" ? (
        <RoadmapCandidateBreakdown value={value} onChange={onChange} />
      ) : (
        <RoadmapCandidateGraph value={value} onChange={onChange} />
      )}
    </div>
  );
}
