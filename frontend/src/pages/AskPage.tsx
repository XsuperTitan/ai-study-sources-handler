import { DatabaseOutlined, ReloadOutlined, SendOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Checkbox, Empty, Input, Skeleton, message } from 'antd'
import { useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Link } from 'react-router'
import { api } from '../api'

export default function AskPage() {
  const queryClient = useQueryClient()
  const [question, setQuestion] = useState('')
  const [selectedPackages, setSelectedPackages] = useState<string[]>([])
  const status = useQuery({ queryKey: ['rag-status'], queryFn: api.ragStatus })
  const packages = useQuery({
    queryKey: ['packages', { mastery: 'ALL' }],
    queryFn: () => api.packages({ mastery: 'ALL' }),
  })
  const ask = useMutation({
    mutationFn: api.askRag,
    onError: (error: Error) => message.error(error.message),
  })
  const reindex = useMutation({
    mutationFn: api.reindexAll,
    onSuccess: (result) => {
      message.success(`已索引 ${result.packagesIndexed} 个资料包、${result.chunksIndexed} 个片段`)
      queryClient.invalidateQueries({ queryKey: ['rag-status'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  function submit() {
    if (!question.trim()) return
    ask.mutate({
      question: question.trim(),
      packageIds: selectedPackages.length ? selectedPackages : undefined,
      topK: 12,
    })
  }

  return (
    <div className="page ask-page">
      <section className="ask-heading">
        <div>
          <span className="eyebrow">KNOWLEDGE RETRIEVAL / RAG</span>
          <h1>向你的资料库提问</h1>
          <p>先检索原始内容块，再由 DeepSeek 基于证据回答。每条来源都能回到对应资料卡片。</p>
        </div>
        <div className="rag-status-card">
          <DatabaseOutlined />
          {status.isLoading ? <Skeleton active paragraph={{ rows: 2 }} /> : (
            <>
              <strong>{status.data?.chromaAvailable ? '索引在线' : '索引未就绪'}</strong>
              <span>{status.data?.indexedChunks ?? 0} 个内容片段</span>
              <small>{status.data?.message}</small>
              <Button
                icon={<ReloadOutlined />}
                loading={reindex.isPending}
                onClick={() => reindex.mutate()}
              >
                重建全库索引
              </Button>
            </>
          )}
        </div>
      </section>

      {!status.isLoading && !status.data?.chromaAvailable ? (
        <Alert type="warning" showIcon message="请先启动 Chroma，再执行全库索引。" />
      ) : null}

      <section className="ask-workbench">
        <div className="ask-composer">
          <Input.TextArea
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            onPressEnter={(event) => {
              if (!event.shiftKey) {
                event.preventDefault()
                submit()
              }
            }}
            autoSize={{ minRows: 3, maxRows: 7 }}
            placeholder="例如：这些资料里如何解释线程池的任务提交流程？"
          />
          <Button type="primary" icon={<SendOutlined />} loading={ask.isPending} onClick={submit}>
            检索并回答
          </Button>
        </div>
        <div className="ask-scope">
          <strong>检索范围</strong>
          <small>不选择时检索整个资料库</small>
          <Checkbox.Group
            value={selectedPackages}
            onChange={(values) => setSelectedPackages(values as string[])}
          >
            {packages.data?.filter((item) => item.status === 'READY' || item.status === 'PARTIALLY_READY').map((item) => (
              <Checkbox value={item.id} key={item.id}>{item.title}</Checkbox>
            ))}
          </Checkbox.Group>
        </div>
      </section>

      <section className="ask-result">
        {ask.isPending ? <Skeleton active paragraph={{ rows: 8 }} /> : ask.data ? (
          <>
            <article className="markdown-paper rag-answer">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{ask.data.answerMarkdown}</ReactMarkdown>
            </article>
            <div className="rag-citations">
              {ask.data.citations.map((citation) => (
                <Link to={`/packages/${citation.packageId}`} key={citation.citationId}>
                  <span>{citation.citationId}</span>
                  <strong>{citation.title}</strong>
                  <p>{citation.excerpt}</p>
                  <small>相关度 {Math.max(0, citation.score * 100).toFixed(0)}%</small>
                </Link>
              ))}
            </div>
          </>
        ) : (
          <Empty description="问题和证据会在这里汇合。" />
        )}
      </section>
    </div>
  )
}
