import { FileText } from "lucide-react";
import MarkdownViewer from "@/components/ui/MarkdownViewer";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";

interface PromptViewerDialogProps {
  promptText: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export default function PromptViewerDialog({
  promptText,
  open,
  onOpenChange,
}: PromptViewerDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent size="3xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <FileText className="h-4 w-4" />
            Feature Request
          </DialogTitle>
          <DialogDescription>
            Full prompt this run was started with
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-auto">
          <MarkdownViewer content={promptText} maxHeight="" />
        </div>
      </DialogContent>
    </Dialog>
  );
}
