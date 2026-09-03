import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, CheckCircle2, Download, Eye, FileText, History, UploadCloud } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@shared/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@shared/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@shared/components/ui/dialog'
import { FileDropzone, validateFile } from '@shared/components/common/FileDropzone'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { SigningStatusBadge } from '@/components/expert/SigningStatusBadge'
import { DOCUMENT_ACCEPTED_EXTENSIONS, DEFAULT_MAX_FILE_SIZE_MB } from '@shared/constants/upload'
import { getCase, isOverdue, markViewed, uploadSignedDocument } from '@/services/expertCaseService'
import type { ExpertDocumentFile } from '@/types/expert'
import { formatDate, formatFileSize } from '@shared/utils/formatters'

function DocumentRow({ file, badge, onView, onDownload }: { file: ExpertDocumentFile; badge?: string; onView: () => void; onDownload: () => void }) {
  return (
    <div className="flex items-center gap-3 rounded-lg border p-3">
      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
        <FileText className="h-5 w-5" />
      </span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium text-foreground">{file.fileName}</p>
        <p className="text-xs text-muted-foreground">
          {formatFileSize(file.fileSizeBytes)} &middot; {badge ?? 'Uploaded'} {formatDate(file.uploadedAt)}
        </p>
      </div>
      <div className="flex shrink-0 items-center gap-1">
        <Button variant="ghost" size="icon" onClick={onView} aria-label="View">
          <Eye className="h-4 w-4" />
        </Button>
        <Button variant="ghost" size="icon" onClick={onDownload} aria-label="Download">
          <Download className="h-4 w-4" />
        </Button>
      </div>
    </div>
  )
}

export default function ExpertCaseDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { data: expertCase, isLoading } = useQuery({ queryKey: ['expert-case', id], queryFn: () => getCase(id!), enabled: Boolean(id) })

  const [previewFile, setPreviewFile] = useState<ExpertDocumentFile | null>(null)
  const [uploadError, setUploadError] = useState<string | undefined>()
  const [isUploading, setIsUploading] = useState(false)

  useEffect(() => {
    if (expertCase?.signingStatus === 'pending' && id) {
      void markViewed(id).then(() => {
        void queryClient.invalidateQueries({ queryKey: ['expert-case', id] })
        void queryClient.invalidateQueries({ queryKey: ['expert-cases'] })
      })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expertCase?.signingStatus, id])

  if (isLoading) {
    return <ListSkeleton />
  }

  if (!expertCase) {
    return (
      <div className="text-center">
        <p className="text-sm text-muted-foreground">This case couldn't be found.</p>
        <Button asChild variant="outline" className="mt-4">
          <Link to="/expert">
            <ArrowLeft className="h-4 w-4" />
            Back to Dashboard
          </Link>
        </Button>
      </div>
    )
  }

  function handleDownload(fileName: string) {
    toast.success(`${fileName} download started.`)
  }

  async function handleSignedUpload(file: File) {
    const validationError = validateFile(file, DOCUMENT_ACCEPTED_EXTENSIONS, DEFAULT_MAX_FILE_SIZE_MB)
    if (validationError) {
      setUploadError(validationError)
      return
    }
    setUploadError(undefined)
    setIsUploading(true)
    try {
      await uploadSignedDocument(id!, file)
      await queryClient.invalidateQueries({ queryKey: ['expert-case', id] })
      await queryClient.invalidateQueries({ queryKey: ['expert-cases'] })
      toast.success('Signed document submitted.')
    } finally {
      setIsUploading(false)
    }
  }

  return (
    <div>
      <Button variant="ghost" size="sm" className="-ml-2 mb-4" onClick={() => navigate('/expert')}>
        <ArrowLeft className="h-4 w-4" />
        Back to Dashboard
      </Button>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{expertCase.caseReference}</p>
          <h1 className="text-xl font-semibold tracking-tight text-foreground sm:text-2xl">{expertCase.serviceType}</h1>
        </div>
        <SigningStatusBadge expertCase={expertCase} />
      </div>

      <p className="mt-1.5 text-sm text-muted-foreground">
        Assigned {formatDate(expertCase.assignedDate)} &middot; Due {formatDate(expertCase.dueDate)}
        {isOverdue(expertCase) && <span className="ml-1 font-medium text-destructive">— past due</span>}
      </p>

      <Card className="mt-6">
        <CardHeader>
          <CardTitle>Draft Letter</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <DocumentRow
            file={expertCase.draftDocument}
            badge={`Version ${expertCase.draftDocument.version} · Uploaded`}
            onView={() => setPreviewFile(expertCase.draftDocument)}
            onDownload={() => handleDownload(expertCase.draftDocument.fileName)}
          />
        </CardContent>
      </Card>

      {expertCase.previousVersions.length > 0 && (
        <Card className="mt-6">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <History className="h-4 w-4 text-muted-foreground" />
              Previous Versions
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {expertCase.previousVersions.map((version) => (
              <DocumentRow
                key={version.version}
                file={version}
                badge={`Version ${version.version} · Uploaded`}
                onView={() => setPreviewFile(version)}
                onDownload={() => handleDownload(version.fileName)}
              />
            ))}
          </CardContent>
        </Card>
      )}

      <Card className="mt-6">
        <CardHeader>
          <CardTitle>Sign &amp; Submit</CardTitle>
        </CardHeader>
        <CardContent>
          {expertCase.signedDocument ? (
            <div className="flex items-center gap-2.5 rounded-lg border border-success/30 bg-success/5 p-4">
              <CheckCircle2 className="h-5 w-5 shrink-0 text-success" />
              <div>
                <p className="text-sm font-semibold text-foreground">Signed copy submitted</p>
                <p className="text-sm text-muted-foreground">
                  {expertCase.signedDocument.fileName} &middot; {formatDate(expertCase.signedDocument.uploadedAt)}
                </p>
              </div>
            </div>
          ) : (
            <div>
              <p className="mb-3 text-sm text-muted-foreground">
                Download the draft letter, sign it, then upload your signed copy here to submit it back to International Evaluations.
              </p>
              <FileDropzone
                accept={DOCUMENT_ACCEPTED_EXTENSIONS}
                maxSizeMb={DEFAULT_MAX_FILE_SIZE_MB}
                onFileSelected={(file) => void handleSignedUpload(file)}
                disabled={isUploading}
                error={uploadError}
              />
              {isUploading && <p className="mt-2 text-xs text-muted-foreground">Uploading…</p>}
            </div>
          )}
        </CardContent>
      </Card>

      <Dialog open={Boolean(previewFile)} onOpenChange={(open) => !open && setPreviewFile(null)}>
        <DialogContent>
          <DialogHeader>
            <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-primary/10 text-primary">
              <UploadCloud className="h-6 w-6" />
            </div>
            <DialogTitle className="text-center">{previewFile?.fileName}</DialogTitle>
            <DialogDescription className="text-center">
              {previewFile ? `${formatFileSize(previewFile.fileSizeBytes)} · Uploaded ${formatDate(previewFile.uploadedAt)}` : ''}
            </DialogDescription>
          </DialogHeader>
          <p className="text-center text-sm text-muted-foreground">
            Document preview isn't available in this environment. Use Download to save a copy.
          </p>
          <Button
            className="mt-2"
            onClick={() => {
              if (previewFile) handleDownload(previewFile.fileName)
            }}
          >
            <Download className="h-4 w-4" />
            Download
          </Button>
        </DialogContent>
      </Dialog>
    </div>
  )
}
