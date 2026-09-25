import StatusCallout from "@/components/ui/StatusCallout";

interface ErrorAlertProps {
  message: string;
}

export default function ErrorAlert({ message }: ErrorAlertProps) {
  return (
    <StatusCallout tone="error" role="alert">
      {message}
    </StatusCallout>
  );
}
