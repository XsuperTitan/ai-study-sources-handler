export type PackageStatus =
  | 'QUEUED'
  | 'PROCESSING'
  | 'READY'
  | 'PARTIALLY_READY'
  | 'FAILED'
  | 'INTERRUPTED'

export type JobStage =
  | 'INGEST'
  | 'PARSE'
  | 'VISION'
  | 'DIGEST'
  | 'NOTE'
  | 'REPORT'
  | 'ILLUSTRATION'

export interface PackageSummary {
  id: string
  title: string
  packageType: 'MIXED' | 'VIDEO'
  status: PackageStatus
  currentStage: JobStage
  progress: number
  warnings: string[]
  createdAt: string
  outputs?: {
    noteReady: boolean
    reportReady: boolean
    illustrationReady: boolean
  }
}

export interface ProcessingJob {
  id: string
  packageId: string
  stage: JobStage
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED' | 'INTERRUPTED'
  attempt: number
  progress: number
  errorCode?: string
  errorMessage?: string
  retryable: boolean
  startedAt?: string
  finishedAt?: string
  metrics?: {
    durationMs: number
    provider?: string
    model?: string
    inputTokens: number
    outputTokens: number
    externalRequestCount: number
  }
}

export interface StudyGuide {
  overview: string
  targetAudience: string[]
  difficulty: string
  estimatedMinutes: number
  prerequisites: string[]
  learningObjectives: string[]
  recommendedSequence: string[]
  coreKnowledgePoints: string[]
  keyPoints: string[]
  difficultPoints: string[]
  commonMistakes: string[]
  interviewFocus: string[]
  exercises: string[]
  reviewSchedule: Array<{ afterDays: number; focus: string }>
  completenessWarnings: string[]
  aiRiskWarnings: string[]
}

export interface Citation {
  blockId: string
  sourceKind: string
  displayName: string
  assetUrl?: string
  pageNumber?: number
  paragraphNumber?: number
  startTimeMs?: number
  endTimeMs?: number
  excerpt: string
}

