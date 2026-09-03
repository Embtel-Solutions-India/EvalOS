import { useContext } from 'react'
import { ExpertAuthContext } from '@/context/ExpertAuthContext'

export function useExpertAuth() {
  const context = useContext(ExpertAuthContext)
  if (!context) {
    throw new Error('useExpertAuth must be used within an ExpertAuthProvider')
  }
  return context
}
