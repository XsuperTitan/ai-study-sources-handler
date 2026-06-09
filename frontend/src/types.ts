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
  cover?: {
    imageUrl?: string
    keywords: string[]
  }
  mastery?: {
    packageId: string
    mastered: boolean
    masteredAt?: string
    updatedAt?: string
  }
  outputs?: {
    noteReady: boolean
    reportReady: boolean
    diagramReady?: boolean
    diagramTitle?: string
    diagramUrl?: string
    illustrationReady: boolean
    illustrationAssetId?: string
    illustrationAssetUrl?: string
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

export interface SourceItem {
  id: string
  kind: 'PDF' | 'TEXT_FILE' | 'PASTED_TEXT' | 'IMAGE' | 'VIDEO'
  originalName: string
  sequence: number
  assetId?: string
  assetUrl?: string
  sourceUrl?: string
  contentType?: string
  size?: number
  metadata: Record<string, unknown>
}

export interface SourceAsset {
  id: string
  originalName: string
  contentType: string
  size: number
  assetUrl: string
}

export interface SourcesResponse {
  items: SourceItem[]
  assets: SourceAsset[]
}
