import type { PackageStatus } from '../types'

const labels: Record<PackageStatus, string> = {
  QUEUED: '排队中',
  PROCESSING: '处理中',
  READY: '已完成',
  PARTIALLY_READY: '部分完成',
  FAILED: '失败',
  INTERRUPTED: '已中断',
}

export default function StatusBadge({ status }: { status: PackageStatus }) {
  return <span className={`status-badge status-${status.toLowerCase()}`}>{labels[status]}</span>
}

