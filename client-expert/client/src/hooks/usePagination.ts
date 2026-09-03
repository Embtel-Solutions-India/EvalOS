import { useMemo, useState } from 'react'

export function usePagination<T>(items: T[], pageSize = 8) {
  const [page, setPage] = useState(1)

  const pageCount = Math.max(1, Math.ceil(items.length / pageSize))
  const safePage = Math.min(page, pageCount)

  const pageItems = useMemo(
    () => items.slice((safePage - 1) * pageSize, safePage * pageSize),
    [items, safePage, pageSize],
  )

  return { page: safePage, pageCount, pageItems, setPage }
}
