import { ArrowRightOutlined, DeleteOutlined, FileAddOutlined, LinkOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button, Empty, Modal, Skeleton, message } from 'antd'
import type { MouseEvent } from 'react'
import { Link } from 'react-router'
import { api } from '../api'
import StatusBadge from '../components/StatusBadge'
import type { PackageSummary } from '../types'

const deletableStatuses = new Set(['READY', 'PARTIALLY_READY', 'FAILED', 'INTERRUPTED'])

export default function HomePage() {
  const queryClient = useQueryClient()
  const packages = useQuery({ queryKey: ['packages'], queryFn: api.packages })
  const capabilities = useQuery({ queryKey: ['capabilities'], queryFn: api.capabilities })
  const deletePackage = useMutation({
    mutationFn: api.deletePackage,
    onSuccess: () => {
      message.success('资料包已删除')
      queryClient.invalidateQueries({ queryKey: ['packages'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  function confirmDelete(event: MouseEvent, item: PackageSummary) {
    event.preventDefault()
    event.stopPropagation()
    if (!deletableStatuses.has(item.status)) return
    Modal.confirm({
      title: '删除资料包？',
      content: `将永久删除“${item.title}”及其本地相关文件，此操作不可恢复。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => deletePackage.mutateAsync(item.id),
    })
  }

  return (
    <div className="page home-page">
      <section className="hero-grid">
        <div className="hero-copy">
          <span className="eyebrow">PERSONAL RESEARCH DESK / 01</span>
          <h1>
            把散落的资料，
            <br />
            整理成<span>有出处</span>的知识。
          </h1>
          <p>
            混合提交 PDF、文本与截图。系统异步解析内容，生成可回到原页、原图和视频时间点的学习笔记。
          </p>
          <div className="hero-actions">
            <Link to="/packages/new">
              <Button type="primary" size="large" icon={<FileAddOutlined />}>
                新建资料包
              </Button>
            </Link>
            <Link to="/videos/new">
              <Button size="large" icon={<LinkOutlined />}>
                解析 B 站视频
              </Button>
            </Link>
          </div>
        </div>
        <div className="capability-board">
          <div className="board-title">能力状态</div>
          {capabilities.data ? (
            Object.entries(capabilities.data).map(([name, value]) => (
              <div className="capability-row" key={name}>
                <span>{name}</span>
                <i className={value.available ? 'online' : 'offline'} />
                <small>{value.model ?? value.provider ?? (value.available ? '可用' : '未配置')}</small>
              </div>
            ))
          ) : (
            <Skeleton active paragraph={{ rows: 3 }} />
          )}
        </div>
      </section>

      <section className="library-section">
        <div className="section-heading">
          <div>
            <span className="eyebrow">ARCHIVE / RECENT</span>
            <h2>最近资料包</h2>
          </div>
          <span>{packages.data?.length ?? 0} 份记录</span>
        </div>
        {packages.isLoading ? (
          <Skeleton active />
        ) : packages.data?.length ? (
          <div className="package-grid">
            {packages.data.map((item, index) => (
              <Link className="package-card" to={`/packages/${item.id}`} key={item.id}>
                <div className="card-index">{String(index + 1).padStart(2, '0')}</div>
                <Button
                  aria-label={`删除 ${item.title}`}
                  className="card-delete"
                  disabled={!deletableStatuses.has(item.status) || deletePackage.isPending}
                  icon={<DeleteOutlined />}
                  onClick={(event) => confirmDelete(event, item)}
                  title={deletableStatuses.has(item.status) ? '删除资料包' : '处理中不可删除'}
                  type="text"
                />
                <div className="card-topline">
                  <span>{item.packageType === 'VIDEO' ? 'VIDEO' : 'MIXED SOURCE'}</span>
                  <StatusBadge status={item.status} />
                </div>
                <h3>{item.title}</h3>
                <div className="card-meta">
                  <span>{new Date(item.createdAt).toLocaleString('zh-CN')}</span>
                  <ArrowRightOutlined />
                </div>
              </Link>
            ))}
          </div>
        ) : (
          <Empty description="还没有资料包，先提交一份学习材料。" />
        )}
      </section>
    </div>
  )
}
