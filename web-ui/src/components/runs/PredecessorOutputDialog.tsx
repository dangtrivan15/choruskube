import { FileText } from "lucide-react";
import MarkdownViewer from "@/components/ui/MarkdownViewer";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";

interface PredecessorOutputDialogProps {
  nodeLabel: string;
  resultContent: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export default function PredecessorOutputDialog({
  nodeLabel,
  resultContent,
  open,
  onOpenChange,
}: PredecessorOutputDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent size="3xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <FileText className="h-4 w-4" />
            {nodeLabel} — Previous Step Output
          </DialogTitle>
          <DialogDescription>
            Full output from the predecessor node
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-auto">
          <MarkdownViewer content={resultContent} maxHeight="" />
        </div>
      </DialogContent>
    </Dialog>
  );
}
