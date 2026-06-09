import type { Citation, PackageSummary, ProcessingJob, SourcesResponse, StudyGuide } from './types'

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly code = 'UNKNOWN',
    public readonly retryable = false,
  ) {
    super(message)
  }
}

async function request<T>(input: RequestInfo, init?: RequestInit): Promise<T> {
  const response = await fetch(input, init)
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new ApiError(body.message ?? `请求失败（${response.status}）`, body.errorCode, body.retryable)
  }
  const contentType = response.headers.get('content-type') ?? ''
  if (contentType.includes('application/json')) return response.json() as Promise<T>
  return response.text() as Promise<T>
}

export const api = {
  packages: (filters?: { q?: string; status?: string; type?: string; mastery?: string }) => {
    const params = new URLSearchParams({ limit: '50' })
    if (filters?.q) params.set('q', filters.q)
    if (filters?.status) params.set('status', filters.status)
    if (filters?.type) params.set('type', filters.type)
    if (filters?.mastery) params.set('mastery', filters.mastery)
    return request<PackageSummary[]>(`/api/v1/packages?${params.toString()}`)
  },
  package: (id: string) => request<PackageSummary>(`/api/v1/packages/${id}`),
  sources: (id: string) => request<SourcesResponse>(`/api/v1/packages/${id}/sources`),
  jobs: (id: string) => request<ProcessingJob[]>(`/api/v1/packages/${id}/jobs`),
  note: (id: string) => request<string>(`/api/v1/packages/${id}/note`),
  diagram: (id: string) => request<string>(`/api/v1/packages/${id}/diagram`),
  report: (id: string) => request<StudyGuide>(`/api/v1/packages/${id}/report`),
  citation: (packageId: string, blockId: string) =>
    request<Citation>(`/api/v1/packages/${packageId}/citations/${blockId}`),
  retry: (jobId: string) =>
    request<{ packageId: string; rootJobId: string }>(`/api/v1/jobs/${jobId}/retry`, {
      method: 'POST',
    }),
  deletePackage: (id: string) =>
    request<void>(`/api/v1/packages/${id}`, {
      method: 'DELETE',
    }),
  setMastery: ({ id, mastered }: { id: string; mastered: boolean }) =>
    request<NonNullable<PackageSummary['mastery']>>(`/api/v1/packages/${id}/mastery`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mastered }),
    }),
  createPackage: (form: FormData) =>
    request<{ packageId: string; rootJobId: string }>('/api/v1/packages', {
      method: 'POST',
      body: form,
    }),
  createVideo: (body: Record<string, unknown>) =>
    request<{ packageId: string; rootJobId: string }>('/api/v1/video-packages', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
  capabilities: () =>
    request<Record<string, { available: boolean; model?: string; provider?: string }>>(
      '/api/v1/capabilities',
    ),
}
