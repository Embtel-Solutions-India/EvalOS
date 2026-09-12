// Upload limits, shared by both portals because both upload files: the client
// sends documents against a checklist item, the expert sends the signed letter
// back. The server is the real guard — these are what the control tells the
// person before they pick a file.
export const DOCUMENT_ACCEPTED_EXTENSIONS = ['PDF', 'JPG', 'JPEG', 'PNG', 'DOC', 'DOCX']

export const DEFAULT_MAX_FILE_SIZE_MB = 10
