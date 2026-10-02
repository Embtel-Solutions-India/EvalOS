# Sample documents for the local seed

The five files every document seeded by `db/seed-local/V915` points at (key prefix `seed/`).
Not real documents. Copy them into the local document directory once:

```
mkdir -p .local-documents/seed && cp backend/seed-local-documents/*.pdf backend/seed-local-documents/*.docx backend/seed-local-documents/*.png .local-documents/seed/
```

Kept outside `src/main/resources` on purpose, so they never ship inside the jar.
