import { useEffect, useId, useState } from 'react'
import { Modal, Spin } from 'antd'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeSanitize from 'rehype-sanitize'
import { api } from '../api'
import { prepareMarkdown } from '../markdown'
import type { Citation } from '../types'

function MermaidBlock({ chart }: { chart: string }) {
  const id = useId().replace(/:/g, '')
  const [svg, setSvg] = useState('')
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let active = true
    import('mermaid')
      .then(({ default: mermaid }) => {
        mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'neutral' })
        return mermaid.render(`mermaid-${id}`, chart)
      })
      .then((result) => active && setSvg(result.svg))
      .catch(() => active && setFailed(true))
    return () => {
      active = false
    }
  }, [chart, id])

  if (failed) return <pre className="code-block">{chart}</pre>
  if (!svg) return <Spin size="small" />
  return <div className="mermaid-block" dangerouslySetInnerHTML={{ __html: svg }} />
}

export default function MarkdownViewer({
  markdown,
  packageId,
}: {
  markdown: string
  packageId: string
}) {
  const [citation, setCitation] = useState<Citation | null>(null)
  const [loading, setLoading] = useState(false)

  async function openCitation(blockId: string) {
    setLoading(true)
    try {
      setCitation(await api.citation(packageId, blockId))
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <article className="markdown-paper">
        <ReactMarkdown
          remarkPlugins={[remarkGfm]}
          rehypePlugins={[rehypeSanitize]}
          components={{
            a: ({ href, children }) => {
              if (href?.startsWith('citation://')) {
                const blockId = href.slice('citation://'.length)
                return (
                  <button className="citation-link" onClick={() => openCitation(blockId)}>
                    来源 {children}
                  </button>
                )
              }
              return (
                <a href={href} target="_blank" rel="noopener noreferrer">
                  {children}
                </a>
              )
            },
            code: ({ className, children }) => {
              const language = /language-(\w+)/.exec(className ?? '')?.[1]
              const value = String(children).replace(/\n$/, '')
              return language === 'mermaid' ? (
                <MermaidBlock chart={value} />
              ) : (
                <code className={className}>{children}</code>
              )
            },
            img: ({ src, alt }) => <img src={src} alt={alt ?? '资料图片'} loading="lazy" />,
          }}
        >
          {prepareMarkdown(markdown, packageId)}
        </ReactMarkdown>
      </article>
      <Modal
        open={loading || Boolean(citation)}
        title={citation?.displayName ?? '读取来源'}
        footer={null}
        onCancel={() => setCitation(null)}
      >
        {loading ? (
          <Spin />
        ) : (
          citation && (
            <div className="citation-card">
              <p>{citation.excerpt}</p>
              {citation.assetUrl && (
                <a href={citation.assetUrl} target="_blank" rel="noopener noreferrer">
                  打开原始资源
                </a>
              )}
            </div>
          )
        )}
      </Modal>
    </>
  )
}
