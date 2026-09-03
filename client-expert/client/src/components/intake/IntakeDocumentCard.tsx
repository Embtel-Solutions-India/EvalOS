import { File as FileIcon, RotateCcw } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Badge } from '@shared/components/ui/badge'
import { Button } from '@shared/components/ui/button'
import { Card, CardContent, CardHeader } from '@shared/components/ui/card'
import { Progress } from '@shared/components/ui/progress'
import { FileDropzone, validateFile } from '@shared/components/common/FileDropzone'
import { IntakeDocumentStatusBadge } from '@/components/intake/IntakeDocumentStatusBadge'
import { simulateUpload } from '@/mock/simulateUpload'
import type { IntakeDocument } from '@/types/intake'
import { formatFileSize } from '@shared/utils/formatters'

interface IntakeDocumentCardProps {
  document: IntakeDocument
  onChange: (updated: IntakeDocument) => void
}

export function IntakeDocumentCard({ document, onChange }: IntakeDocumentCardProps) {
  const [error, setError] = useState<string | null>(null)
  const [progress, setProgress] = useState<number | null>(null)

  async function handleFileSelected(file: File) {
    const validationError = validateFile(file, document.acceptedTypes, document.maxSizeMb)
    if (validationError) {
      setError(validationError)
      return
    }
    setError(null)
    setProgress(0)
    await simulateUpload(file, setProgress)
    setProgress(null)
    onChange({
      ...document,
      status: 'uploaded',
      file: {
        fileName: file.name,
        fileSizeBytes: file.size,
        fileType: file.type,
        uploadedAt: new Date().toISOString(),
      },
    })
    toast.success('Document uploaded.')
  }

  const isUploading = progress !== null

  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-3 space-y-0">
        <div>
          <div className="flex items-center gap-2">
            <h4 className="text-sm font-semibold text-foreground">{document.name}</h4>
            <Badge variant={document.required ? 'outline' : 'muted'}>{document.required ? 'Required' : 'Optional'}</Badge>
          </div>
          <p className="mt-1 text-sm text-muted-foreground">{document.description}</p>
        </div>
        <IntakeDocumentStatusBadge status={document.status} className="shrink-0" />
      </CardHeader>
      <CardContent>
        {isUploading && (
          <div className="space-y-2">
            <p className="text-xs font-medium text-muted-foreground">Uploading...</p>
            <Progress value={progress ?? 0} />
          </div>
        )}

        {!isUploading && document.file && (
          <div className="flex flex-col gap-3 rounded-lg border bg-muted/30 p-3 sm:flex-row sm:items-center sm:justify-between">
            <div className="flex items-center gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
                <FileIcon className="h-5 w-5" />
              </div>
              <div>
                <p className="text-sm font-medium text-foreground">{document.file.fileName}</p>
                <p className="text-xs text-muted-foreground">{formatFileSize(document.file.fileSizeBytes)}</p>
              </div>
            </div>
            <div className="flex gap-2 sm:shrink-0">
              <Button type="button" variant="outline" size="sm" onClick={() => toast.info('Preview is a mock in this development phase.')}>
                Preview
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => {
                  setError(null)
                  window.document.getElementById(`intake-replace-${document.id}`)?.click()
                }}
              >
                <RotateCcw className="h-3.5 w-3.5" />
                Replace
              </Button>
              <input
                id={`intake-replace-${document.id}`}
                type="file"
                className="sr-only"
                accept={document.acceptedTypes.map((ext) => `.${ext.toLowerCase()}`).join(',')}
                onChange={(event) => {
                  const file = event.target.files?.[0]
                  event.target.value = ''
                  if (file) void handleFileSelected(file)
                }}
              />
            </div>
          </div>
        )}

        {!isUploading && !document.file && (
          <FileDropzone
            accept={document.acceptedTypes}
            maxSizeMb={document.maxSizeMb}
            onFileSelected={(file) => void handleFileSelected(file)}
            error={error ?? undefined}
          />
        )}
      </CardContent>
    </Card>
  )
}
