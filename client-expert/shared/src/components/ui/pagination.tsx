import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from '@shared/components/ui/button'
import { cn } from '@shared/utils/cn'

interface PaginationProps {
  page: number
  pageCount: number
  onPageChange: (page: number) => void
  className?: string
}

function getPageNumbers(page: number, pageCount: number): (number | 'ellipsis')[] {
  if (pageCount <= 7) return Array.from({ length: pageCount }, (_, i) => i + 1)

  const pages = new Set<number>([1, pageCount, page, page - 1, page + 1])
  const sorted = [...pages].filter((p) => p >= 1 && p <= pageCount).sort((a, b) => a - b)

  const result: (number | 'ellipsis')[] = []
  sorted.forEach((p, index) => {
    if (index > 0 && p - sorted[index - 1] > 1) result.push('ellipsis')
    result.push(p)
  })
  return result
}

export function Pagination({ page, pageCount, onPageChange, className }: PaginationProps) {
  if (pageCount <= 1) return null

  return (
    <nav aria-label="Pagination" className={cn('flex items-center justify-between gap-3', className)}>
      <Button
        type="button"
        variant="outline"
        size="sm"
        onClick={() => onPageChange(page - 1)}
        disabled={page <= 1}
      >
        <ChevronLeft className="h-4 w-4" />
        Previous
      </Button>

      <div className="hidden items-center gap-1 sm:flex">
        {getPageNumbers(page, pageCount).map((item, index) =>
          item === 'ellipsis' ? (
            <span key={`ellipsis-${index}`} className="px-2 text-sm text-muted-foreground">
              &hellip;
            </span>
          ) : (
            <button
              key={item}
              type="button"
              aria-current={item === page ? 'page' : undefined}
              onClick={() => onPageChange(item)}
              className={cn(
                'flex h-8 w-8 items-center justify-center rounded-md text-sm font-medium transition-colors',
                item === page ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-accent',
              )}
            >
              {item}
            </button>
          ),
        )}
      </div>

      <span className="text-sm text-muted-foreground sm:hidden">
        Page {page} of {pageCount}
      </span>

      <Button
        type="button"
        variant="outline"
        size="sm"
        onClick={() => onPageChange(page + 1)}
        disabled={page >= pageCount}
      >
        Next
        <ChevronRight className="h-4 w-4" />
      </Button>
    </nav>
  )
}
