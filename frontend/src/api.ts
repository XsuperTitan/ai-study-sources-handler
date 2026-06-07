import type { Citation, PackageSummary, ProcessingJob, StudyGuide } from './types'

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
  packages: () => request<PackageSummary[]>('/api/v1/packages?limit=50'),
  package: (id: string) => request<PackageSummary>(`/api/v1/packages/${id}`),
  jobs: (id: string) => request<ProcessingJob[]>(`/api/v1/packages/${id}/jobs`),
  note: (id: string) => request<string>(`/api/v1/packages/${id}/note`),
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
