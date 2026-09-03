import { UploadCloud } from 'lucide-react'
import { useRef, useState, type DragEvent } from 'react'
import { cn } from '@shared/utils/cn'

interface FileDropzoneProps {
  accept: string[]
  maxSizeMb: number
  onFileSelected: (file: File) => void
  disabled?: boolean
  error?: string
}

export function validateFile(file: File, accept: string[], maxSizeMb: number): string | null {
  if (file.size === 0) {
    return 'This file is empty. Please choose a different file.'
  }
  if (file.size > maxSizeMb * 1024 * 1024) {
    return `File is too large. Maximum size is ${maxSizeMb} MB.`
  }
  const extension = file.name.split('.').pop()?.toUpperCase() ?? ''
  const acceptedExtensions = accept.map((type) => type.toUpperCase())
  if (!acceptedExtensions.includes(extension)) {
    return `Unsupported file type. Accepted types: ${accept.join(', ')}.`
  }
  return null
}

export function FileDropzone({ accept, maxSizeMb, onFileSelected, disabled, error }: FileDropzoneProps) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [isDragging, setIsDragging] = useState(false)

  function handleFiles(files: FileList | null) {
    const file = files?.[0]
    if (file) onFileSelected(file)
  }

  function handleDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setIsDragging(false)
    if (disabled) return
    handleFiles(event.dataTransfer.files)
  }

  return (
    <div>
      <div
        role="button"
        tabIndex={disabled ? -1 : 0}
        aria-disabled={disabled}
        onClick={() => !disabled && inputRef.current?.click()}
        onKeyDown={(event) => {
          if (!disabled && (event.key === 'Enter' || event.key === ' ')) {
            event.preventDefault()
            inputRef.current?.click()
          }
        }}
        onDragOver={(event) => {
          event.preventDefault()
          if (!disabled) setIsDragging(true)
        }}
        onDragLeave={() => setIsDragging(false)}
        onDrop={handleDrop}
        className={cn(
          'flex cursor-pointer flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed px-4 py-8 text-center transition-colors',
          isDragging && 'border-primary bg-primary/5',
          !isDragging && 'border-border hover:border-primary/50 hover:bg-muted/40',
          disabled && 'cursor-not-allowed opacity-50 hover:border-border hover:bg-transparent',
          error && 'border-destructive',
        )}
      >
        <UploadCloud className="h-7 w-7 text-muted-foreground" aria-hidden="true" />
        <p className="text-sm font-medium text-foreground">
          <span className="text-primary">Upload a document</span> or drag and drop
        </p>
        <p className="text-xs text-muted-foreground">
          {accept.join(', ')} up to {maxSizeMb} MB
        </p>
        <input
          ref={inputRef}
          type="file"
          className="sr-only"
          disabled={disabled}
          accept={accept.map((ext) => `.${ext.toLowerCase()}`).join(',')}
          onChange={(event) => {
            handleFiles(event.target.files)
            event.target.value = ''
          }}
        />
      </div>
      {error && (
        <p className="mt-1.5 text-xs font-medium text-destructive" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}
