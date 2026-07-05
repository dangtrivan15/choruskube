import { Link, useParams } from "react-router";
import { BookOpen, ArrowLeft } from "lucide-react";
import { Skeleton } from "@/components/ui/skeleton";
import MarkdownViewer from "@/components/ui/MarkdownViewer";
import PageHeader from "@/components/layout/PageHeader";
import { useDocsList, useDocsPage } from "@/hooks/useDocs";

function DocsListView() {
  const { data: docs, isLoading, isError } = useDocsList();

  if (isLoading) {
    return (
      <div className="space-y-2">
        {Array.from({ length: 3 }).map((_, i) => (
          <Skeleton key={i} className="h-10 w-full" />
        ))}
      </div>
    );
  }

  if (isError) {
    return (
      <div className="text-sm text-destructive">
        Failed to load documentation. Please try again.
      </div>
    );
  }

  const sorted = (docs ?? []).slice().sort((a, b) => a.order - b.order);

  return (
    <div>
      <PageHeader data-testid="docs-heading" title="Documentation" />
      <ul className="mt-6 space-y-2">
        {sorted.map((doc) => (
          <li key={doc.slug}>
            <Link
              data-testid="docs-list-item"
              to={`/docs/${doc.slug}`}
              className="flex items-start gap-3 rounded-md border p-4 text-sm font-medium transition-colors hover:bg-muted/50"
            >
              <BookOpen className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
              <div>
                <span>{doc.title}</span>
                {doc.description && (
                  <p data-testid="docs-list-item-description" className="mt-0.5 text-xs font-normal text-muted-foreground">{doc.description}</p>
                )}
              </div>
            </Link>
          </li>
        ))}
        {sorted.length === 0 && (
          <li className="text-sm text-muted-foreground">No documentation pages found.</li>
        )}
      </ul>
    </div>
  );
}

function DocsPageView({ slug }: { slug: string }) {
  const { data: page, isLoading, isError } = useDocsPage(slug);

  if (isLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-1/2" />
        <Skeleton className="h-4 w-full" />
        <Skeleton className="h-4 w-full" />
        <Skeleton className="h-4 w-3/4" />
      </div>
    );
  }

  if (isError) {
    return (
      <div>
        <Link
          to="/docs"
          className="mb-4 flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="h-4 w-4" />
          Back to Documentation
        </Link>
        <p data-testid="docs-load-error" className="text-sm text-destructive">
          Failed to load documentation page. Please try again.
        </p>
      </div>
    );
  }

  if (!page) {
    return (
      <div>
        <Link
          to="/docs"
          className="mb-4 flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="h-4 w-4" />
          Back to Documentation
        </Link>
        <p data-testid="docs-not-found" className="text-sm text-destructive">
          Documentation page not found.
        </p>
      </div>
    );
  }

  return (
    <div>
      <Link
        to="/docs"
        className="mb-4 flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
      >
        <ArrowLeft className="h-4 w-4" />
        Back to Documentation
      </Link>
      <h1 data-testid="docs-page-title" className="mb-6 text-2xl font-semibold">
        {page.title}
      </h1>
      <div data-testid="docs-page-content">
        <MarkdownViewer content={page.content} variant="prose" linkBase="/docs" />
      </div>
    </div>
  );
}

export default function DocsPage() {
  const { slug } = useParams<{ slug?: string }>();

  if (!slug) {
    return <DocsListView />;
  }

  return <DocsPageView slug={slug} />;
}
